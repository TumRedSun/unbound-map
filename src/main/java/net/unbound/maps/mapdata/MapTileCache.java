package net.unbound.maps.mapdata;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
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
import net.minecraft.world.level.material.MapColor;
import net.unbound.maps.UnboundConfig;

/**
 * Computes and caches the 16x16 color tiles for both the minimap and the world map.
 *
 * Surface tiles: WORLD_SURFACE heightmap + block map color, with optional
 * biome tinting (grass/foliage/water), light shading and cross-chunk hillshading.
 * Slice (cave) tiles: the topmost non-air block in the player's altitude bucket,
 * shaded by exposure, depth and block light.
 *
 * Final tiles are recorded in {@link MapStore} so explored-but-unloaded chunks
 * keep rendering from disk.
 */
public final class MapTileCache {
    public static final int UNKNOWN = -15723752;
    public static final float SLICE_SHADE = 0.88F;
    private static final float SLICE_EXPOSED = 1.12F;
    private static final float SLICE_BURIED = 0.66F;
    private static final int MAX_ENTRIES = 40000;
    private static final int MAX_SLICE_ENTRIES = 8000;
    private static final int SLICE_BUCKET_BITS = 3;
    private static final Map<Long, MapTileCache.Entry> CHUNKS = new LinkedHashMap<>(4096, 0.75F, true);
    private static final Map<Integer, Map<Long, MapTileCache.Entry>> SLICES = new HashMap<>();
    private static boolean minimapSlice;
    private static boolean worldMapSlice;
    private static int sliceBucket;

    private MapTileCache() {
    }

    /** Cave (slice) view of the HUD minimap — independent from the world map's. */
    public static boolean isMinimapSlice() {
        return minimapSlice;
    }

    /** Cave (slice) view of the world map — independent from the minimap's. */
    public static boolean isWorldMapSlice() {
        return worldMapSlice;
    }

    public static void setMinimapSlice(boolean var0) {
        if (var0 != minimapSlice) {
            minimapSlice = var0;
            MinimapTexture.invalidateAll();
        }
    }

    /** World map cells are keyed by (cave, bucket), so no invalidation is needed here. */
    public static void setWorldMapSlice(boolean var0) {
        worldMapSlice = var0;
    }

    public static boolean toggleMinimapSlice() {
        setMinimapSlice(!minimapSlice);
        return minimapSlice;
    }

    public static boolean toggleWorldMapSlice() {
        setWorldMapSlice(!worldMapSlice);
        return worldMapSlice;
    }

