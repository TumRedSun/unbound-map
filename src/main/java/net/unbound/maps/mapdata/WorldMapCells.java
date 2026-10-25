package net.unbound.maps.mapdata;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.NativeImage.Format;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.unbound.maps.UnboundMapsMod;

public final class WorldMapCells {
    public static final int CELL = 512;
    private static final int MAX_CELLS = 40;
    private static final Map<WorldMapCells.CellKey, WorldMapCells.Cell> CELLS = new LinkedHashMap<>(64, 0.75F, true);

    private WorldMapCells() {
    }

    public static WorldMapCells.Cell cell(int var0, int var1) {
        return CELLS.get(new WorldMapCells.CellKey(var0, var1, MapTileCache.isCave(), MapTileCache.caveBucket()));
    }

    public static void clearAll() {
        Minecraft var0 = Minecraft.getInstance();

        for (WorldMapCells.Cell var2 : CELLS.values()) {
            var0.getTextureManager().release(var2.location);
        }

        CELLS.clear();
    }

    public static void markChunkDirty(long var0) {
        if (!CELLS.isEmpty()) {
            int var2 = ChunkPos.getX(var0);
            int var3 = ChunkPos.getZ(var0);
            int var4 = cellOfChunk(var2);
            int var5 = cellOfChunk(var3);

            for (WorldMapCells.Cell var7 : CELLS.values()) {
                if (var7.cellX == var4 && var7.cellZ == var5) {
                    var7.dirty.add(var0);
                }
            }
        }
    }

    public static void ensureVisible(ClientLevel var0, double var1, double var3, int var5, int var6, double var7) {
        boolean var9 = MapTileCache.isCave();
        int var10 = MapTileCache.caveBucket();
        int var11 = Math.floorDiv(floor(var1 - var5 * var7 / 2.0), 512);
        int var12 = Math.floorDiv(floor(var1 + var5 * var7 / 2.0), 512);
        int var13 = Math.floorDiv(floor(var3 - var6 * var7 / 2.0), 512);
        int var14 = Math.floorDiv(floor(var3 + var6 * var7 / 2.0), 512);

        for (int var15 = var13; var15 <= var14; var15++) {
            for (int var16 = var11; var16 <= var12; var16++) {
                WorldMapCells.CellKey var17 = new WorldMapCells.CellKey(var16, var15, var9, var10);
                if (!CELLS.containsKey(var17)) {
                    CELLS.put(var17, createCell(var0, var16, var15, var9, var10));
                    evictOld();
                }
            }
        }

        for (WorldMapCells.Cell var19 : CELLS.values()) {
            redrawDirty(var0, var19);
        }
    }

    private static WorldMapCells.Cell createCell(ClientLevel var0, int var1, int var2, boolean var3, int var4) {
        NativeImage var5 = new NativeImage(Format.RGBA, 512, 512, true);

        for (int var6 = 0; var6 < 512; var6++) {
            for (int var7 = 0; var7 < 512; var7++) {
                var5.setPixelRGBA(var7, var6, -15723752);
            }
        }

        DynamicTexture var16 = new DynamicTexture(var5);
        String var17 = var3 ? "cave" + var4 + "_" : "";
        ResourceLocation var8 = UnboundMapsMod.id("dynmap_cell_" + var17 + var1 + "_" + var2);
        Minecraft.getInstance().getTextureManager().register(var8, var16);
        WorldMapCells.Cell var9 = new WorldMapCells.Cell(var1, var2, var8, var16);
        int var10 = var1 * 512 >> 4;
        int var11 = var1 * 512 + 512 - 1 >> 4;
        int var12 = var2 * 512 >> 4;
        int var13 = var2 * 512 + 512 - 1 >> 4;

        for (int var14 = var12; var14 <= var13; var14++) {
            for (int var15 = var10; var15 <= var11; var15++) {
                fillChunk(var0, var9, var15, var14, var3, var4);
            }
        }

        var16.upload();
        return var9;
    }

    private static void fillChunk(ClientLevel var0, WorldMapCells.Cell var1, int var2, int var3, boolean var4, int var5) {
        NativeImage var6 = var1.texture.getPixels();
        if (var6 != null) {
            int var7 = var2 << 4;
            int var8 = var3 << 4;
            int var9 = var7 - var1.cellX * 512;
            int var10 = var8 - var1.cellZ * 512;
            if (var9 >= 0 && var10 >= 0 && var9 + 16 <= 512 && var10 + 16 <= 512) {
                for (int var11 = 0; var11 < 16; var11++) {
                    for (int var12 = 0; var12 < 16; var12++) {
                        int var13 = var4
                            ? MapTileCache.getSliceColor(var0, var7 + var12, var8 + var11, var5)
                            : MapTileCache.getColor(var0, var7 + var12, var8 + var11);
                        var6.setPixelRGBA(var9 + var12, var10 + var11, var13);
                    }
                }
            }
        }
    }

    private static void redrawDirty(ClientLevel var0, WorldMapCells.Cell var1) {
        if (!var1.dirty.isEmpty()) {
            boolean var2 = MapTileCache.isCave();
            int var3 = MapTileCache.caveBucket();
            long[] var4 = var1.dirty.toLongArray();
            var1.dirty.clear();

            for (long var8 : var4) {
                fillChunk(var0, var1, ChunkPos.getX(var8), ChunkPos.getZ(var8), var2, var3);
            }

            var1.texture.upload();
        }
    }

    private static void evictOld() {
        Minecraft var0 = Minecraft.getInstance();

        while (CELLS.size() > 40) {
            Iterator var1 = CELLS.entrySet().iterator();
            Entry var2 = (Entry)var1.next();
            var0.getTextureManager().release(((WorldMapCells.Cell)var2.getValue()).location);
            var1.remove();
        }
    }

    private static int cellOfChunk(int var0) {
        return var0 >> 5;
    }

    private static int floor(double var0) {
        return Mth.floor(var0);
    }

    public static final class Cell {
        public final int cellX;
        public final int cellZ;
        public final ResourceLocation location;
        public final DynamicTexture texture;
        private final LongOpenHashSet dirty = new LongOpenHashSet();

        Cell(int var1, int var2, ResourceLocation var3, DynamicTexture var4) {
            this.cellX = var1;
            this.cellZ = var2;
            this.location = var3;
            this.texture = var4;
        }
    }

    private record CellKey(int cellX, int cellZ, boolean cave, int bucket) {
    }
}
