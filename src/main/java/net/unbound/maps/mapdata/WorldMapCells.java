package net.unbound.maps.mapdata;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.NativeImage.Format;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.unbound.maps.UnboundMapsMod;

/**
 * Baked map tiles ("cells") behind the world map.
 *
 * <p>Every cell is a 512x512 texture. Its ground span grows with the LOD
 * stride: {@code span = 512 * stride} blocks, so one texel always covers
 * {@code stride} blocks. Far zoom-out therefore needs far fewer cells
 * (stride 8 = 64x fewer) while the texel stays roughly screen-pixel sized.</p>
 *
 * <p>Perf model (this used to freeze the game at high zoom-out): creating a
 * cell only allocates the texture and queues its chunks center-out, and
 * {@link #ensureVisible} bakes a limited number of chunks per frame, so the
 * map builds progressively like Xaero's instead of stalling. The cell key does
 * NOT include the sky darkness: day/night is a draw-time shader tint, so tiles
 * never re-bake when the sun sets.</p>
 */
public final class WorldMapCells {
    /** Texture side in texels (and in blocks at stride 1). */
    public static final int CELL = 512;
    private static final int MAX_CELLS = 64;
    /** Cells created per frame: 2 keeps panning smooth even across unexplored space. */
    private static final int MAX_NEW_CELLS_PER_FRAME = 2;
    /** Chunk fills shared by all cells per frame. */
    private static final int MAX_CHUNK_FILLS_PER_FRAME = 128;
    private static final Map<WorldMapCells.CellKey, WorldMapCells.Cell> CELLS = new LinkedHashMap<>(64, 0.75F, true);

    private WorldMapCells() {
    }

    /** Ground span of one cell at the given LOD stride, in blocks. */
    public static int span(int stride) {
        return CELL * stride;
    }

    public static WorldMapCells.Cell cell(int var0, int var1) {
        return CELLS.get(new WorldMapCells.CellKey(var0, var1, MapTileCache.isCave(), MapTileCache.caveBucket(), strideFor(currentBpp())));
    }

    /** Blocks per texture pixel of the active world map view (used for the LOD choice). */
    private static double currentBpp() {
        return net.unbound.maps.screen.WorldMapScreen.currentBlocksPerPixel();
    }

    /**
     * LOD stride by blocks-per-pixel: when one screen pixel covers several
     * blocks anyway, sampling every N-th block (one texel per N x N block area)
     * looks the same while costing N^2 times less per cell.
     */
    public static int strideFor(double var0) {
        if (var0 >= 0.5) {
            return 1;
        } else if (var0 >= 0.25) {
            return 2;
        } else {
            return var0 >= 0.125 ? 4 : 8;
        }
    }

    public static void clearAll() {
        Minecraft var0 = Minecraft.getInstance();

        for (WorldMapCells.Cell var2 : CELLS.values()) {
            var0.getTextureManager().release(var2.location);
        }

        CELLS.clear();
    }

    public static void markChunkDirty(long var0) {
        if (CELLS.isEmpty()) {
            return;
        }
        int var2 = ChunkPos.getX(var0) << 4;
        int var3 = ChunkPos.getZ(var0) << 4;

        for (WorldMapCells.Cell var7 : CELLS.values()) {
            int var8 = span(var7.stride);
            if (var2 >= var7.cellX * var8 && var2 < (var7.cellX + 1) * var8
                && var3 >= var7.cellZ * var8 && var3 < (var7.cellZ + 1) * var8) {
                var7.dirty.add(var0);
            }
        }
    }

    public static void ensureVisible(ClientLevel var0, double var1, double var3, int var5, int var6, double var7) {
        boolean var9 = MapTileCache.isCave();
        int var10 = MapTileCache.caveBucket();
        int var20 = strideFor(var7);
        int var21 = span(var20);
        double var22 = var1 - var5 * var7 / 2.0;
        double var23 = var3 - var6 * var7 / 2.0;
        int var11 = Math.floorDiv(Mth.floor(var22), var21);
        int var12 = Math.floorDiv(Mth.floor(var1 + var5 * var7 / 2.0), var21);
        int var13 = Math.floorDiv(Mth.floor(var23), var21);
        int var14 = Math.floorDiv(Mth.floor(var3 + var6 * var7 / 2.0), var21);
        int var15 = Math.floorDiv(Mth.floor(var1), var21);
        int var16 = Math.floorDiv(Mth.floor(var3), var21);

        // create missing cells center-out, a couple per frame
        List<int[]> var17 = new ArrayList<>();
        for (int var18 = var13; var18 <= var14; var18++) {
            for (int var19 = var11; var19 <= var12; var19++) {
                var17.add(new int[]{var19, var18});
            }
        }

        var17.sort(Comparator.comparingInt(c -> Math.max(Math.abs(c[0] - var15), Math.abs(c[1] - var16))));
        int var24 = 0;
        for (int[] var25 : var17) {
            WorldMapCells.CellKey var26 = new WorldMapCells.CellKey(var25[0], var25[1], var9, var10, var20);
            if (!CELLS.containsKey(var26)) {
                if (var24 >= MAX_NEW_CELLS_PER_FRAME) {
                    continue;
                }
                CELLS.put(var26, createCell(var0, var25[0], var25[1], var9, var10, var20));
                evictOld();
                var24++;
            }
        }

        // bake queued/dirty chunks within the per-frame budget, nearest cells first
        int var27 = MAX_CHUNK_FILLS_PER_FRAME;
        List<WorldMapCells.Cell> var28 = new ArrayList<>(CELLS.values());
        var28.sort(Comparator.comparingInt(c -> Math.max(Math.abs(c.cellX - var15), Math.abs(c.cellZ - var16))));
        for (WorldMapCells.Cell var29 : var28) {
            if (var27 <= 0) {
                break;
            }
            var27 -= var29.work(var0, var27, var9, var10, var20);
        }
    }

