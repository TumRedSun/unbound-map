package net.unbound.maps.mapdata;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.MapColor;
import net.unbound.maps.UnboundConfig;

/**
 * Computes and caches the 16x16 color tiles for both the minimap and the world map.
 *
 * Surface tiles: WORLD_SURFACE heightmap + block map color, with optional
 * biome tinting (grass/foliage/water), dynamic light shading (block light from
 * torches/lava/glowstone glows at night, sky light follows the day cycle) and
 * cross-chunk hillshading.
 * Slice (cave) tiles: Xaero-style "cut lid" view. Everything above the view
 * height is treated as removed; the first non-air block below it (a cave floor,
 * a ravine bottom, lava) is rendered at any depth, lit by the light inside the
 * cavity itself, with 16 perceptual light gradations.
 *
 * Final tiles are recorded in {@link MapStore} so explored-but-unloaded chunks
 * keep rendering from disk.
 */
public final class MapTileCache {
    public static final int UNKNOWN = -15723752;
    private static final int MAX_ENTRIES = 40000;
    private static final int MAX_SLICE_ENTRIES = 8000;
    private static final int MAX_SLICE_BUCKETS = 4;
    private static final Map<Long, MapTileCache.Entry> CHUNKS = new LinkedHashMap<>(4096, 0.75F, true);
    /** Slice (cave) tiles per 8-block bucket; access-ordered so old buckets can be trimmed. */
    private static final Map<Integer, Map<Long, MapTileCache.Entry>> SLICES = new LinkedHashMap<>(8, 0.75F, true);
    private static boolean cave;
    private static int sliceBucket;
    /** Minimap view state (always automatic, see {@link CaveState}). */
    private static boolean miniCave;
    private static int miniBucket;
    /** 16 perceptual light gradations: caves and torch-lit areas get many distinguishable steps. */
    private static final float[] LIGHT_CURVE = {
        0.16F, 0.19F, 0.23F, 0.27F, 0.31F, 0.35F, 0.40F, 0.45F,
        0.51F, 0.57F, 0.64F, 0.71F, 0.79F, 0.87F, 0.94F, 1.00F
    };
    /** How much sky light each night-dim step removes (config 0..3; 0 = Xaero-like constant colors). */
    private static final float NIGHT_STEP = 0.12F;
    private static int nightStrength = 0;
    /** Global look: gentle gamma lift (brighter mids) + mild saturation boost, applied to every rendered pixel. */
    private static final int[] ENHANCE_LUT = new int[256];
    private static final float ENHANCE_GAMMA = 0.92F;
    private static final float ENHANCE_SATURATION = 1.06F;

    static {
        for (int i = 0; i < 256; i++) {
            ENHANCE_LUT[i] = Math.round(255.0F * (float)Math.pow(i / 255.0, ENHANCE_GAMMA));
        }
    }

    private MapTileCache() {
    }

    /** True while the WORLD MAP renders the cave (slice) view. Driven by {@link CaveState}. */
    public static boolean isCave() {
        return cave;
    }

    /** World map active slice bucket (8-block altitude band). */
    public static int caveBucket() {
        return sliceBucket;
    }

    /** True while the MINIMAP renders the cave (slice) view - always automatic. */
    public static boolean isMiniCave() {
        return miniCave;
    }

    /** Minimap active slice bucket (8-block altitude band). */
    public static int miniBucket() {
        return miniBucket;
    }

    /**
     * Re-syncs the view flags from {@link CaveState} and invalidates caches
     * when the view mode or the slice bucket changed. Cheap to call every tick.
     */
    public static void onCaveViewChanged() {
        boolean newCave = CaveState.isWorldCave();
        int newBucket = bucketOf(CaveState.worldSliceY());
        if (newCave != cave || newBucket != sliceBucket) {
            cave = newCave;
            sliceBucket = newBucket;
            // the per-bucket tile caches are KEPT: moving up or down a cave reuses
            // the slices computed for heights you already visited (clearing them
            // made the view go dark for seconds on every 8-block height change)
            trimSlices();
        }

        boolean newMiniCave = CaveState.isMiniCave();
        int newMiniBucket = bucketOf(CaveState.miniSliceY());
        if (newMiniCave != miniCave || newMiniBucket != miniBucket) {
            miniCave = newMiniCave;
            miniBucket = newMiniBucket;
            trimSlices();
            MinimapTexture.invalidateAll();
        }

        // Day/night: the dim is applied at DRAW time (see nightFactor), so tiles
        // keep their colors around the clock and never re-bake when the sky
        // darkens. With strength 0 (default) the map is full-bright like Xaero's.
        nightStrength = intFlag(UnboundConfig.NIGHT_DIM_STRENGTH, 0);
    }

