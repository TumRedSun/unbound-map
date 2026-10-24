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
import net.unbound.maps.UnboundMapsMod;

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
            MapTileCache.updateCaveBucket(var1.player.getBlockY());
            if (Math.abs(var2 - (anchorX + 256)) > 128 || Math.abs(var3 - (anchorZ + 256)) > 128) {
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
                int var24 = MapTileCache.isCaveMode() ? 24 : Integer.MAX_VALUE;
                int var26 = 0;
                NativeImage var28 = texture.getPixels();
                if (var28 != null) {
                    for (long var11 : var22) {
                        if (var26 >= var24) {
                            dirtyChunks.add(var11);
                        } else {
                            int var13 = ChunkPos.getX(var11);
                            int var14 = ChunkPos.getZ(var11);
                            int var15 = (var13 << 4) - anchorX;
                            int var16 = (var14 << 4) - anchorZ;
                            if (var15 < 512 && var16 < 512 && var15 + 16 > 0 && var16 + 16 > 0) {
                                var26++;

                                for (int var17 = 0; var17 < 16; var17++) {
                                    int var18 = var16 + var17;
                                    if (var18 >= 0 && var18 < 512) {
                                        for (int var19 = 0; var19 < 16; var19++) {
                                            int var20 = var15 + var19;
                                            if (var20 >= 0 && var20 < 512) {
                                                var28.setPixelRGBA(var20, var18, MapTileCache.colorFor(var0, (var13 << 4) + var19, (var14 << 4) + var17));
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (var26 > 0) {
                        texture.upload();
                    }
                }
            }
        }
    }
}
