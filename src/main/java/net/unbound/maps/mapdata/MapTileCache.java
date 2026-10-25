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
    private static final Map<Long, MapTileCache.Entry> CHUNKS = new LinkedHashMap<>(4096, 0.75F, true);
    private static final Map<Integer, Map<Long, MapTileCache.Entry>> SLICES = new HashMap<>();
    private static boolean cave;
    private static int sliceBucket;
    /** 16 perceptual light gradations: caves and torch-lit areas get many distinguishable steps. */
    private static final float[] LIGHT_CURVE = {
        0.10F, 0.13F, 0.16F, 0.19F, 0.22F, 0.25F, 0.29F, 0.33F,
        0.38F, 0.43F, 0.49F, 0.56F, 0.64F, 0.73F, 0.85F, 1.00F
    };
    /**
     * How much of the sky brightness is removed at full night. The dim is applied
     * UNIFORMLY at draw time (RenderSystem shader color), like a gentle evening
     * filter - the map keeps Xaero-level readability, torch/lava glow stays baked
     * into slice (cave) tiles only.
     */
    private static final float NIGHT_DIM_STRENGTH = 0.22F;
    private static boolean nightDim = true;
    /** Global look: gentle gamma lift (brighter mids) + mild saturation boost, applied to every rendered pixel. */
    private static final int[] ENHANCE_LUT = new int[256];
    private static final float ENHANCE_GAMMA = 0.87F;
    private static final float ENHANCE_SATURATION = 1.14F;

    static {
        for (int i = 0; i < 256; i++) {
            ENHANCE_LUT[i] = Math.round(255.0F * (float)Math.pow(i / 255.0, ENHANCE_GAMMA));
        }
    }

    private MapTileCache() {
    }

    /** True while the maps render the cave (slice) view. Driven by {@link CaveState}. */
    public static boolean isCave() {
        return cave;
    }

    /** Active slice bucket (8-block altitude band). */
    public static int caveBucket() {
        return sliceBucket;
    }

    /**
     * Re-syncs the view flags from {@link CaveState} and invalidates caches
     * when the view mode or the slice bucket changed. Cheap to call every tick.
     */
    public static void onCaveViewChanged() {
        boolean newCave = CaveState.isCave();
        if (newCave != cave) {
            cave = newCave;
            MinimapTexture.invalidateAll();
        }

        int bucket = bucketOf(CaveState.sliceY());
        if (bucket != sliceBucket) {
            sliceBucket = bucket;
            SLICES.clear();
            MinimapTexture.invalidateAll();
        }

        nightDim = flag(UnboundConfig.NIGHT_DIM, true);
    }

    /**
     * Uniform day/night dim factor for surface map tiles, applied at draw time via
     * RenderSystem.setShaderColor: 1.0 at day, ~0.78 at full night. Cave mode is
     * exempt (it is lit by block light, not by the sun). The surface colors no
     * longer depend on the time of day, so baked tiles are shared between day and
     * night and never re-bake when the sky darkens.
     */
    public static float nightFactor() {
        if (!nightDim || MapTileCache.cave) {
            return 1.0F;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return 1.0F;
        }
        return 1.0F - NIGHT_DIM_STRENGTH * (float)mc.level.getSkyDarken() / 15.0F;
    }

    /**
     * Xaero-like light for the cave (slice) view only: block light (torches, lava,
     * glowstone) keeps its glow, sky light in the cavity follows the day cycle.
     */
    private static float lightShade(ClientLevel var0, int var1, int var2) {
        float var4 = LIGHT_CURVE[var2 & 15];
        float var5 = LIGHT_CURVE[var1 & 15];
        if (nightDim) {
            var5 *= 1.0F - NIGHT_DIM_STRENGTH * (float)var0.getSkyDarken() / 15.0F;
        }

        return Math.max(0.14F, Math.max(var4, var5));
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

    public static int bucketCenterY(int var0) {
        return (var0 << 3) - 512 + 4;
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

    public static int colorFor(ClientLevel var0, int var1, int var2, boolean var3) {
        return var3 ? getSliceColor(var0, var1, var2, sliceBucket) : getColor(var0, var1, var2);
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
                    return var7 - 1;
                }
            }

            int var8 = surfaceHeight(var0, var1, var2);
            return var8 != Integer.MIN_VALUE ? var8 - 1 : var3;
        }
    }

    public static boolean contains(long var0) {
        return CHUNKS.containsKey(var0);
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
     * depth + hillshade. Day/night is a uniform draw-time dim ({@link #nightFactor()}),
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
                    // stored light nibbles (v2 format) were captured with the old buggy
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
                    int var16 = var6.getHeight(Types.WORLD_SURFACE, var14, var13);
                    var33[var15] = var16;
                    if (var16 <= var11) {
                        var32[var15] = -15723752;
                    } else {
                        var12.set(var9 + var14, var16 - 1, var10 + var13);
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
                                    var19 = tintAbgr(var19, var21.getGrassColor((double)var12.getX(), (double)var12.getZ()));
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
                    var9.set((var1 << 4) + (var13 & 15), Math.max(var5.heights[var13] - 1, var0.getMinBuildHeight()), (var2 << 4) + (var13 >> 4));
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

    /** Cross-chunk hillshade: darkens slopes facing away, brightens slopes facing the light. */
    private static void shadeEntry(ClientLevel var0, int var1, int var2, MapTileCache.Entry var3) {
        if (var3.shaded) {
            return;
        }
        boolean var4 = flag(UnboundConfig.TERRAIN_SHADING, true);
        if (var4) {
            MapTileCache.Entry var5 = neighborEntry(var0, var1 - 1, var2);
            MapTileCache.Entry var6 = neighborEntry(var0, var1 + 1, var2);
            MapTileCache.Entry var7 = neighborEntry(var0, var1, var2 - 1);
            MapTileCache.Entry var8 = neighborEntry(var0, var1, var2 + 1);

            for (int var9 = 0; var9 < 16; var9++) {
                for (int var10 = 0; var10 < 16; var10++) {
                    int var11 = var9 << 4 | var10;
                    if (var3.colors[var11] != -15723752) {
                        int var12 = var3.heights[var11];
                        int var13 = heightAt(var3, var5, var7, var10 - 1, var9 - 1, var12);
                        float var14 = Mth.clamp(1.0F + (float)(var12 - var13) * 0.1F, 0.7F, 1.35F);
                        int var15 = heightAt(var3, var6, null, var10 + 1, var9, var12);
                        int var16 = heightAt(var3, var8, null, var10, var9 + 1, var12);
                        if (Math.abs(var12 - var15) >= 4 || Math.abs(var12 - var16) >= 4) {
                            var14 *= 0.88F;
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

    /** Height of a local column (lx, lz may leave 0..15 by one block into the given neighbor). */
    private static int heightAt(MapTileCache.Entry var0, MapTileCache.Entry var1, MapTileCache.Entry var2, int var3, int var4, int var5) {
        if (var3 >= 0 && var3 < 16 && var4 >= 0 && var4 < 16) {
            int var8 = var0.heights[var4 << 4 | var3];
            return var8 != Integer.MIN_VALUE ? var8 : var5;
        } else if (var1 != null) {
            int var6 = ((var3 % 16) + 16) % 16;
            int var7 = ((var4 % 16) + 16) % 16;
            int var9 = var1.heights[var7 << 4 | var6];
            return var9 != Integer.MIN_VALUE ? var9 : var5;
        } else {
            return var5;
        }
    }

    private static MapTileCache.Entry neighborEntry(ClientLevel var0, int var1, int var2) {
        long var3 = ChunkPos.asLong(var1, var2);
        MapTileCache.Entry var5 = (MapTileCache.Entry)CHUNKS.get(var3);
        if (var5 == null) {
            var5 = computeEntry(var0, var1, var2, false);
            if (var5 != null) {
                storeWithLimit(CHUNKS, var3, var5, 40000);
            }
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
            int[] var9 = new int[256];
            int[] var10 = new int[256];
            int var11 = var1 << 4;
            int var12 = var2 << 4;
            MutableBlockPos var13 = new MutableBlockPos();

            for (int var14 = 0; var14 < 16; var14++) {
                for (int var15 = 0; var15 < 16; var15++) {
                    int var16 = var15 << 4 | var14;
                    int var17 = sliceTop(var4, var14, var15, var5, var7, var8);
                    var9[var16] = var17;
                    if (var17 == Integer.MIN_VALUE) {
                        var10[var16] = -15723752;
                    } else {
                        var13.set(var11 + var14, var17, var12 + var15);
                        BlockState var18 = var4.getBlockState(var13);
                        int var19 = mapColorAbgr(var18.getMapColor(var4, var13));
                        if (!var18.getFluidState().isEmpty()) {
                            if (var18.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
                                var19 = lavaColor(var19);
                            } else {
                                int var20 = 0;

                                for (BlockPos var21 = var13.immutable(); var20 < 12 && !var4.getFluidState(var21).isEmpty(); var20++) {
                                    var21 = var21.below();
                                }

                                var19 = shadeAbgr(var19, Math.max(0.4F, 1.0F - var20 * 0.08F));
                            }
                        }

                        if (var29) {
                            // light inside the cavity itself: torches light the
                            // cave floor, lava pools glow, dark rooms stay dark
                            var13.set(var11 + var14, Math.min(var17 + 1, var8 - 1), var12 + var15);
                            int var22 = var0.getBrightness(LightLayer.SKY, var13);
                            int var23 = var0.getBrightness(LightLayer.BLOCK, var13);
                            var19 = shadeAbgr(var19, lightShade(var0, var22, var23));
                        }

                        var10[var16] = shadeAbgr(var19, sliceDepthDim(var17, var5, var6));
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
     * persistent store (colors + surface heights, no per-block data):
     * - columns whose real surface lies at/below the view height render exactly
     *   like the surface map with the usual depth fade (plains, ocean floors,
     *   valley bottoms);
     * - columns whose terrain rises above the view height are "underground":
     *   they show a dark silhouette of the terrain, like Xaero's unlit cave view.
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
        int[] var7 = var4.colors();
        int[] var8 = var4.heights() == null ? defaultHeights() : var4.heights();
        int[] var9 = new int[256];
        int[] var10 = new int[256];

        for (int var11 = 0; var11 < 256; var11++) {
            if (var7[var11] != UNKNOWN && var8[var11] != Integer.MIN_VALUE) {
                int var12 = var8[var11] - 1;
                var10[var11] = var12;
                if (var12 <= var5) {
                    var9[var11] = shadeAbgr(var7[var11], sliceDepthDim(var12, var5, var6));
                } else {
                    var9[var11] = shadeAbgr(var7[var11], 0.10F);
                }
            } else {
                var9[var11] = UNKNOWN;
                var10[var11] = Integer.MIN_VALUE;
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