    /** Keeps only the most recently used slice buckets (the active ones are touched on access). */
    private static void trimSlices() {
        SLICES.get(sliceBucket); // refresh access order
        SLICES.get(miniBucket);
        while (SLICES.size() > MAX_SLICE_BUCKETS) {
            Integer oldest = null;
            for (Integer key : SLICES.keySet()) {
                if (key != sliceBucket && key != miniBucket) {
                    oldest = key;
                    break;
                }
            }
            if (oldest == null) {
                break;
            }
            SLICES.remove(oldest);
        }
    }

    /**
     * Uniform day/night dim factor for surface map tiles, applied at draw time
     * (shader color / vertex tint): 1.0 by day, down to 1 - strength*0.12 by
     * full night (strength 0 = constant Xaero-like colors, 3 = 36% darker).
     * The cave view is exempt: it is lit by block light, not by the sun.
     */
    public static float nightFactor(boolean caveView) {
        if (nightStrength <= 0 || caveView) {
            return 1.0F;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return 1.0F;
        }
        return 1.0F - Math.min(1.0F, nightStrength * NIGHT_STEP) * (float)mc.level.getSkyDarken() / 15.0F;
    }

    /**
     * Xaero-like light for the cave (slice) view only: block light (torches, lava,
     * glowstone) keeps its glow, sky light in the cavity follows the day cycle.
     */
    private static float lightShade(ClientLevel var0, int var1, int var2) {
        float var4 = LIGHT_CURVE[var2 & 15];
        float var5 = LIGHT_CURVE[var1 & 15];
        if (nightStrength > 0) {
            var5 *= 1.0F - nightStrength * NIGHT_STEP * (float)var0.getSkyDarken() / 15.0F;
        }

        return Math.max(0.16F, Math.max(var4, var5));
    }

    /**
     * The final look of EVERY map pixel (surface, cave slice, live or stored):
     * a gentle gamma lift makes mid-tones brighter and softer, a mild saturation
     * boost keeps the colors juicy. Lava and other self-lit blocks stay vivid.
     */
    public static int enhance(int var0) {
        if (var0 == UNKNOWN || (var0 >>> 24) != 0xFF) {
            return var0;
        }
        int var1 = ENHANCE_LUT[var0 & 0xFF];
        int var2 = ENHANCE_LUT[var0 >>> 8 & 0xFF];
        int var3 = ENHANCE_LUT[var0 >>> 16 & 0xFF];
        float var4 = 0.299F * var1 + 0.587F * var2 + 0.114F * var3;
        int var5 = clamp255(Math.round(var4 + (var1 - var4) * ENHANCE_SATURATION));
        int var6 = clamp255(Math.round(var4 + (var2 - var4) * ENHANCE_SATURATION));
        int var7 = clamp255(Math.round(var4 + (var3 - var4) * ENHANCE_SATURATION));
        return var0 & 0xFF000000 | var7 << 16 | var6 << 8 | var5;
    }

    private static int clamp255(int var0) {
        return var0 < 0 ? 0 : Math.min(255, var0);
    }

    /** Lava glows: blend the map color toward a vivid bright orange, no depth darkening. */
    private static int lavaColor(int var0) {
        int var1 = Math.round((var0 & 0xFF) * 0.55F + 255.0F * 0.45F);
        int var2 = Math.round((var0 >>> 8 & 0xFF) * 0.55F + 176.0F * 0.45F);
        int var3 = Math.round((var0 >>> 16 & 0xFF) * 0.55F + 64.0F * 0.45F);
        return var0 & 0xFF000000 | clamp255(var3) << 16 | clamp255(var2) << 8 | clamp255(var1);
    }

    public static int bucketOf(int var0) {
        return var0 + 512 >> 3;
    }

    /**
     * Cut height of a slice bucket - the bucket's LOWEST block. The cut is never
     * ABOVE the player's feet (the old center-of-bucket cut showed the floor of
     * the level ABOVE the player for half of every 8-block band, which looked
     * like the map "not showing my height").
     */
    public static int bucketCenterY(int var0) {
        return (var0 << 3) - 512;
    }

    public static int sliceRange() {
        try {
            return UnboundConfig.CAVE_RANGE.get();
        } catch (NullPointerException | IllegalStateException var1) {
            return 10;
        }
    }

