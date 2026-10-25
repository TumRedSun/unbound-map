package net.unbound.maps.mapdata;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.NativeImage.Format;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
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
 * Baked texture cells for the world map, Xaero-style.
 *
 * <p>Each cell is a 512x512 texture. {@code lod} (level of detail) decides how
 * many blocks one texel covers: 1 << lod. Zoomed out views pick a higher lod so
 * only a handful of cells is ever visible and re-baking stays cheap, while the
 * GPU scales the textures - panning and zooming therefore never re-bake
 * anything that is already on screen.</p>
 *
 * <p>Cell creation is budgeted: a cell fills in strip by strip over a few
 * frames (map data streams in progressively instead of stalling the frame).
 * Every cell remembers the view mode (surface / cave slice + bucket) it was
 * baked for and is ONLY ever redrawn in that mode - mixing modes inside one
 * cell used to show caves "through" mountains.</p>
 */
public final class WorldMapCells {
    /** Texture side in texels (and in blocks at stride 1). */
    public static final int CELL = 512;
    private static final int MAX_CELLS = 160;
    private static final int MAX_LOD = 5;
    private static final int STRIP_ROWS = 16;
    private static final int STRIPS_PER_CELL = CELL / STRIP_ROWS;
    private static final int FRAME_PIXEL_BUDGET = 163840;
    private static final Map<WorldMapCells.CellKey, WorldMapCells.Cell> CELLS = new LinkedHashMap<>(64, 0.75F, true);
    private static final ArrayDeque<WorldMapCells.Cell> PENDING = new ArrayDeque<>();
    private static int currentLod;
    // visible cell range of the CURRENT frame, used to keep alive cells that are on screen
    private static int visMinX;
    private static int visMaxX;
    private static int visMinZ;
    private static int visMaxZ;

    private WorldMapCells() {
    }

    public static class Cell {
        public final int cellX;
        public final int cellZ;
        public final int lod;
        public final boolean cave;
        public final int bucket;
        public final ResourceLocation location;
        public final DynamicTexture texture;
        private int nextStrip;
        private boolean dead;
        private final LongOpenHashSet dirty = new LongOpenHashSet();

        Cell(int var1, int var2, int var3, boolean var4, int var5, ResourceLocation var6, DynamicTexture var7) {
            this.cellX = var1;
            this.cellZ = var2;
            this.lod = var3;
            this.cave = var4;
            this.bucket = var5;
            this.location = var6;
            this.texture = var7;
        }
    }

    /** Lod for the current zoom: 1 texel = 2^lod blocks, keeps the visible cell count small. */
    private static int lodFor(double var0) {
        if (var0 < 1.0) {
            return 0;
        }
        int var2 = Mth.floor((float)(Math.log(var0) / Math.log(2.0)));
        return Mth.clamp(var2, 0, MAX_LOD);
    }

    public static int currentLod() {
        return currentLod;
    }

    /** Side of one cell in blocks at the current lod. */
    public static int cellBlocks() {
        return CELL << currentLod;
    }

    public static WorldMapCells.Cell cell(int var0, int var1) {
        return CELLS.get(new WorldMapCells.CellKey(var0, var1, MapTileCache.isCave(), MapTileCache.caveBucket(), currentLod));
    }

    /**
     * A cell is drawable only while its GL texture is alive. A texture whose id
     * was released (or whose pixels were disposed) used to be bound as the
     * vanilla missing texture, which painted huge magenta/black checkerboard
     * squares over the map - such cells are detected here, dropped and rebuilt.
     */
    public static boolean isDrawable(WorldMapCells.Cell var0) {
        return var0 != null && var0.texture.getId() != -1 && var0.texture.getPixels() != null;
    }

    public static void clearAll() {
        Minecraft var0 = Minecraft.getInstance();

        for (WorldMapCells.Cell var2 : CELLS.values()) {
            var2.dead = true;
            var0.getTextureManager().release(var2.location);
        }

        CELLS.clear();
        PENDING.clear();
    }

