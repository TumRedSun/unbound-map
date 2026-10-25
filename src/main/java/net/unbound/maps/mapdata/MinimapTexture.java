package net.unbound.maps.mapdata;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.NativeImage.Format;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.unbound.maps.UnboundMapsMod;
import net.unbound.maps.render.MinimapHud;

public final class MinimapTexture {
    public static final int SIZE = 512;
    private static final ResourceLocation LOCATION = UnboundMapsMod.id("minimap_texture");
    private static DynamicTexture texture;
    private static int anchorX;
    private static int anchorZ;
    private static boolean dirtyAll = true;
    private static final LongOpenHashSet dirtyChunks = new LongOpenHashSet();

    private MinimapTexture() {
    }

    public static ResourceLocation location() {
        return LOCATION;
    }

    public static int anchorX() {
        return anchorX;
    }

    public static int anchorZ() {
        return anchorZ;
    }

    public static void markChunkDirty(long var0) {
        dirtyChunks.add(var0);
    }

    public static void invalidateAll() {
        dirtyAll = true;
    }

    private static void ensureTexture() {
        if (texture == null) {
            NativeImage var0 = new NativeImage(Format.RGBA, 512, 512, true);

            for (int var1 = 0; var1 < 512; var1++) {
                for (int var2 = 0; var2 < 512; var2++) {
                    var0.setPixelRGBA(var2, var1, -15723752);
                }
            }

            texture = new DynamicTexture(var0);
            Minecraft.getInstance().getTextureManager().register(LOCATION, texture);
            dirtyAll = true;
        }
    }

    public static void clientTick(ClientLevel var0) {
        ensureTexture();
        Minecraft var1 = Minecraft.getInstance();
        if (var1.player != null) {
            int var2 = Mth.floor(var1.player.getX());
            int var3 = Mth.floor(var1.player.getZ());
            MapTileCache.onCaveViewChanged();
            // re-anchor when the CURRENT view would run past the 512x512 texture:
            // normally the player may drift 128 blocks off-center; the enlarged map
            // (Z key) can show up to ~246 blocks, so it keeps the player centered
            int keep = MinimapHud.isBig()
                ? Math.max(4, 256 - Mth.ceil(MinimapHud.currentViewBlocks()))
                : 128;
            if (Math.abs(var2 - (anchorX + 256)) > keep || Math.abs(var3 - (anchorZ + 256)) > keep) {
                anchorX = var2 - 256;
                anchorZ = var3 - 256;
                dirtyAll = true;
            }

            if (var0.getGameTime() % 20L == 0L) {
                int var4 = var2 >> 4;
                int var5 = var3 >> 4;

                for (int var6 = -1; var6 <= 1; var6++) {
                    for (int var7 = -1; var7 <= 1; var7++) {
                        MapTileCache.invalidate(ChunkPos.asLong(var4 + var7, var5 + var6));
                    }
                }
            }

            // newly LOADED chunks used to stay dark until the player walked up to
            // them (only the 3x3 around the player was refreshed), which made the
            // maps draw explored terrain 10-20 s late - pick up every loaded chunk
            // inside the texture so fresh terrain appears within a second
            if (var0.getGameTime() % 20L == 10L) {
                scanLoadedChunks(var0);
            }

            if (dirtyAll) {
                int var21 = anchorX >> 4;
                int var23 = anchorX + 512 - 1 >> 4;
                int var25 = anchorZ >> 4;
                int var27 = anchorZ + 512 - 1 >> 4;

                for (int var8 = var25; var8 <= var27; var8++) {
                    for (int var9 = var21; var9 <= var23; var9++) {
                        dirtyChunks.add(ChunkPos.asLong(var9, var8));
                    }
                }

                dirtyAll = false;
            }

            if (!dirtyChunks.isEmpty()) {
                long[] var22 = dirtyChunks.toLongArray();
                dirtyChunks.clear();
                boolean slice = MapTileCache.isMiniCave();
                int miniBucket = MapTileCache.miniBucket();
                // fresh tile COMPUTATIONS are the expensive part (heightmap / cut-lid
                // scans); redraws of already computed tiles are nearly free. Chunks
                // inside the current view are always worth doing; fresh computes are
                // budgeted per tick so a re-anchor never stalls the frame, and tiles
                // outside the view trickle in the background
                int computeBudget = slice ? 24 : 32;
                int backgroundBudget = 8;
                int halfView = Mth.ceil(MinimapHud.currentViewBlocks()) + 16;
                int drawn = 0;
                int computed = 0;
                NativeImage var28 = texture.getPixels();
                if (var28 != null) {
                    // pass 0: chunks in the current view, pass 1: background trickle
                    for (int pass = 0; pass < 2; pass++) {
                        for (long var11 : var22) {
                            int var13 = ChunkPos.getX(var11);
                            int var14 = ChunkPos.getZ(var11);
                            boolean visible = Math.abs((var13 << 4) + 8 - var2) <= halfView
                                && Math.abs((var14 << 4) + 8 - var3) <= halfView;
                            if (pass == 0 != visible) {
                                continue;
                            }
                            boolean cached = MapTileCache.isCached(var11, slice, miniBucket);
                            if (!cached) {
                                if (visible) {
                                    if (computed >= computeBudget) {
                                        dirtyChunks.add(var11);
                                        continue;
                                    }
                                    computed++;
                                } else {
                                    if (backgroundBudget <= 0) {
                                        dirtyChunks.add(var11);
                                        continue;
                                    }
                                    backgroundBudget--;
                                }
                            }

                            int var15 = (var13 << 4) - anchorX;
                            int var16 = (var14 << 4) - anchorZ;
                            if (var15 < 512 && var16 < 512 && var15 + 16 > 0 && var16 + 16 > 0) {
                                drawn++;
                                for (int var17 = 0; var17 < 16; var17++) {
                                    int var18 = var16 + var17;
                                    if (var18 >= 0 && var18 < 512) {
                                        for (int var19 = 0; var19 < 16; var19++) {
                                            int var20 = var15 + var19;
                                            if (var20 >= 0 && var20 < 512) {
                                                var28.setPixelRGBA(var20, var18, MapTileCache.colorFor(var0, (var13 << 4) + var19, (var14 << 4) + var17, slice, miniBucket));
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (drawn > 0) {
                        texture.upload();
                    }
                }
            }
        }
    }

    /** Marks every LOADED chunk inside the 512x512 texture that has no live tile yet as dirty. */
    private static void scanLoadedChunks(ClientLevel var0) {
        int var1 = anchorX >> 4;
        int var2 = (anchorX + 511) >> 4;
        int var3 = anchorZ >> 4;
        int var4 = (anchorZ + 511) >> 4;
        boolean slice = MapTileCache.isMiniCave();

        for (int var5 = var3; var5 <= var4; var5++) {
            for (int var6 = var1; var6 <= var2; var6++) {
                long var7 = ChunkPos.asLong(var6, var5);
                if (!dirtyChunks.contains(var7)
                    && MapTileCache.needsRefresh(var0, var7, slice, MapTileCache.miniBucket())
                    && var0.getChunk(var6, var5, ChunkStatus.FULL, false) != null) {
                    MapTileCache.invalidate(var7);
                }
            }
        }
    }
}