    private static boolean flag(net.neoforged.neoforge.common.ModConfigSpec.BooleanValue var0, boolean var1) {
        try {
            return var0.get();
        } catch (NullPointerException | IllegalStateException var2) {
            return var1;
        }
    }

    private static int intFlag(net.neoforged.neoforge.common.ModConfigSpec.IntValue var0, int var1) {
        try {
            return var0.get();
        } catch (NullPointerException | IllegalStateException var2) {
            return var1;
        }
    }

    public static int colorFor(ClientLevel var0, int var1, int var2, boolean var3, int var4) {
        return var3 ? getSliceColor(var0, var1, var2, var4) : getColor(var0, var1, var2);
    }

    public static int columnY(ClientLevel var0, int var1, int var2, int var3, boolean var4) {
        long var5 = ChunkPos.asLong(var1 >> 4, var2 >> 4);
        if (var4) {
            Map<Long, MapTileCache.Entry> var6 = SLICES.get(sliceBucket);
            if (var6 != null) {
                MapTileCache.Entry var7 = var6.get(var5);
                if (var7 != null) {
                    int var8 = var7.heights[(var2 & 15) << 4 | var1 & 15];
                    if (var8 != Integer.MIN_VALUE) {
                        return var8;
                    }
                }
            }

            return sliceTopY(var0, var1, var2, var3);
        } else {
            MapTileCache.Entry var6 = CHUNKS.get(var5);
            if (var6 != null) {
                int var7 = var6.heights[(var2 & 15) << 4 | var1 & 15];
                if (var7 != Integer.MIN_VALUE) {
                    // heights hold the Y of the TOP surface block itself
                    return var7;
                }
            }

            int var8 = surfaceHeight(var0, var1, var2);
            return var8 != Integer.MIN_VALUE ? var8 : var3;
        }
    }

    public static boolean contains(long var0) {
        return CHUNKS.containsKey(var0);
    }

    /**
     * True when a redraw of this chunk would come straight from the cache (cheap),
     * false when it would trigger a fresh heightmap / cut-lid computation.
     * Used to budget fresh work without throttling cache redraws.
     */
    public static boolean isCached(long var0, boolean var1, int var2) {
        if (var1) {
            Map<Long, MapTileCache.Entry> var3 = SLICES.get(var2);
            return var3 != null && var3.containsKey(var0);
        }
        return CHUNKS.containsKey(var0);
    }

    /**
     * True when the tile cache has no LIVE data for this chunk in the given view
     * mode (nothing computed yet, or only the persistent-store copy while the real
     * chunk is loaded) - such chunks are picked up by the periodic loaded-chunk scan
     * so freshly explored terrain reaches the maps without waiting for the player.
     */
    public static boolean needsRefresh(ClientLevel var0, long var1, boolean var2, int var3) {
        if (var2) {
            Map<Long, MapTileCache.Entry> var4 = SLICES.get(var3);
            return var4 == null || !var4.containsKey(var1);
        }
        MapTileCache.Entry var5 = CHUNKS.get(var1);
        if (var5 == null) {
            return true;
        }
        int var6 = ChunkPos.getX(var1);
        int var7 = ChunkPos.getZ(var1);
        return var5.stored && var0.getChunk(var6, var7, ChunkStatus.FULL, false) != null;
    }

    public static void clearAll() {
        CHUNKS.clear();
        SLICES.clear();
    }

    public static void invalidate(long var0) {
        MapTileCache.Entry var2 = CHUNKS.remove(var0);
        boolean var3 = false;

        for (Map var5 : SLICES.values()) {
            if (var5.remove(var0) != null) {
                var3 = true;
            }
        }

        if (var2 != null || var3) {
            MinimapTexture.markChunkDirty(var0);
            WorldMapCells.markChunkDirty(var0);
        }
    }

    public static int getColor(ClientLevel var0, int var1, int var2) {
        int var3 = var1 >> 4;
        int var4 = var2 >> 4;
        long var5 = ChunkPos.asLong(var3, var4);
        MapTileCache.Entry var7 = CHUNKS.get(var5);
        if (var7 == null) {
            var7 = computeEntry(var0, var3, var4, true);
            if (var7 == null) {
                return -15723752;
            }

            storeWithLimit(CHUNKS, var5, var7, 40000);
            MinimapTexture.markChunkDirty(var5);
            WorldMapCells.markChunkDirty(var5);
        } else if (var7.stored && var0.getChunk(var3, var4, ChunkStatus.FULL, false) != null) {
            var7 = computeEntry(var0, var3, var4, true);
            if (var7 == null) {
                return -15723752;
            }

            CHUNKS.put(var5, var7);
            MinimapTexture.markChunkDirty(var5);
            WorldMapCells.markChunkDirty(var5);
        } else if (!var7.shaded) {
            shadeEntry(var0, var3, var4, var7);
            MinimapTexture.markChunkDirty(var5);
            WorldMapCells.markChunkDirty(var5);
        }

        return enhance(var7.colors[(var2 & 15) << 4 | var1 & 15]);
    }

