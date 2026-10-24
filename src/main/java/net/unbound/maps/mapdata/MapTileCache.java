package net.unbound.maps.mapdata;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.unbound.maps.UnboundConfig;

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
    private static boolean sliceMode;
    private static int sliceBucket;

    private MapTileCache() {
    }

    public static boolean isCaveMode() {
        return sliceMode;
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

    public static boolean toggleCaveMode() {
        sliceMode = !sliceMode;
        clearAll();
        MinimapTexture.invalidateAll();
        WorldMapCells.clearAll();
        return sliceMode;
    }

    public static boolean updateCaveBucket(int var0) {
        if (!sliceMode) {
            return false;
        } else {
            int var1 = bucketOf(var0);
            if (var1 != sliceBucket) {
                sliceBucket = var1;
                clearAll();
                MinimapTexture.invalidateAll();
                WorldMapCells.clearAll();
                return true;
            } else {
                return false;
            }
        }
    }

    public static int colorFor(ClientLevel var0, int var1, int var2) {
        return sliceMode ? getSliceColor(var0, var1, var2, sliceBucket) : getColor(var0, var1, var2);
    }

    public static int columnY(ClientLevel var0, int var1, int var2, int var3) {
        int var4 = var1 >> 4;
        int var5 = var2 >> 4;
        long var6 = ChunkPos.asLong(var4, var5);
        if (sliceMode) {
            Map<Long, MapTileCache.Entry> var11 = SLICES.get(sliceBucket);
            if (var11 != null) {
                MapTileCache.Entry var13 = var11.get(var6);
                if (var13 != null) {
                    int var10 = var13.heights[(var2 & 15) << 4 | var1 & 15];
                    if (var10 != Integer.MIN_VALUE) {
                        return var10;
                    }
                }
            }

            return sliceTopY(var0, var1, var2, var3);
        } else {
            MapTileCache.Entry var8 = CHUNKS.get(var6);
            if (var8 != null) {
                int var9 = var8.heights[(var2 & 15) << 4 | var1 & 15];
                if (var9 != Integer.MIN_VALUE) {
                    return var9 - 1;
                }
            }

            int var12 = surfaceHeight(var0, var1, var2);
            return var12 != Integer.MIN_VALUE ? var12 - 1 : var3;
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
            var7 = computeChunk(var0, var3, var4);
            if (var7 == null) {
                return -15723752;
            }

            storeWithLimit(CHUNKS, var5, var7, 40000);
            MinimapTexture.markChunkDirty(var5);
            WorldMapCells.markChunkDirty(var5);
        }

        return var7.colors[(var2 & 15) << 4 | var1 & 15];
    }

    public static int surfaceHeight(ClientLevel var0, int var1, int var2) {
        ChunkAccess var3 = var0.getChunk(var1 >> 4, var2 >> 4, ChunkStatus.FULL, false);
        return var3 == null ? Integer.MIN_VALUE : var3.getHeight(Types.WORLD_SURFACE, var1 & 15, var2 & 15);
    }

    private static MapTileCache.Entry computeChunk(ClientLevel var0, int var1, int var2) {
        ChunkAccess var3 = var0.getChunk(var1, var2, ChunkStatus.FULL, false);
        if (var3 == null) {
            return null;
        } else {
            int[] var4 = new int[256];
            int[] var5 = new int[256];
            int var6 = var1 << 4;
            int var7 = var2 << 4;
            int var8 = var0.getMinBuildHeight();

            for (int var9 = 0; var9 < 16; var9++) {
                for (int var10 = 0; var10 < 16; var10++) {
                    int var11 = var9 << 4 | var10;
                    int var12 = var3.getHeight(Types.WORLD_SURFACE, var10, var9);
                    var4[var11] = var12;
                    if (var12 <= var8) {
                        var5[var11] = -15723752;
                    } else {
                        BlockPos var13 = new BlockPos(var6 + var10, var12 - 1, var7 + var9);
                        BlockState var14 = var3.getBlockState(var13);
                        if (var14.isAir()) {
                            var5[var11] = -15723752;
                        } else {
                            int var15 = mapColorAbgr(var14.getMapColor(var3, var13));
                            FluidState var16 = var3.getFluidState(var13);
                            if (!var16.isEmpty()) {
                                int var17 = 0;

                                for (BlockPos var18 = var13; var17 < 12 && !var3.getFluidState(var18).isEmpty(); var17++) {
                                    var18 = var18.below();
                                }

                                var15 = shadeAbgr(var15, Math.max(0.45F, 1.0F - var17 * 0.07F));
                            }

                            var5[var11] = var15;
                        }
                    }
                }
            }

            shadeSurface(var5, var4);
            return new MapTileCache.Entry(var5, var4);
        }
    }

    private static void shadeSurface(int[] var0, int[] var1) {
        for (int var2 = 0; var2 < 16; var2++) {
            for (int var3 = 0; var3 < 16; var3++) {
                int var4 = var2 << 4 | var3;
                if (var0[var4] != -15723752) {
                    int var5 = var1[var4];
                    int var6 = var1[Math.max(var2 - 1, 0) << 4 | Math.max(var3 - 1, 0)];
                    float var7 = Mth.clamp(1.0F + (var5 - var6) * 0.08F, 0.7F, 1.32F);
                    int var8 = var1[Math.min(var2 + 1, 15) << 4 | var3];
                    int var9 = var1[var2 << 4 | Math.min(var3 + 1, 15)];
                    if (Math.abs(var5 - var8) >= 4 || Math.abs(var5 - var9) >= 4) {
                        var7 *= 0.88F;
                    }

                    if (var7 != 1.0F) {
                        var0[var4] = shadeAbgr(var0[var4], var7);
                    }
                }
            }
        }
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
            int var5 = bucketCenterY(var3);
            int var6 = sliceRange();
            int var7 = var5 + var6;
            int var8 = var5 - var6;
            int[] var9 = new int[256];
            int[] var10 = new int[256];
            int var11 = var1 << 4;
            int var12 = var2 << 4;
            int var13 = var0.getMaxBuildHeight();

            for (int var14 = 0; var14 < 16; var14++) {
                for (int var15 = 0; var15 < 16; var15++) {
                    int var16 = var14 << 4 | var15;
                    int var17 = sliceTop(var4, var15, var14, var7, var8, var0.getMinBuildHeight(), var13);
                    var9[var16] = var17;
                    if (var17 == Integer.MIN_VALUE) {
                        var10[var16] = -15723752;
                    } else {
                        BlockPos var18 = new BlockPos(var11 + var15, var17, var12 + var14);
                        BlockState var19 = var4.getBlockState(var18);
                        int var20 = mapColorAbgr(var19.getMapColor(var4, var18));
                        if (!var19.getFluidState().isEmpty()) {
                            int var21 = 0;

                            for (BlockPos var22 = var18; var21 < 12 && !var4.getFluidState(var22).isEmpty(); var21++) {
                                var22 = var22.below();
                            }

                            var20 = shadeAbgr(var20, Math.max(0.4F, 1.0F - var21 * 0.08F));
                        } else {
                            BlockPos var24 = var18.above();
                            boolean var25 = var24.getY() < var13 && var4.getBlockState(var24).isAir();
                            var20 = shadeAbgr(var20, var25 ? 1.12F : 0.66F);
                        }

                        var10[var16] = shadeAbgr(var20, 0.88F);
                    }
                }
            }

            return new MapTileCache.Entry(var10, var9);
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

        Entry(int[] var1, int[] var2) {
            this.colors = var1;
            this.heights = var2;
        }
    }
}