    public static void markChunkDirty(long var0) {
        if (!CELLS.isEmpty()) {
            int var2 = ChunkPos.getX(var0);
            int var3 = ChunkPos.getZ(var0);

            for (WorldMapCells.Cell var7 : CELLS.values()) {
                if (var7.cellX == (var2 >> (5 + var7.lod)) && var7.cellZ == (var3 >> (5 + var7.lod))) {
                    var7.dirty.add(var0);
                }
            }
        }
    }

    public static void ensureVisible(ClientLevel var0, double var1, double var3, int var5, int var6, double var7) {
        currentLod = lodFor(var7);
        boolean var9 = MapTileCache.isCave();
        int var10 = MapTileCache.caveBucket();
        int var11 = cellBlocks();
        double var12 = var1 - var5 * var7 / 2.0;
        double var14 = var1 + var5 * var7 / 2.0;
        double var16 = var3 - var6 * var7 / 2.0;
        double var18 = var3 + var6 * var7 / 2.0;
        int var20 = Math.floorDiv(Mth.floor(var12), var11);
        int var21 = Math.floorDiv(Mth.floor(var14), var11);
        int var22 = Math.floorDiv(Mth.floor(var16), var11);
        int var23 = Math.floorDiv(Mth.floor(var18), var11);
        visMinX = var20;
        visMaxX = var21;
        visMinZ = var22;
        visMaxZ = var23;

        for (int var24 = var22; var24 <= var23; var24++) {
            for (int var25 = var20; var25 <= var21; var25++) {
                WorldMapCells.CellKey var26 = new WorldMapCells.CellKey(var25, var24, var9, var10, currentLod);
                WorldMapCells.Cell var27 = CELLS.get(var26);
                if (var27 != null && !isDrawable(var27)) {
                    // the GL texture died (released underneath us): drop and rebuild
                    dropCell(var27);
                    var27 = null;
                }
                if (var27 == null) {
                    var27 = createCell(var0, var25, var24, var9, var10, currentLod);
                    CELLS.put(var26, var27);
                    PENDING.addLast(var27);
                }
            }
        }

        processBudget(var0);

        for (WorldMapCells.Cell var28 : CELLS.values()) {
            redrawDirty(var0, var28);
        }

        evictOld();
    }

    /** Fully removes one cell: texture release, CELLS removal (PENDING skips dead cells). */
    private static void dropCell(WorldMapCells.Cell var0) {
        var0.dead = true;
        Minecraft.getInstance().getTextureManager().release(var0.location);
        CELLS.values().removeIf(var1 -> var1 == var0);
    }

    private static WorldMapCells.Cell createCell(ClientLevel var0, int var1, int var2, boolean var3, int var4, int var5) {
        NativeImage var6 = new NativeImage(Format.RGBA, CELL, CELL, true);

        for (int var7 = 0; var7 < CELL; var7++) {
            for (int var8 = 0; var8 < CELL; var8++) {
                var6.setPixelRGBA(var8, var7, -15723752);
            }
        }

        DynamicTexture var16 = new DynamicTexture(var6);
        String var17 = var3 ? "cave" + var4 + "_" : "";
        ResourceLocation var8 = UnboundMapsMod.id("dynmap_cell_" + var17 + "lod" + var5 + "_" + var1 + "_" + var2);
        Minecraft.getInstance().getTextureManager().register(var8, var16);
        return new WorldMapCells.Cell(var1, var2, var5, var3, var4, var8, var16);
    }

    /** Fills pending cell strips (nearest-first is approximated by FIFO) within the per-frame pixel budget. */
    private static void processBudget(ClientLevel var0) {
        if (PENDING.isEmpty()) {
            return;
        }
        int var1 = FRAME_PIXEL_BUDGET;
        java.util.ArrayList<WorldMapCells.Cell> var2 = new java.util.ArrayList<>();

        while (var1 > 0 && !PENDING.isEmpty()) {
            WorldMapCells.Cell var3 = PENDING.pollFirst();
            if (var3.dead) {
                continue;
            }
            fillStrip(var0, var3, var3.nextStrip++);
            var1 -= CELL * STRIP_ROWS;
            var2.add(var3);
            if (var3.nextStrip < STRIPS_PER_CELL) {
                PENDING.addLast(var3);
            }
        }

        for (WorldMapCells.Cell var4 : var2) {
            var4.texture.upload();
        }
    }