    public static int surfaceHeight(ClientLevel var0, int var1, int var2) {
        ChunkAccess var3 = var0.getChunk(var1 >> 4, var2 >> 4, ChunkStatus.FULL, false);
        return var3 == null ? Integer.MIN_VALUE : var3.getHeight(Types.WORLD_SURFACE, var1 & 15, var2 & 15);
    }

    /**
     * Computes a chunk entry from live world data (biome tint + water tint),
     * falling back to the persistent store when the chunk is unloaded.
     * Surface colors are full-bright Xaero-style: map color + biome tint + water
     * depth + hillshade. Day/night is a uniform draw-time dim (the draw-time night dim),
     * so tiles never depend on the time of day (the old per-column light capture
     * sampled the sky INSIDE opaque surface blocks and blacked out the land at night).
     * When {@code allowShade} is true the hillshade pass runs immediately;
     * neighbors needed by it are pre-computed raw (one level of recursion at most).
     */
    private static MapTileCache.Entry computeEntry(ClientLevel var0, int var1, int var2, boolean var3) {
        long var4 = ChunkPos.asLong(var1, var2);
        ChunkAccess var6 = var0.getChunk(var1, var2, ChunkStatus.FULL, false);
        if (var6 == null) {
            if (MapStore.isEnabled()) {
                MapStore.StoredChunk var7 = MapStore.storedChunk(var0.dimension(), var4);
                if (var7 != null) {
                    int[] var8 = var7.colors();
                    // Legacy repair: chunks explored before 1.10.0 painted the block
                    // UNDER the surface, so every tree canopy (air under the top leaf)
                    // was stored as UNKNOWN and showed as black blobs on the map.
                    // Those columns are certainly tree tops - render the default
                    // foliage green instead of the "unknown" hole. The stored array
                    // is shared with the region cache, so repair on a CLONE only.
                    if (var8 != null) {
                        int[] var9 = var8;
                        boolean var10 = false;
                        for (int var11 = 0; var11 < 256; var11++) {
                            if (var9[var11] == -15723752
                                && var7.heights() != null
                                && var7.heights()[var11] != Integer.MIN_VALUE) {
                                if (!var10) {
                                    var9 = var8.clone();
                                    var10 = true;
                                }

                                var9[var11] = tintAbgr(mapColorAbgr(MapColor.PLANT), 0x48B518);
                            }
                        }

                        var8 = var9;
                    }

                    // stored light nibbles (v2 format) were captured with the old buggy
                    // sampling and are intentionally ignored: surface tiles are full-bright
                    return new MapTileCache.Entry(
                        var8, var7.heights() == null ? defaultHeights() : var7.heights(), true, true
                    );
                }
            }

            return null;
        } else {
            boolean var29 = flag(UnboundConfig.BIOME_SHADING, true);
            boolean var30 = flag(UnboundConfig.LIGHT_SHADING, true);
            boolean var31 = MapStore.isEnabled();
            int[] var32 = new int[256];
            int[] var33 = new int[256];
            int var9 = var1 << 4;
            int var10 = var2 << 4;
            int var11 = var0.getMinBuildHeight();
            MutableBlockPos var12 = new MutableBlockPos();

            for (int var13 = 0; var13 < 16; var13++) {
                for (int var14 = 0; var14 < 16; var14++) {
                    int var15 = var13 << 4 | var14;
                    // ChunkAccess.getHeight already returns the Y of the TOP non-air
                    // block (getFirstAvailable - 1) - the old code read h-1 and painted
                    // the block UNDER the surface (dirt where grass should be, air
                    // under tree canopies -> black "unknown" forest, "chunk noise").
                    int var16 = var6.getHeight(Types.WORLD_SURFACE, var14, var13);
                    var33[var15] = var16;
                    if (var16 <= var11) {
                        var32[var15] = -15723752;
                    } else {
                        var12.set(var9 + var14, var16, var10 + var13);
                        BlockState var17 = var6.getBlockState(var12);
                        if (var17.isAir()) {
                            var32[var15] = -15723752;
                        } else {
                            MapColor var18 = var17.getMapColor(var6, var12);
                            int var19 = mapColorAbgr(var18);
                            net.minecraft.core.Holder<Biome> var20 = var0.getBiome(var12);
                            if (var29) {
                                Biome var21 = (Biome)var20.value();
                                if (var18 == MapColor.WATER) {
                                    var19 = abgrFromRgb(var21.getWaterColor());
                                } else if (var17.is(BlockTags.LEAVES)) {
                                    var19 = tintAbgr(var19, var21.getFoliageColor());
                                } else if (var18 == MapColor.GRASS || var18 == MapColor.PLANT) {
                                    // Xaero-like uniform grass: plant blocks (short/tall
                                    // grass, ferns, crops...) keep the PLANT map color whose
                                    // zero R/B channels made tinted pixels a darker, different
                                    // green than the grass_block next to them (salt-and-pepper
                                    // noise). Rendering them on the GRASS base color makes
                                    // plains one smooth, monolithic color.
                                    int var22 = var18 == MapColor.PLANT ? mapColorAbgr(MapColor.GRASS) : var19;
                                    var19 = tintAbgr(var22, var21.getGrassColor((double)var12.getX(), (double)var12.getZ()));
                                }
                            }

                            FluidState var22 = var6.getFluidState(var12);
                            if (!var22.isEmpty()) {
                                if (var22.is(net.minecraft.tags.FluidTags.LAVA)) {
                                    // lava emits full light: keep it glowing hot instead of the
                                    // water-style depth darkening that made it look dark red
                                    var19 = lavaColor(var19);
                                } else {
                                    int var23 = 0;

                                    for (BlockPos var24 = var12.immutable(); var23 < 12 && !var6.getFluidState(var24).isEmpty(); var23++) {
                                        var24 = var24.below();
                                    }

                                    var19 = shadeAbgr(var19, Math.max(0.45F, 1.0F - var23 * 0.07F));
                                }
                            }

                            var32[var15] = var19;
                        }
                    }
                }
            }

            MapTileCache.Entry var34 = new MapTileCache.Entry(var32, var33, false, false);
            if (var3) {
                shadeEntry(var0, var1, var2, var34);
            }

            if (var3 && var31) {
                recordToStore(var0, var1, var2, var4, var34);
            }

            return var34;
        }
    }

