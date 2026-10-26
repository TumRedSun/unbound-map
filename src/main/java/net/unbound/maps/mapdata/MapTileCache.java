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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.DropExperienceBlock;
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
 * Slice (cave) tiles: Xaero-style "cut lid" view at an EXACT height. Everything
 * above the cut plane is treated as removed; the block at the cut renders bright
 * (the cross-section wall), the first non-air block below an open cut (a cave
 * floor, a ravine bottom, lava) is rendered at any depth, lit by the light
 * inside the cavity itself, with 16 perceptual light gradations.
 *
 * Final tiles are recorded in {@link MapStore} so explored-but-unloaded chunks
 * keep rendering from disk.
 */
public final class MapTileCache {
    public static final int UNKNOWN = -15723752;
    private static final int MAX_ENTRIES = 40000;
    private static final int MAX_SLICE_ENTRIES = 8000;
    private static final int MAX_SLICE_BUCKETS = 8;
    private static final Map<Long, MapTileCache.Entry> CHUNKS = new LinkedHashMap<>(4096, 0.75F, true);
    /** Slice (cave) tiles per exact cut Y; access-ordered so old heights can be trimmed. */
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

    /** World map active slice key (the exact cut Y + 512). */
    public static int caveBucket() {
        return sliceBucket;
    }

    /** True while the MINIMAP renders the cave (slice) view - always automatic. */
    public static boolean isMiniCave() {
        return miniCave;
    }

    /** Minimap active slice key (the exact cut Y + 512). */
    public static int miniBucket() {
        return miniBucket;
    }