    private static void fillStrip(ClientLevel var0, WorldMapCells.Cell var1, int var2) {
        NativeImage var3 = var1.texture.getPixels();
        if (var3 == null) {
            return;
        }
        int var4 = 1 << var1.lod;
        int var5 = var1.cellX << (9 + var1.lod);
        int var6 = var1.cellZ << (9 + var1.lod);
        int var7 = var2 * STRIP_ROWS;

        for (int var8 = var7; var8 < var7 + STRIP_ROWS; var8++) {
            int var9 = var6 + (var8 << var1.lod) + (var4 >> 1);

            for (int var10 = 0; var10 < CELL; var10++) {
                int var11 = var5 + (var10 << var1.lod) + (var4 >> 1);
                int var12 = var1.cave
                    ? MapTileCache.getSliceColor(var0, var11, var9, var1.bucket)
                    : MapTileCache.getColor(var0, var11, var9);
                var3.setPixelRGBA(var10, var8, var12);
            }
        }
    }

    /** Redraws dirty chunks, ALWAYS in the mode the cell itself was baked for. */
    private static void redrawDirty(ClientLevel var0, WorldMapCells.Cell var1) {
        if (!var1.dirty.isEmpty()) {
            long[] var2 = var1.dirty.toLongArray();
            var1.dirty.clear();

            for (long var6 : var2) {
                fillChunk(var0, var1, ChunkPos.getX(var6), ChunkPos.getZ(var6));
            }

            var1.texture.upload();
        }
    }

    private static void fillChunk(ClientLevel var0, WorldMapCells.Cell var1, int var2, int var3) {
        NativeImage var4 = var1.texture.getPixels();
        if (var4 == null) {
            return;
        }
        int var5 = 1 << var1.lod;
        int var6 = var1.cellX << (9 + var1.lod);
        int var7 = var1.cellZ << (9 + var1.lod);
        int var8 = Math.floorDiv(var2 << 4, var5) - (var6 >> var1.lod);
        int var9 = Math.floorDiv((var2 << 4) + 15, var5) - (var6 >> var1.lod);
        int var10 = Math.floorDiv(var3 << 4, var5) - (var7 >> var1.lod);
        int var11 = Math.floorDiv((var3 << 4) + 15, var5) - (var7 >> var1.lod);
        int var12 = Math.max(var8, 0);
        int var13 = Math.max(var10, 0);
        int var14 = Math.min(var9, CELL - 1);
        int var15 = Math.min(var11, CELL - 1);

        for (int var16 = var13; var16 <= var15; var16++) {
            for (int var17 = var12; var17 <= var14; var17++) {
                int var18 = var6 + (var17 << var1.lod) + (var5 >> 1);
                int var19 = var7 + (var16 << var1.lod) + (var5 >> 1);
                int var20 = var1.cave
                    ? MapTileCache.getSliceColor(var0, var18, var19, var1.bucket)
                    : MapTileCache.getColor(var0, var18, var19);
                var4.setPixelRGBA(var17, var16, var20);
            }
        }
    }

    private static void evictOld() {
        Minecraft var0 = Minecraft.getInstance();

        while (CELLS.size() > MAX_CELLS) {
            // evict the least recently used cell, but never one that is visible in
            // the current frame unless nothing else can go (evicting on-screen
            // cells made them vanish and re-bake every frame at deep zoom-outs)
            WorldMapCells.Cell var2 = null;
            boolean anyHidden = false;
            for (WorldMapCells.Cell var4 : CELLS.values()) {
                boolean visible = var4.cellX >= visMinX && var4.cellX <= visMaxX && var4.cellZ >= visMinZ && var4.cellZ <= visMaxZ;
                if (!visible) {
                    var2 = var4;
                    anyHidden = true;
                    break;
                }
            }

            if (!anyHidden) {
                var2 = CELLS.values().iterator().next();
            }

            final WorldMapCells.Cell var3 = var2;
            var3.dead = true;
            var0.getTextureManager().release(var3.location);
            CELLS.values().removeIf(var5 -> var5 == var3);
        }
    }

    private record CellKey(int cellX, int cellZ, boolean cave, int bucket, int lod) {
    }
}