    /** Persists a chunk: hillshaded full-bright colors, surface biome palette, discovery sets. */
    private static void recordToStore(
        ClientLevel var0, int var1, int var2, long var3, MapTileCache.Entry var5
    ) {
        java.util.Set<String> var7 = new java.util.HashSet<>();
        java.util.Set<String> var8 = new java.util.HashSet<>();
        MutableBlockPos var9 = new MutableBlockPos();
        java.util.List<String> var10 = new java.util.ArrayList<>();
        java.util.Map<String, Byte> var11 = new java.util.HashMap<>();
        byte[] var12 = new byte[256];

        try {
            for (int var13 = 0; var13 < 256; var13++) {
                if (var5.colors[var13] != -15723752) {
                    // the top surface block itself (heights hold its Y)
                    var9.set((var1 << 4) + (var13 & 15), Math.max(var5.heights[var13], var0.getMinBuildHeight()), (var2 << 4) + (var13 >> 4));
                    BlockState var14 = var0.getBlockState(var9);
                    var8.add(BuiltInRegistries.BLOCK.getKey(var14.getBlock()).toString());
                    String var15 = var0.getBiome(var9)
                        .unwrapKey()
                        .map(var0x -> var0x.location().toString())
                        .orElse(null);
                    if (var15 != null) {
                        var7.add(var15);
                        Byte var16 = var11.get(var15);
                        if (var16 == null) {
                            if (var10.size() < 255) {
                                var16 = (byte)var10.size();
                                var10.add(var15);
                                var11.put(var15, var16);
                            } else {
                                var16 = (byte)254;
                            }
                        }

                        var12[var13] = var16;
                    } else {
                        var12[var13] = -1;
                    }
                } else {
                    var12[var13] = -1;
                }
            }
        } catch (Exception var17) {
            var7.clear();
            var8.clear();
        }

        MapStore.chunkExplored(
            var0.dimension(), var3, var5.colors, var5.heights, var10.toArray(new String[0]), var12, var7, var8
        );
    }

    private static int[] defaultHeights() {
        int[] var0 = new int[256];
        java.util.Arrays.fill(var0, Integer.MIN_VALUE);
        return var0;
    }

