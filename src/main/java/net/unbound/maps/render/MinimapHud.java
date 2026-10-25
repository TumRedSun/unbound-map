package net.unbound.maps.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw.Layer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.util.Mth;
import net.unbound.maps.UnboundConfig;
import net.unbound.maps.UnboundMapsDebug;
import net.unbound.maps.mapdata.MapTileCache;
import net.unbound.maps.mapdata.MinimapTexture;
import org.joml.Matrix4f;

public final class MinimapHud {
    private static final int[] ZOOM_LEVELS = new int[]{32, 64, 128};
    private static int zoomIndex = 1;
    public static final Layer LAYER = MinimapHud::render;

    private MinimapHud() {
    }

    /** Xaero-style night dimming factor for map tiles (1.0 at day, ~0.55 at night). */
    public static float mapDim(Minecraft var0) {
        try {
            if (var0.level != null && UnboundConfig.NIGHT_DIM.get()) {
                return 1.0F - 0.45F * (float)var0.level.getSkyDarken() / 15.0F;
            }
        } catch (NullPointerException | IllegalStateException var1) {
        }

        return 1.0F;
    }

    public static void zoomIn() {
        if (zoomIndex > 0) {
            zoomIndex--;
        }
    }

    public static void zoomOut() {
        if (zoomIndex < ZOOM_LEVELS.length - 1) {
            zoomIndex++;
        }
    }

    private static void render(GuiGraphics var0, DeltaTracker var1) {
        Minecraft var2 = Minecraft.getInstance();
        LocalPlayer var3 = var2.player;
        ClientLevel var4 = var2.level;
        if (var3 != null && var4 != null && UnboundConfig.MINIMAP_ENABLED.get()) {
            try {
                var0.flush();
                int var5 = Math.max(32, UnboundConfig.MINIMAP_SIZE.get()) / 2;
                int var6 = var0.guiWidth() - var5 - 8;
                int var7 = var5 + 8;
                float var8 = ZOOM_LEVELS[zoomIndex];
                boolean var9 = UnboundConfig.MINIMAP_SHAPE.get() == UnboundConfig.Shape.SQUARE;
                double var10 = var3.getX();
                double var12 = var3.getZ();
                int var14 = MinimapTexture.anchorX();
                int var15 = MinimapTexture.anchorZ();
                float var16 = 0.001953125F;
                Matrix4f var17 = var0.pose().last().pose();
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                float dimFactor = mapDim(var2);
                RenderSystem.setShaderColor(dimFactor, dimFactor, dimFactor, 1.0F);
                if (var9) {
                    renderSquare(var0, var17, var6, var7, var5, var8, var10, var12, var14, var15, var16);
                } else {
                    renderRound(var17, var6, var7, var5, var8, var10, var12, var14, var15, var16);
                }
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

                var0.drawString(var2.font, "N", var6 - var2.font.width("N") / 2, var7 - var5 - 11, -4668212, true);
                WaypointRenderer.drawMinimapMarkers(var0, var6, var7, var5, var8, var10, var12, var9);
                EntityRadar.draw(var0, var6, var7, var5, var8, var10, var12, var9);
                DrawHelper.playerArrow(var17, var6, var7, var3.getYRot());
                boolean var18 = UnboundConfig.SHOW_COORDS.get();
                boolean var19 = UnboundConfig.SHOW_BIOME.get();
                boolean var20 = MapTileCache.isMinimapSlice();
                int var21 = (var18 ? 1 : 0) + (var19 ? 1 : 0) + (var20 ? 1 : 0);
                if (var21 > 0) {
                    int var22 = 2 * var5 + 6;
                    int var23 = var21 * 10 + 4;
                    int var24 = var6 - var5 - 3;
                    int var25 = var7 + var5 + 7;
                    var0.fill(var24, var25, var24 + var22, var25 + var23, -1945104364);
                    var0.renderOutline(var24, var25, var24 + var22, var25 + var23, 1711276032);
                    int var26 = var25 + 3;
                    if (var20) {
                        var0.drawCenteredString(var2.font, I18n.get("gui.unboundmaps.caveLabel"), var6, var26, -1727668);
                        var26 += 10;
                    }

                    if (var18) {
                        var0.drawCenteredString(
                            var2.font, "X " + var3.getBlockX() + "  Y " + var3.getBlockY() + "  Z " + var3.getBlockZ(), var6, var26, -1643021
                        );
                        var26 += 10;
                    }

                    if (var19) {
                        String var27 = var4.getBiome(var3.blockPosition())
                            .unwrapKey()
                            .map(var0x -> var0x.location().getPath())
                            .orElse("unknown")
                            .replace('_', ' ');
                        var0.drawCenteredString(var2.font, var27, var6, var26, -4668212);
                    }
                }

                RenderSystem.disableBlend();
            } catch (Exception var28) {
                UnboundMapsDebug.note(var28);
            }
        }
    }

    private static void renderSquare(
        GuiGraphics var0, Matrix4f var1, int var2, int var3, int var4, float var5, double var6, double var8, int var10, int var11, float var12
    ) {
        float var13 = var2 - var4;
        float var14 = var3 - var4;
        float var15 = var2 + var4;
        float var16 = var3 + var4;
        float var17 = (float)((var6 - var5 - var10) * var12);
        float var18 = (float)((var8 - var5 - var11) * var12);
        float var19 = (float)((var6 + var5 - var10) * var12);
        float var20 = (float)((var8 + var5 - var11) * var12);
        DrawHelper.texturedQuad(var1, MinimapTexture.location(), var13, var14, var15, var16, var17, var18, var19, var20);
        int var21 = Mth.floor(var13);
        int var22 = Mth.floor(var14);
        int var23 = Mth.floor(var15);
        int var24 = Mth.floor(var16);
        var0.renderOutline(var21 - 2, var22 - 2, var23 + 2, var24 + 2, -267382764);
        var0.renderOutline(var21 - 1, var22 - 1, var23 + 1, var24 + 1, -267382764);
        var0.renderOutline(var21, var22, var23, var24, -1879048192);
    }

    private static void renderRound(Matrix4f var0, int var1, int var2, int var3, float var4, double var5, double var7, int var9, int var10, float var11) {
        RenderSystem.setShaderTexture(0, MinimapTexture.location());
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        BufferBuilder var12 = Tesselator.getInstance().begin(Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_TEX);
        var12.addVertex(var0, var1, var2, 0.0F).setUv((float)((var5 - var9) * var11), (float)((var7 - var10) * var11));
        int var13 = 48;

        for (int var14 = 0; var14 <= var13; var14++) {
            double var15 = var14 % var13 * ((Math.PI * 2) / var13);
            float var17 = var1 + (float)(Math.cos(var15) * var3);
            float var18 = var2 + (float)(Math.sin(var15) * var3);
            double var19 = var5 + Math.cos(var15) * var4;
            double var21 = var7 + Math.sin(var15) * var4;
            var12.addVertex(var0, var17, var18, 0.0F).setUv((float)((var19 - var9) * var11), (float)((var21 - var10) * var11));
        }

        BufferUploader.drawWithShader(var12.buildOrThrow());
        DrawHelper.ring(var0, var1, var2, var3 - 1.0F, var3 + 2.5F, var13, -267382764);
    }
}
