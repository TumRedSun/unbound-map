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
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.unbound.maps.UnboundConfig;
import net.unbound.maps.UnboundMapsDebug;
import net.unbound.maps.mapdata.CaveState;
import net.unbound.maps.mapdata.MapTileCache;
import net.unbound.maps.mapdata.MinimapTexture;
import org.joml.Matrix4f;

public final class MinimapHud {
    private static final int[] ZOOM_LEVELS = new int[]{32, 64, 128};
    private static int zoomIndex = 1;
    public static final Layer LAYER = MinimapHud::render;

    private MinimapHud() {
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
        if (var3 != null && var4 != null && var2.screen == null && UnboundConfig.MINIMAP_ENABLED.get()) {
            try {
                // start from a clean batching state, so nothing from the vanilla HUD
                // (or a previous frame's leftovers) can mix into the map quads
                var0.flush();
                LabelPlacer.begin();
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
                // gentle Xaero-like night dim, multiplied straight into the map
                // vertices (cave view is exempt: it is lit by block light)
                float var18 = MapTileCache.nightFactor();
                int var19 = tintFor(var18);
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                if (var9) {
                    renderSquare(var0, var17, var6, var7, var5, var8, var10, var12, var14, var15, var16, var19);
                } else {
                    renderRound(var17, var6, var7, var5, var8, var10, var12, var14, var15, var16, var19);
                }

                var0.flush();
                var0.drawString(
                    var2.font,
                    "N",
                    var6 - var2.font.width("N") / 2,
                    Math.max(1, var7 - var5 - 11),
                    -4668212,
                    true
                );
                WaypointRenderer.drawMinimapMarkers(var0, var6, var7, var5, var8, var10, var12, var9);
                EntityRadar.draw(var0, var6, var7, var5, var8, var10, var12, var9);
                DrawHelper.playerArrow(var17, var6, var7, var3.getYRot());
                var0.flush();
                boolean var20 = UnboundConfig.SHOW_COORDS.get();
                boolean var21 = UnboundConfig.SHOW_BIOME.get();
                boolean var22 = MapTileCache.isCave();
                int var23 = (var20 ? 1 : 0) + (var21 ? 1 : 0) + (var22 ? 1 : 0);
                if (var23 > 0) {
                    int var24 = 2 * var5 + 6;
                    int var25 = var23 * 10 + 4;
                    int var26 = Mth.clamp(var6 - var5 - 3, 2, Math.max(2, var0.guiWidth() - var24 - 2));
                    int var27 = var7 + var5 + 7;
                    var0.fill(var26, var27, var26 + var24, var27 + var25, -1945104364);
                    var0.renderOutline(var26, var27, var26 + var24, var27 + var25, 1711276032);
                    int var28 = var27 + 3;
                    if (var22) {
                        String var23c = I18n.get("gui.unboundmaps.caveLabel")
                            + ": "
                            + (CaveState.isAuto() ? I18n.get("gui.unboundmaps.caveAuto") : "Y=" + CaveState.fixedY());
                        var0.drawCenteredString(var2.font, var23c, var6, var28, -1727668);
                        var28 += 10;
                    }

                    if (var20) {
                        var0.drawCenteredString(
                            var2.font, "X " + var3.getBlockX() + "  Y " + var3.getBlockY() + "  Z " + var3.getBlockZ(), var6, var28, -1643021
                        );
                        var28 += 10;
                    }

                    if (var21) {
                        String var29 = var4.getBiome(var3.blockPosition())
                            .unwrapKey()
                            .map(var0x -> Component.translatable(var0x.location().toLanguageKey("biome")).getString())
                            .orElse("unknown");
                        var0.drawCenteredString(var2.font, var29, var6, var28, -4668212);
                    }
                }

                RenderSystem.disableBlend();
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            } catch (Exception var30) {
                UnboundMapsDebug.note(var30);
                // never leave render state dirty for the rest of the frame
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                RenderSystem.disableBlend();
            }
        }
    }

    /** Gray tint (ABGR) for the night dim, multiplied into the map quad vertices. */
    private static int tintFor(float var0) {
        int var1 = Mth.clamp((int)(var0 * 255.0F), 0, 255);
        return 0xFF000000 | var1 << 16 | var1 << 8 | var1;
    }

    private static void renderSquare(
        GuiGraphics var0, Matrix4f var1, int var2, int var3, int var4, float var5, double var6, double var8, int var10, int var11, float var12, int var13
    ) {
        float var14 = var2 - var4;
        float var15 = var3 - var4;
        float var16 = var2 + var4;
        float var17 = var3 + var4;
        float var18 = (float)((var6 - var5 - var10) * var12);
        float var19 = (float)((var8 - var5 - var11) * var12);
        float var20 = (float)((var6 + var5 - var10) * var12);
        float var21 = (float)((var8 + var5 - var11) * var12);
        DrawHelper.texturedQuadTinted(var1, MinimapTexture.location(), var14, var15, var16, var17, var18, var19, var20, var21, var13);
        int var22 = Mth.floor(var14);
        int var23 = Mth.floor(var15);
        int var24 = Mth.floor(var16);
        int var25 = Mth.floor(var17);
        var0.renderOutline(var22 - 2, var23 - 2, var24 + 2, var25 + 2, -267382764);
        var0.renderOutline(var22 - 1, var23 - 1, var24 + 1, var25 + 1, -267382764);
        var0.renderOutline(var22, var23, var24, var25, -1879048192);
    }

    private static void renderRound(
        Matrix4f var0, int var1, int var2, int var3, float var4, double var5, double var7, int var9, int var10, float var11, int var12
    ) {
        RenderSystem.setShaderTexture(0, MinimapTexture.location());
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexColorShader);
        BufferBuilder var13 = Tesselator.getInstance().begin(Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_TEX_COLOR);
        var13.addVertex(var0, (float)var1, (float)var2, 0.0F)
            .setUv((float)((var5 - var9) * var11), (float)((var7 - var10) * var11))
            .setColor(var12);
        int var14 = 48;

        for (int var15 = 0; var15 <= var14; var15++) {
            double var16 = var15 % var14 * ((Math.PI * 2) / var14);
            float var18 = var1 + (float)(Math.cos(var16) * var3);
            float var19 = var2 + (float)(Math.sin(var16) * var3);
            double var20 = var5 + Math.cos(var16) * var4;
            double var22 = var7 + Math.sin(var16) * var4;
            var13.addVertex(var0, var18, var19, 0.0F)
                .setUv((float)((var20 - var9) * var11), (float)((var22 - var10) * var11))
                .setColor(var12);
        }

        BufferUploader.drawWithShader(var13.buildOrThrow());
        DrawHelper.ring(var0, (float)var1, (float)var2, var3 - 1.0F, var3 + 2.5F, var14, -267382764);
    }
}