    public static int caveBucket() {
        return sliceBucket;
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

    public static boolean updateCaveBucket(int var0) {
        int var1 = bucketOf(var0);
        if (var1 == sliceBucket) {
            return false;
        } else {
            sliceBucket = var1;
            SLICES.clear();
            if (minimapSlice) {
                MinimapTexture.invalidateAll();
            }

            if (worldMapSlice) {
                WorldMapCells.clearAll();
            }

            return true;
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

        return var7.colors[(var2 & 15) << 4 | var1 & 15];
    }

    public static int surfaceHeight(ClientLevel var0, int var1, int var2) {
        ChunkAccess var3 = var0.getChunk(var1 >> 4, var2 >> 4, ChunkStatus.FULL, false);
        return var3 == null ? Integer.MIN_VALUE : var3.getHeight(Types.WORLD_SURFACE, var1 & 15, var2 & 15);
    }

    /**
     * Computes a chunk entry from live world data (biome tint + water tint + light),
     * falling back to the persistent store when the chunk is unloaded.
     * When {@code allowShade} is true the hillshade pass runs immediately;
     * neighbors needed by it are pre-computed raw (one level of recursion at most).
     */
    private static MapTileCache.Entry computeEntry(ClientLevel var0, int var1, int var2, boolean var3) {
        long var4 = ChunkPos.asLong(var1, var2);
        ChunkAccess var6 = var0.getChunk(var1, var2, ChunkStatus.FULL, false);
        if (var6 == null) {
            if (MapStore.isEnabled()) {
                int[] var7 = MapStore.storedColors(var0.dimension(), var4);
                if (var7 != null) {
                    int[] var8 = MapStore.storedHeights(var0.dimension(), var4);
                    return new MapTileCache.Entry(var7, var8 == null ? defaultHeights() : var8, true, true);
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
                                int var23 = 0;

                                for (BlockPos var24 = var12.immutable(); var23 < 12 && !var6.getFluidState(var24).isEmpty(); var23++) {
                                    var24 = var24.below();
                                }

                                var19 = shadeAbgr(var19, Math.max(0.45F, 1.0F - var23 * 0.07F));
                            }

                            if (var30) {
                                int var25 = var0.getBrightness(LightLayer.SKY, var12);
                                int var26 = var0.getBrightness(LightLayer.BLOCK, var12);
                                int var27 = Math.max(var25, var26);
                                var19 = shadeAbgr(var19, 0.35F + 0.65F * (float)var27 / 15.0F);
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

            if (var31) {
                recordToStore(var0, var1, var2, var4, var34);
            }

            return var34;
        }
    }

    private static void recordToStore(ClientLevel var0, int var1, int var2, long var3, MapTileCache.Entry var5) {
        java.util.Set<String> var6 = new java.util.HashSet<>();
        java.util.Set<String> var7 = new java.util.HashSet<>();
        MutableBlockPos var8 = new MutableBlockPos();

        try {
            for (int var9 = 0; var9 < 16; var9++) {
                for (int var10 = 0; var10 < 16; var10++) {
                    int var11 = var9 << 4 | var10;
                    if (var5.colors[var11] != -15723752) {
                        var8.set((var1 << 4) + var10, Math.max(var5.heights[var11] - 1, var0.getMinBuildHeight()), (var2 << 4) + var9);
                        BlockState var12 = var0.getBlockState(var8);
                        var0.getBiome(var8)
                            .unwrapKey()
                            .ifPresent(var0x -> var6.add(var0x.location().toString()));
                        var7.add(BuiltInRegistries.BLOCK.getKey(var12.getBlock()).toString());
                    }
                }
            }
        } catch (Exception var13) {
            var6.clear();
            var7.clear();
        }

        MapStore.chunkExplored(var0.dimension(), var3, var5.colors, var5.heights, var6, var7);
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

        return var9.colors[(var2 & 15) << 4 | var1 & 15];
    }

    public static int sliceTopY(ClientLevel var0, int var1, int var2, int var3) {
        ChunkAccess var4 = var0.getChunk(var1 >> 4, var2 >> 4, ChunkStatus.FULL, false);
        if (var4 == null) {
            return var3;
        } else {
            int var5 = sliceTop(var4, var1 & 15, var2 & 15, var3 + sliceRange(), var3 - sliceRange(), var0.getMinBuildHeight(), var0.getMaxBuildHeight());
            return var5 == Integer.MIN_VALUE ? var3 : var5;
        }
    }

    private static MapTileCache.Entry computeSliceChunk(ClientLevel var0, int var1, int var2, int var3) {
        ChunkAccess var4 = var0.getChunk(var1, var2, ChunkStatus.FULL, false);
        if (var4 == null) {
            return null;
        } else {
            boolean var29 = flag(UnboundConfig.LIGHT_SHADING, true);
            int var5 = bucketCenterY(var3);
            int var6 = sliceRange();
            int var7 = var5 + var6;
            int var8 = var5 - var6;
            int[] var9 = new int[256];
            int[] var10 = new int[256];
            int var11 = var1 << 4;
            int var12 = var2 << 4;
            int var13 = var0.getMaxBuildHeight();
            MutableBlockPos var14 = new MutableBlockPos();

            for (int var15 = 0; var15 < 16; var15++) {
                for (int var16 = 0; var16 < 16; var16++) {
                    int var17 = var15 << 4 | var16;
                    int var18 = sliceTop(var4, var16, var15, var7, var8, var0.getMinBuildHeight(), var13);
                    var9[var17] = var18;
                    if (var18 == Integer.MIN_VALUE) {
                        var10[var17] = -15723752;
                    } else {
                        var14.set(var11 + var16, var18, var12 + var15);
                        BlockState var19 = var4.getBlockState(var14);
                        int var20 = mapColorAbgr(var19.getMapColor(var4, var14));
                        if (!var19.getFluidState().isEmpty()) {
                            int var21 = 0;

                            for (BlockPos var22 = var14.immutable(); var21 < 12 && !var4.getFluidState(var22).isEmpty(); var21++) {
                                var22 = var22.below();
                            }

                            var20 = shadeAbgr(var20, Math.max(0.4F, 1.0F - var21 * 0.08F));
                        } else {
                            BlockPos var24 = var14.above();
                            boolean var25 = var24.getY() < var13 && var4.getBlockState(var24).isAir();
                            var20 = shadeAbgr(var20, var25 ? 1.12F : 0.66F);
                        }

                        var20 = shadeAbgr(var20, 0.88F);
                        if (var29) {
                            int var26 = Math.max(
                                var0.getBrightness(LightLayer.SKY, var14), var0.getBrightness(LightLayer.BLOCK, var14)
                            );
                            var20 = shadeAbgr(var20, Math.max(0.3F, 0.35F + 0.65F * (float)var26 / 15.0F));
                        }

                        float var27 = 1.0F - Math.min((float)Math.abs(var18 - var5) / ((float)var6 * 1.6F), 1.0F) * 0.25F;
                        var10[var17] = shadeAbgr(var20, var27);
                    }
                }
            }

            return new MapTileCache.Entry(var10, var9, true, false);
        }
    }

    private static int sliceTop(ChunkAccess var0, int var1, int var2, int var3, int var4, int var5, int var6) {
        int var7 = Math.min(var3, var6 - 1);
        int var8 = Math.max(var4, var5);
        MutableBlockPos var9 = new MutableBlockPos();

        for (int var10 = var7; var10 >= var8; var10--) {
            var9.set(var1, var10, var2);
            BlockState var11 = var0.getBlockState(var9);
            if (!var11.isAir()) {
                return var10;
            }
        }

        return Integer.MIN_VALUE;
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