    /**
     * Re-syncs the view flags from {@link CaveState} and invalidates caches
     * when the view mode or the slice height changed. Cheap to call every tick.
     */
    public static void onCaveViewChanged() {
        boolean newCave = CaveState.isWorldCave();
        int newBucket = bucketOf(CaveState.worldSliceY());
        if (newCave != cave || newBucket != sliceBucket) {
            cave = newCave;
            sliceBucket = newBucket;
            // the per-height tile caches are KEPT: moving up or down a cave reuses
            // the slices computed for heights you already visited (clearing them
            // made the view go dark for seconds on every height change)
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

    /**
     * Slice view height key = the EXACT cut Y + 512 (no 8-block quantization).
     * The cut plane must never sit below the player's feet: for 7 of every 8
     * heights the old bucket-bottom cut landed INSIDE the floor under the
     * player (their own gallery rendered as solid bright rock), and the later
     * "scan the whole 8-block band" band hack made one continuous cavern
     * flicker between bright rock and dark cavity following its 3D ceiling
     * relief - the cave shattered into "broken blocks painting whole chunks".
     * A single plane at the exact Y is what Xaero renders and reads cleanly.
     */
    public static int bucketOf(int var0) {
        return var0 + 512;
    }

    /**
     * Cut height of a slice bucket - the EXACT cut Y (the key stores Y + 512,
     * see {@link #bucketOf}).
     */
    public static int bucketCenterY(int var0) {
        return var0 - 512;
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
                    // legacy (pre-1.11.0) region data is repaired by MapStore.loadRegion
                    // right when it is read, so stored colors are safe to render as-is.
                    // Stored light nibbles (v2 format) were captured with the old buggy
                    // sampling and are intentionally ignored: surface tiles are full-bright
                    return new MapTileCache.Entry(
                        var7.colors(), var7.heights() == null ? defaultHeights() : var7.heights(), true, true
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
                                    // Xaero-like readable foliage: tinting the PLANT map
                                    // color (zero R/B channels) produced a near-black
                                    // (0,90,0) that made every forest look burned. The
                                    // GRASS base keeps R/B, the biome foliage tint decides
                                    // the hue, and a slight darkening keeps canopies
                                    // distinct from the grass floor without going black.
                                    var19 = shadeAbgr(tintAbgr(mapColorAbgr(MapColor.GRASS), var21.getFoliageColor()), 0.88F);
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

            if (var3) {
                // the neighbors may have been shaded while this chunk did not exist
                // yet (their border columns fell back to their own height). Re-shade
                // them once so slopes continue smoothly across the border - the old
                // clamped sampling burned permanent "chunk lines" into both maps.
                markNeighborsForReshade(var1, var2);
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
     * Deterministic AND seamless: border columns sample the REAL height of the
     * adjacent chunk (from its cached tile or, if it is loaded but not yet
     * computed, straight from its heightmap). Heights never depend on the scan
     * order, so a chunk shades identically no matter when it is drawn; the old
     * clamped-to-own-border sampling left every border column unshaded and
     * burned visible chunk grid lines into hillsides.
     */
    private static void shadeEntry(ClientLevel var0, int var1, int var2, MapTileCache.Entry var3) {
        if (var3.shaded) {
            return;
        }
        boolean var4 = flag(UnboundConfig.TERRAIN_SHADING, true);
        if (var4) {
            if (var3.base == null) {
                var3.base = var3.colors.clone();
            }

            for (int var9 = 0; var9 < 16; var9++) {
                for (int var10 = 0; var10 < 16; var10++) {
                    int var11 = var9 << 4 | var10;
                    if (var3.base[var11] != -15723752) {
                        int var12 = var3.heights[var11];
                        int var13 = crossBorderHeight(var0, var1, var2, var10 - 1, var9 - 1, var12, var3);
                        float var14 = Mth.clamp(1.0F + (float)(var12 - var13) * 0.06F, 0.82F, 1.18F);
                        int var15 = crossBorderHeight(var0, var1, var2, var10 + 1, var9, var12, var3);
                        int var16 = crossBorderHeight(var0, var1, var2, var10, var9 + 1, var12, var3);
                        if (Math.abs(var12 - var15) >= 4 || Math.abs(var12 - var16) >= 4) {
                            var14 *= 0.94F;
                        }

                        if (var14 != 1.0F) {
                            var3.colors[var11] = shadeAbgr(var3.base[var11], var14);
                        } else {
                            var3.colors[var11] = var3.base[var11];
                        }
                    } else {
                        var3.colors[var11] = -15723752;
                    }
                }
            }
        }

        var3.shaded = true;
    }

    /** Marks the four direct neighbors for one re-shade now that this chunk exists. */
    private static void markNeighborsForReshade(int var0, int var1) {
        int[][] var2 = new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

        for (int[] var6 : var2) {
            int var7 = var0 + var6[0];
            int var8 = var1 + var6[1];
            long var9 = ChunkPos.asLong(var7, var8);
            MapTileCache.Entry var10 = CHUNKS.get(var9);
            // stored entries keep their on-disk (already shaded) colors - re-running
            // the shade pass on them would darken them a second time
            if (var10 != null && var10.shaded && !var10.stored && var10.base != null) {
                var10.shaded = false;
                System.arraycopy(var10.base, 0, var10.colors, 0, 256);
                MinimapTexture.markChunkDirty(var9);
                WorldMapCells.markChunkDirty(var9);
            }
        }
    }

    /**
     * Height of a local column; (lx, lz) may leave 0..15 by ONE block into the
     * direct neighbor chunk. Falls back to the chunk's cached tile, then to the
     * live neighbor heightmap, then to the own height (never triggers neighbor
     * tile computation, so shading stays cheap and non-recursive).
     */
    private static int crossBorderHeight(ClientLevel var0, int var1, int var2, int var3, int var4, int var5, MapTileCache.Entry var6) {
        int var7 = Mth.clamp(var3, -1, 16);
        int var8 = Mth.clamp(var4, -1, 16);
        if (var7 >= 0 && var7 < 16 && var8 >= 0 && var8 < 16) {
            int var9 = var6.heights[var8 << 4 | var7];
            return var9 != Integer.MIN_VALUE ? var9 : var5;
        }

        int var10 = var1 + (var7 >> 4);
        int var11 = var2 + (var8 >> 4);
        int var12 = var7 & 15;
        int var13 = var8 & 15;
        MapTileCache.Entry var14 = CHUNKS.get(ChunkPos.asLong(var10, var11));
        if (var14 != null) {
            int var15 = var14.heights[var13 << 4 | var12];
            return var15 != Integer.MIN_VALUE ? var15 : var5;
        }

        ChunkAccess var16 = var0.getChunk(var10, var11, ChunkStatus.FULL, false);
        if (var16 != null) {
            int var17 = var16.getHeight(Types.WORLD_SURFACE, var12, var13);
            return var17 != Integer.MIN_VALUE ? var17 : var5;
        }

        return var5;
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
            // Xaero-style single-plane cut at the EXACT view height: everything
            // above the cut is removed. The old 8-block band scan made one
            // continuous cavern flicker between bright rock and dark cavity
            // following its 3D ceiling relief - the cave shattered into "broken
            // blocks painting whole chunks" (the band was solid where the
            // ceiling was high and a cavity where it was low). A single plane
            // renders a clean cross-section: solid-at-cut columns show the cut
            // block bright (the wall the lid was cut through), air-at-cut
            // columns show the lit floor below.
            int[] var9 = new int[256];
            int[] var10 = new int[256];
            int var11 = var1 << 4;
            int var12 = var2 << 4;
            MutableBlockPos var13 = new MutableBlockPos();
            int var30 = Mth.clamp(var5, var7, var8 - 1);

            for (int var14 = 0; var14 < 16; var14++) {
                for (int var15 = 0; var15 < 16; var15++) {
                    int var16 = var15 << 4 | var14;
                    var13.set(var11 + var14, var30, var12 + var15);
                    BlockState var17 = var4.getBlockState(var13);
                    if (!var17.isAir()) {
                        // solid at the cut: the cross-section wall, rendered at
                        // the cut block itself, bright (uniform rock tone for
                        // the stone family - raw deepslate/sculk/tuff map
                        // colors are near-black and read as holes)
                        var9[var16] = var30;
                        MapColor var41 = var17.getMapColor(var4, var13);
                        int var36 = mapColorAbgr(var41);
                        if (!var17.getFluidState().isEmpty()) {
                            if (var17.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
                                var36 = lavaColor(var36);
                            } else {
                                var36 = shadeAbgr(var36, 0.85F);
                            }
                        } else {
                            var36 = sliceBlockColor(var17, var41, var0, var30);
                        }

                        var10[var16] = var36;
                    } else {
                        // air at the cut: open cavity - render the floor below,
                        // scanning all the way down (ravines, lava lakes and
                        // galleries far below the cut stay visible)
                        int var34 = sliceTop(var4, var14, var15, var30, var7, var8);
                        var9[var16] = var34;
                        if (var34 == Integer.MIN_VALUE) {
                            var10[var16] = -15723752;
                        } else {
                            var13.set(var11 + var14, var34, var12 + var15);
                            BlockState var35 = var4.getBlockState(var13);
                            int var36 = sliceBlockColor(var35, var35.getMapColor(var4, var13), var0, var34);
                            if (!var35.getFluidState().isEmpty()) {
                                if (var35.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
                                    var36 = lavaColor(mapColorAbgr(var35.getMapColor(var4, var13)));
                                } else {
                                    int var37 = 0;

                                    for (BlockPos var38 = var13.immutable(); var37 < 12 && !var4.getFluidState(var38).isEmpty(); var37++) {
                                        var38 = var38.below();
                                    }

                                    var36 = shadeAbgr(var36, Math.max(0.4F, 1.0F - var37 * 0.08F));
                                }

                                // water/lava floors read like the surface map
                                var10[var16] = shadeAbgr(var36, sliceDepthDim(var34, var5, var6));
                            } else {
                                // cave floor: dark by default (the tunnel shape reads
                                // against bright rock), torch/sky light brightens it -
                                // but never past 0.72: a fully lit stone floor used to
                                // reach the very tone of the surrounding rock, so the
                                // tunnel shape dissolved into the grey around it and
                                // only the colored (dirt/ore) floors stayed visible as
                                // random bright rectangles
                                float var39 = 0.35F;
                                if (var29) {
                                    var13.set(var11 + var14, Math.min(var34 + 1, var8 - 1), var12 + var15);
                                    int var40 = var0.getBrightness(LightLayer.SKY, var13);
                                    int var42 = var0.getBrightness(LightLayer.BLOCK, var13);
                                    var39 = Math.max(var39, Math.min(0.72F, lightShade(var0, var40, var42)));
                                }

                                var10[var16] = shadeAbgr(var36, var39 * sliceDepthDim(var34, var5, var6));
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
     * Cut-lid rock classification: stone-family blocks (stone, deepslate, tuff,
     * gravel - everything with the stone/deepslate map color) render as ONE
     * uniform, bright tone per depth class, so tunnels and galleries read as
     * clean dark shapes against solid ground. Ores keep their sparkle, fluids
     * and everything else (dirt patches, moss, sculk) keep their identity. The
     * raw map colors made every underground block a different shade of dark
     * grey - the "cave mode is an unreadable mess" report in a nutshell.
     */
    private static int sliceBlockColor(BlockState var0, MapColor var1, ClientLevel var2, int var3) {
        if (var1 != MapColor.STONE && var1 != MapColor.DEEPSLATE) {
            return mapColorAbgr(var1);
        }

        if (var0.getBlock() instanceof DropExperienceBlock) {
            return mapColorAbgr(var1);
        }

        return rockTone(var2, var3);
    }

    /** Uniform bright rock tone per dimension/depth class (ABGR). */
    private static int rockTone(ClientLevel var0, int var1) {
        if (var0.dimension() == Level.NETHER) {
            return 0xFF3A303F;
        }

        if (var0.dimension() == Level.END) {
            return 0xFFB8D2D5;
        }

        return var1 < 0 ? 0xFF74747A : 0xFFA2A2A4;
    }

    /**
     * Approximate cut-lid for an explored-but-unloaded chunk, rebuilt from the
     * persistent store (colors + surface heights, no per-block data), aligned
     * with the live single-plane slice:
     * - columns whose real surface lies BELOW the cut height are open at the
     *   cut (valleys, ocean floors, caverns the store cannot see) - the floor
     *   shows the stored surface color, capped dark like the live cavity path;
     * - columns whose terrain rises to or above the cut height are solid at
     *   the cut: neutral stone/deepslate grey, matching the full-bright rock
     *   of live chunks.
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
        int var11 = rockTone(var0, var5);
        int[] var7 = var4.colors();
        int[] var8 = var4.heights() == null ? defaultHeights() : var4.heights();
        int[] var9 = new int[256];
        int[] var10 = new int[256];

        for (int var12 = 0; var12 < 256; var12++) {
            if (var7[var12] != UNKNOWN && var8[var12] != Integer.MIN_VALUE) {
                int var13 = var8[var12];
                var10[var12] = var13;
                if (var13 >= var5) {
                    // terrain reaches the cut plane: solid at the cut, same grey as live rock
                    var9[var12] = shadeAbgr(var11, 0.9F);
                } else {
                    // open at the cut down to the stored surface: cavity floor,
                    // capped dark exactly like the live slice path - the old 0.45+
                    // "sky-lit" floors mismatched the live dark floors and showed up
                    // as random bright rectangles scattered over the stored map
                    var9[var12] = shadeAbgr(var7[var12], Math.min(0.72F, Math.max(0.4F, sliceDepthDim(var13, var5, var6))));
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
        /** Unshaded colors (lazily cloned on first shade) so a re-shade never darkens twice. */
        public int[] base;

        Entry(int[] var1, int[] var2) {
            this.colors = var1;
            this.heights = var2;
            this.shaded = true;
            this.stored = false;
            this.base = null;
        }

        Entry(int[] var1, int[] var2, boolean var3, boolean var4) {
            this.colors = var1;
            this.heights = var2;
            this.shaded = var3;
            this.stored = var4;
            this.base = null;
        }
    }
}