    private static WorldMapCells.Cell createCell(ClientLevel var0, int var1, int var2, boolean var3, int var4, int var5) {
        NativeImage var6 = new NativeImage(Format.RGBA, 512, 512, true);

        for (int var7 = 0; var7 < 512; var7++) {
            for (int var8 = 0; var8 < 512; var8++) {
                var6.setPixelRGBA(var8, var7, -15723752);
            }
        }

        DynamicTexture var9 = new DynamicTexture(var6);
        String var10 = var3 ? "cave" + var4 + "_" : "";
        ResourceLocation var11 = UnboundMapsMod.id("dynmap_cell_" + var10 + var1 + "_" + var2 + "_s" + var5);
        Minecraft.getInstance().getTextureManager().register(var11, var9);
        WorldMapCells.Cell var12 = new WorldMapCells.Cell(var1, var2, var5, var11, var9);

        // queue the chunks of this cell center-out so the visible middle appears first
        int var13 = span(var5);
        int var14 = Math.floorDiv(var1 * var13, 16);
        int var15 = Math.floorDiv((var1 + 1) * var13 - 1, 16);
        int var16 = Math.floorDiv(var2 * var13, 16);
        int var17 = Math.floorDiv((var2 + 1) * var13 - 1, 16);
        List<Long> var18 = new ArrayList<>();
        for (int var19 = var16; var19 <= var17; var19++) {
            for (int var20 = var14; var20 <= var15; var20++) {
                var18.add(ChunkPos.asLong(var20, var19));
            }
        }

        int var21 = (var14 + var15) / 2;
        int var22 = (var16 + var17) / 2;
        var18.sort(Comparator.comparingLong(p ->
            (long)Math.max(Math.abs(ChunkPos.getX(p) - var21), Math.abs(ChunkPos.getZ(p) - var22))
        ));
        for (long var23 : var18) {
            var12.pending.add(var23);
        }

        return var12;
    }

    private static void fillChunk(ClientLevel var0, WorldMapCells.Cell var1, long var2, boolean var4, int var5, int var6) {
        NativeImage var7 = var1.texture.getPixels();
        if (var7 == null) {
            return;
        }
        int var8 = ChunkPos.getX(var2) << 4;
        int var9 = ChunkPos.getZ(var2) << 4;
        int var10 = span(var6);
        int var11 = var8 - var1.cellX * var10;
        int var12 = var9 - var1.cellZ * var10;
        if (var11 >= 0 && var12 >= 0 && var11 + 16 <= var10 && var12 + 16 <= var10) {
            int var13 = var11 / var6;
            int var14 = var12 / var6;

            for (int var15 = 0; var15 < 16; var15 += var6) {
                for (int var16 = 0; var16 < 16; var16 += var6) {
                    int var17 = var4
                        ? MapTileCache.getSliceColor(var0, var8 + var16, var9 + var15, var5)
                        : MapTileCache.getColor(var0, var8 + var16, var9 + var15);
                    var7.setPixelRGBA(var13 + var16 / var6, var14 + var15 / var6, var17);
                }
            }
        }
    }

    private static void evictOld() {
        Minecraft var0 = Minecraft.getInstance();

        while (CELLS.size() > MAX_CELLS) {
            Iterator var1 = CELLS.entrySet().iterator();
            Entry var2 = (Entry)var1.next();
            var0.getTextureManager().release(((WorldMapCells.Cell)var2.getValue()).location);
            var1.remove();
        }
    }

    public static final class Cell {
        public final int cellX;
        public final int cellZ;
        /** LOD stride of this cell: one texel covers this many blocks. */
        public final int stride;
        public final ResourceLocation location;
        public final DynamicTexture texture;
        private final LongOpenHashSet dirty = new LongOpenHashSet();
        private final LongOpenHashSet pending = new LongOpenHashSet();

        Cell(int var1, int var2, int var3, ResourceLocation var4, DynamicTexture var5) {
            this.cellX = var1;
            this.cellZ = var2;
            this.stride = var3;
            this.location = var4;
            this.texture = var5;
        }

        /** Bakes up to {@code budget} queued/dirty chunks; returns how many were done. */
        int work(ClientLevel var1, int var2, boolean var3, int var4, int var5) {
            if (this.pending.isEmpty() && this.dirty.isEmpty()) {
                return 0;
            }
            int var6 = 0;
            boolean var7 = false;
            for (long var9 : this.pending.toLongArray()) {
                if (var6 >= var2) {
                    break;
                }
                this.pending.remove(var9);
                fillChunk(var1, this, var9, var3, var4, var5);
                var6++;
                var7 = true;
            }
            for (long var11 : this.dirty.toLongArray()) {
                if (var6 >= var2) {
                    break;
                }
                this.dirty.remove(var11);
                fillChunk(var1, this, var11, var3, var4, var5);
                var6++;
                var7 = true;
            }

            if (var7) {
                this.texture.upload();
            }

            return var6;
        }
    }

    private record CellKey(int cellX, int cellZ, boolean cave, int bucket, int stride) {
    }
}