    /**
     * Hillshade: darkens slopes facing away, brightens slopes facing the light.
     * Deterministic per chunk: samples NEVER cross the chunk border, so a chunk
     * shades identically no matter which neighbor was scanned first. The old
     * cross-chunk reads depended on scan order (border columns shaded before the
     * neighbor existed used a different fallback) and burned permanent "chunk
     * lines" into both maps.
     */
    private static void shadeEntry(ClientLevel var0, int var1, int var2, MapTileCache.Entry var3) {
        if (var3.shaded) {
            return;
        }
        boolean var4 = flag(UnboundConfig.TERRAIN_SHADING, true);
        if (var4) {
            for (int var9 = 0; var9 < 16; var9++) {
                for (int var10 = 0; var10 < 16; var10++) {
                    int var11 = var9 << 4 | var10;
                    if (var3.colors[var11] != -15723752) {
                        int var12 = var3.heights[var11];
                        int var13 = heightAt(var3, var10 - 1, var9 - 1, var12);
                        float var14 = Mth.clamp(1.0F + (float)(var12 - var13) * 0.06F, 0.82F, 1.18F);
                        int var15 = heightAt(var3, var10 + 1, var9, var12);
                        int var16 = heightAt(var3, var10, var9 + 1, var12);
                        if (Math.abs(var12 - var15) >= 4 || Math.abs(var12 - var16) >= 4) {
                            var14 *= 0.94F;
                        }

                        if (var14 != 1.0F) {
                            var3.colors[var11] = shadeAbgr(var3.colors[var11], var14);
                        }
                    }
                }
            }
        }

        var3.shaded = true;
    }

    /** Height of a local column (lx, lz clamped to 0..15 - never crosses the chunk). */
    private static int heightAt(MapTileCache.Entry var0, int var3, int var4, int var5) {
        int var6 = Mth.clamp(var3, 0, 15);
        int var7 = Mth.clamp(var4, 0, 15);
        int var8 = var0.heights[var7 << 4 | var6];
        return var8 != Integer.MIN_VALUE ? var8 : var5;
    }

    public static int getSliceColor(ClientLevel var0, int var1, int var2, int var3) {
        int var4 = var1 >> 4;

        int var5 = var2 >> 4;
        long var6 = ChunkPos.asLong(var4, var5);
        Map<Long, MapTileCache.Entry> var8 = SLICES.computeIfAbsent(var3, var0x -> new LinkedHashMap<>(512, 0.75F, true));
        MapTileCache.Entry var9 = var8.get(var6);
        if (var9 == null) {
            var9 = computeSliceChunk(var0, var4, var5, var3);
            if (var9 == null) {
                return -15723752;
            }

            storeWithLimit(var8, var6, var9, 8000);
        }

        return enhance(var9.colors[(var5 & 15) << 4 | var1 & 15]);
    }

    public static int sliceTopY(ClientLevel var0, int var1, int var2, int var3) {
        ChunkAccess var4 = var0.getChunk(var1 >> 4, var2 >> 4, ChunkStatus.FULL, false);
        if (var4 == null) {
            return var3;
        } else {
            int var5 = sliceTop(var4, var1 & 15, var2 & 15, bucketCenterY(sliceBucket), var0.getMinBuildHeight(), var0.getMaxBuildHeight());
            return var5 == Integer.MIN_VALUE ? var3 : var5;
        }
    }

    private static MapTileCache.Entry computeSliceChunk(ClientLevel var0, int var1, int var2, int var3) {
        ChunkAccess var4 = var0.getChunk(var1, var2, ChunkStatus.FULL, false);
        if (var4 == null) {
            // explored-but-unloaded chunk: rebuild the cut-lid view approximately
            // from the persistent store, so cave mode covers the WHOLE explored map
            return storedSliceChunk(var0, var1, var2, var3);
        } else {
            boolean var29 = flag(UnboundConfig.LIGHT_SHADING, true);
            int var5 = bucketCenterY(var3);
            int var6 = sliceRange() * 2;
            int var7 = var0.getMinBuildHeight();
            int var8 = var0.getMaxBuildHeight();
            int var30 = Mth.clamp(var5, var7, var8 - 1);
            int[] var9 = new int[256];
            int[] var10 = new int[256];
            int var11 = var1 << 4;
            int var12 = var2 << 4;
            MutableBlockPos var13 = new MutableBlockPos();

            for (int var14 = 0; var14 < 16; var14++) {
                for (int var15 = 0; var15 < 16; var15++) {
                    int var16 = var15 << 4 | var14;
                    // Xaero-readable cut-lid: what matters is whether the column is
                    // SOLID at the view height or has a CAVITY there. The old code
                    // always rendered the first block below the cut, so solid rock
                    // and tunnel floors were the same stone grey and caves were an
                    // unreadable mush - now rock is bright and cavities are dark
                    // shapes (with torch-lit floors glowing), like Xaero's caves.
                    var13.set(var11 + var14, var30, var12 + var15);
                    BlockState var31 = var4.getBlockState(var13);
                    if (!var31.isAir()) {
                        // solid at the cut level: render the block itself, full-bright
                        var9[var16] = var30;
                        int var32 = mapColorAbgr(var31.getMapColor(var4, var13));
                        if (!var31.getFluidState().isEmpty()) {
                            if (var31.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
                                var32 = lavaColor(var32);
                            } else {
                                var32 = shadeAbgr(var32, 0.85F);
                            }
                        }

                        var10[var16] = var32;
                    } else {
                        // cavity at the cut level: find the floor below
                        int var33 = sliceTop(var4, var14, var15, var5, var7, var8);
                        var9[var16] = var33;
                        if (var33 == Integer.MIN_VALUE) {
                            var10[var16] = -15723752;
                        } else {
                            var13.set(var11 + var14, var33, var12 + var15);
                            BlockState var34 = var4.getBlockState(var13);
                            int var35 = mapColorAbgr(var34.getMapColor(var4, var13));
                            if (!var34.getFluidState().isEmpty()) {
                                if (var34.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
                                    var35 = lavaColor(var35);
                                } else {
                                    int var36 = 0;

                                    for (BlockPos var37 = var13.immutable(); var36 < 12 && !var4.getFluidState(var37).isEmpty(); var36++) {
                                        var37 = var37.below();
                                    }

                                    var35 = shadeAbgr(var35, Math.max(0.4F, 1.0F - var36 * 0.08F));
                                }

                                // water/lava floors read like the surface map
                                var10[var16] = shadeAbgr(var35, sliceDepthDim(var33, var5, var6));
                            } else {
                                // cave floor: dark by default (the tunnel shape reads
                                // against bright rock), torch/sky light brightens it
                                float var38 = 0.35F;
                                if (var29) {
                                    var13.set(var11 + var14, Math.min(var33 + 1, var8 - 1), var12 + var15);
                                    int var39 = var0.getBrightness(LightLayer.SKY, var13);
                                    int var40 = var0.getBrightness(LightLayer.BLOCK, var13);
                                    var38 = Math.max(var38, lightShade(var0, var39, var40));
                                }

                                var10[var16] = shadeAbgr(var35, var38 * sliceDepthDim(var33, var5, var6));
                            }
                        }
                    }
                }
            }

            return new MapTileCache.Entry(var10, var9, true, false);
        }
    }

    /** Depth fade used by the cut-lid view: columns far below the view height get slightly darker. */
    private static float sliceDepthDim(int var0, int var1, int var2) {
        return 1.0F - Math.min((float)Math.abs(var0 - var1) / (float)Math.max(1, var2), 1.0F) * 0.22F;
    }

    /**
     * Approximate cut-lid for an explored-but-unloaded chunk, rebuilt from the
     * persistent store (colors + surface heights, no per-block data), aligned
     * with the live slice look:
     * - columns whose real surface lies BELOW the view height are open cavities
     *   (valleys, ocean floors) - the sky-lit floor shows the surface color;
     * - columns whose terrain rises above the view height are solid at the cut:
     *   neutral stone/deepslate grey, matching the full-bright rock of live chunks.
     */
    private static MapTileCache.Entry storedSliceChunk(ClientLevel var0, int var1, int var2, int var3) {
        if (!MapStore.isEnabled()) {
            return null;
        }

        MapStore.StoredChunk var4 = MapStore.storedChunk(var0.dimension(), ChunkPos.asLong(var1, var2));
        if (var4 == null) {
            return null;
        }

        int var5 = bucketCenterY(var3);
        int var6 = sliceRange() * 2;
        int var11 = mapColorAbgr(var5 < 0 ? MapColor.DEEPSLATE : MapColor.STONE);
        int[] var7 = var4.colors();
        int[] var8 = var4.heights() == null ? defaultHeights() : var4.heights();
        int[] var9 = new int[256];
        int[] var10 = new int[256];

        for (int var12 = 0; var12 < 256; var12++) {
            if (var7[var12] != UNKNOWN && var8[var12] != Integer.MIN_VALUE) {
                int var13 = var8[var12];
                var10[var12] = var13;
                if (var13 >= var5) {
                    // solid at the cut level: unknown rock, same grey as live rock
                    var9[var12] = shadeAbgr(var11, 0.9F);
                } else {
                    // open cavity down to the stored surface: sky-lit floor
                    var9[var12] = shadeAbgr(var7[var12], Math.max(0.45F, sliceDepthDim(var13, var5, var6)));
                }
            } else {
                var9[var12] = UNKNOWN;
                var10[var12] = Integer.MIN_VALUE;
            }
        }

        return new MapTileCache.Entry(var9, var10, true, false);
    }

    /**
     * The "cut lid" scan: everything above {@code cutY} is treated as removed.
     * Returns the first non-air block at or below {@code cutY}, scanning all the
     * way to the world bottom, so cave floors, ravines and lava lakes far below
     * the view height stay visible. Whole air-only sections are skipped.
     */
    private static int sliceTop(ChunkAccess var0, int var1, int var2, int var3, int var4, int var5) {
        LevelChunkSection[] var6 = var0.getSections();
        if (var6 == null || var6.length == 0) {
            return Integer.MIN_VALUE;
        } else {
            int var7 = Math.min(var3, var5 - 1);
            int var8 = Mth.clamp((var7 - var4) >> 4, 0, var6.length - 1);
            MutableBlockPos var9 = new MutableBlockPos();

            for (int var10 = var8; var10 >= 0; var10--) {
                LevelChunkSection var11 = var6[var10];
                if (var11 != null && !var11.hasOnlyAir()) {
                    int var12 = var4 + (var10 << 4);

                    for (int var13 = Math.min(var7, var12 + 15); var13 >= var12; var13--) {
                        var9.set(var1, var13, var2);
                        if (!var0.getBlockState(var9).isAir()) {
                            return var13;
                        }
                    }
                }
            }

            return Integer.MIN_VALUE;
        }
    }

    private static void storeWithLimit(Map<Long, MapTileCache.Entry> var0, long var1, MapTileCache.Entry var3, int var4) {
        if (var0.size() >= var4) {
            int var5 = var4 / 4;
            Iterator<Map.Entry<Long, MapTileCache.Entry>> var6 = var0.entrySet().iterator();

            while (var5-- > 0 && var6.hasNext()) {
                var6.next();
                var6.remove();
            }
        }

        var0.put(var1, var3);
    }

    public static int mapColorAbgr(MapColor var0) {
        int var1 = var0.col & 16777215;
        int var2 = var1 >> 16 & 0xFF;
        int var3 = var1 >> 8 & 0xFF;
        int var4 = var1 & 0xFF;
        return 0xFF000000 | var4 << 16 | var3 << 8 | var2;
    }

    public static int abgrFromRgb(int var0) {
        int var1 = var0 & 16777215;
        int var2 = var1 >> 16 & 0xFF;
        int var3 = var1 >> 8 & 0xFF;
        int var4 = var1 & 0xFF;
        return 0xFF000000 | var4 << 16 | var3 << 8 | var2;
    }

    /** Multiplies an ABGR color by an RGB tint (used for biome grass/foliage colors). */
    public static int tintAbgr(int var0, int var1) {
        int var2 = (var1 >> 16 & 0xFF) * (var0 & 0xFF) / 255;
        int var3 = (var1 >> 8 & 0xFF) * (var0 >>> 8 & 0xFF) / 255;
        int var4 = (var1 & 0xFF) * (var0 >>> 16 & 0xFF) / 255;
        return var0 & 0xFF000000 | var4 << 16 | var3 << 8 | var2;
    }

    public static int shadeAbgr(int var0, float var1) {
        int var2 = var0 & 0xFF000000;
        int var3 = Math.min(255, (int)((var0 & 0xFF) * var1));
        int var4 = Math.min(255, (int)((var0 >>> 8 & 0xFF) * var1));
        int var5 = Math.min(255, (int)((var0 >>> 16 & 0xFF) * var1));
        return var2 | var5 << 16 | var4 << 8 | var3;
    }

    public static final class Entry {
        public final int[] colors;
        public final int[] heights;
        public boolean shaded;
        public final boolean stored;

        Entry(int[] var1, int[] var2) {
            this.colors = var1;
            this.heights = var2;
            this.shaded = true;
            this.stored = false;
        }

        Entry(int[] var1, int[] var2, boolean var3, boolean var4) {
            this.colors = var1;
            this.heights = var2;
            this.shaded = var3;
            this.stored = var4;
        }
    }
}
