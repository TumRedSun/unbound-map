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
    /** Enlarged minimap state, toggled with the (default Z) key. */
    private static boolean big;
    /** Half-range of the CURRENT view in blocks (follows zoom and the enlarged mode). */
    private static float viewBlocks = ZOOM_LEVELS[1];
    public static final Layer LAYER = MinimapHud::render;

    private MinimapHud() {
    }

    /** Z key: show a much larger minimap (centered or in the same corner, per config). */
    public static void toggleBig() {
        big = !big;
    }

    public static boolean isBig() {
        return big;
    }

    /** Half-range of what the HUD currently shows, in blocks (minimap zoom + enlarged mode). */
    public static float currentViewBlocks() {
        return viewBlocks;
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
                int normalHalf = var5;
                if (big) {
                    // enlarged map: the SAME blocks-per-pixel scale, just more of the
                    // world inside the bigger frame (the old code kept the block range
                    // and stretched it, which zoomed IN instead of showing more area)
                    int var5b = Math.min(var0.guiWidth(), var0.guiHeight()) / 2 - 16;
                    var5 = Math.min(Math.max(var5, var5b), 480);
                    float ratio = var5 / (float) normalHalf;
                    viewBlocks = Math.min(ZOOM_LEVELS[zoomIndex] * ratio, 246.0F);
                } else {
                    viewBlocks = ZOOM_LEVELS[zoomIndex];
                }
                float var8 = viewBlocks;

                // anchor: the configured corner, or the screen center for the
                // enlarged mode when bigMapCentered is on
                UnboundConfig.Anchor var5c = UnboundConfig.MINIMAP_ANCHOR.get();
                boolean var5d = big && UnboundConfig.MINIMAP_BIG_CENTERED.get();
                int var6 = var5d
                    ? var0.guiWidth() / 2
                    : var5c == UnboundConfig.Anchor.TOP_LEFT || var5c == UnboundConfig.Anchor.BOTTOM_LEFT
                        ? var5 + 8
                        : var0.guiWidth() - var5 - 8;
                int var7 = var5d
                    ? var0.guiHeight() / 2
                    : var5c == UnboundConfig.Anchor.TOP_LEFT || var5c == UnboundConfig.Anchor.TOP_RIGHT
                        ? var5 + 8
                        : var0.guiHeight() - var5 - 8;
                boolean var9 = UnboundConfig.MINIMAP_SHAPE.get() == UnboundConfig.Shape.SQUARE;
                double var10 = var3.getX();
                double var12 = var3.getZ();
                int var14 = MinimapTexture.anchorX();
                int var15 = MinimapTexture.anchorZ();
                float var16 = 0.001953125F;
                Matrix4f var17 = var0.pose().last().pose();
                // gentle night dim straight in the map vertices, driven by the same
                // nightDimStrength the world map uses (0 = constant Xaero colors);
                // cave view is exempt: it is lit by block light, not by the sun
                int var18 = tintFor(MapTileCache.nightFactor(MapTileCache.isMiniCave()));
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                if (var9) {
                    renderSquare(var17, var6, var7, var5, var8, var10, var12, var14, var15, var16, var18);
                } else {
                    renderRound(var17, var6, var7, var5, var8, var10, var12, var14, var15, var16, var18);
                }

                // map texture drawn through the immediate path - flush before the
                // batched labels so ordering and GL state stay deterministic
                var0.flush();
                // compute the info panel position FIRST: with bottom anchors it
                // flips above the map and the N label must move on top of it
                boolean var30 = UnboundConfig.SHOW_COORDS.get();
                boolean var31 = UnboundConfig.SHOW_BIOME.get();
                boolean var20 = MapTileCache.isMiniCave();
                int var21 = (var30 ? 1 : 0) + (var31 ? 1 : 0) + (var20 ? 1 : 0);
                int var25 = var7 + var5 + 7;
                int var23 = var21 * 10 + 4;
                boolean panelAbove = false;
                if (var21 > 0 && var25 + var23 > var0.guiHeight() - 2) {
                    // bottom-anchored maps put the info panel ABOVE the map
                    var25 = Math.max(2, var7 - var5 - 7 - var23);
                    panelAbove = true;
                }
                int nLabelY = panelAbove ? var25 - 11 : var7 - var5 - 11;
                var0.drawString(var2.font, "N", var6 - var2.font.width("N") / 2, Math.max(1, nLabelY), -4668212, true);
                WaypointRenderer.drawMinimapMarkers(var0, var6, var7, var5, var8, var10, var12, var9);
                EntityRadar.draw(var0, var6, var7, var5, var8, var10, var12, var9);
                DrawHelper.playerArrow(var17, var6, var7, var3.getYRot());
                var0.flush();
                if (var21 > 0) {
                    int var22 = 2 * var5 + 6;
                    int var24 = Mth.clamp(var6 - var5 - 3, 2, Math.max(2, var0.guiWidth() - var22 - 2));
                    var0.fill(var24, var25, var24 + var22, var25 + var23, -1945104364);
                    // renderOutline takes (x, y, WIDTH, HEIGHT) in 1.21.1
                    var0.renderOutline(var24, var25, var22, var23, 1711276032);
                    int var26 = var25 + 3;
                    if (var20) {
                        String var27 = I18n.get("gui.unboundmaps.caveLabel")
                            + ": "
                            + (CaveState.isPinned() && !CaveState.isAuto()
                                ? "Y=" + CaveState.fixedY()
                                : I18n.get("gui.unboundmaps.caveAuto"));
                        var0.drawCenteredString(var2.font, var27, var6, var26, -1727668);
                        var26 += 10;
                    }

                    if (var30) {
                        var0.drawCenteredString(
                            var2.font, "X " + var3.getBlockX() + "  Y " + var3.getBlockY() + "  Z " + var3.getBlockZ(), var6, var26, -1643021
                        );
                        var26 += 10;
                    }

                    if (var31) {
                        String var28 = var4.getBiome(var3.blockPosition())
                            .unwrapKey()
                            .map(var0x -> Component.translatable(var0x.location().toLanguageKey("biome")).getString())
                            .orElse("unknown");
                        var0.drawCenteredString(var2.font, var28, var6, var26, -4668212);
                    }
                }

                RenderSystem.disableBlend();
            } catch (Exception var29) {
                UnboundMapsDebug.note(var29);
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
        Matrix4f var0, int var1, int var2, int var3, float var4, double var5, double var6, int var8, int var9, float var10, int var11
    ) {
        float var12 = var1 - var3;
        float var13 = var2 - var3;
        float var14 = var1 + var3;
        float var15 = var2 + var3;
        float var16 = (float)((var5 - var4 - var8) * var10);
        float var17 = (float)((var6 - var4 - var9) * var10);
        float var18 = (float)((var5 + var4 - var8) * var10);
        float var19 = (float)((var6 + var4 - var9) * var10);
        DrawHelper.texturedQuadTinted(var0, MinimapTexture.location(), var12, var13, var14, var15, var16, var17, var18, var19, var11);
        int var20 = Mth.floor(var12);
        int var21 = Mth.floor(var13);
        int var22 = Mth.floor(var14);
        int var23 = Mth.floor(var15);
        Minecraft var24 = Minecraft.getInstance();
        GuiGraphics var25 = new GuiGraphics(var24, var24.renderBuffers().bufferSource());
        // renderOutline takes (x, y, WIDTH, HEIGHT) in 1.21.1
        var25.renderOutline(var20 - 2, var21 - 2, var22 - var20 + 4, var23 - var21 + 4, -267382764);
        var25.renderOutline(var20 - 1, var21 - 1, var22 - var20 + 2, var23 - var21 + 2, -267382764);
        var25.renderOutline(var20, var21, var22 - var20, var23 - var21, -1879048192);
        var25.flush();
    }

    private static void renderRound(Matrix4f var0, int var1, int var2, int var3, float var4, double var5, double var6, int var8, int var9, float var10, int var11) {
        RenderSystem.setShaderTexture(0, MinimapTexture.location());
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexColorShader);
        BufferBuilder var12 = Tesselator.getInstance().begin(Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_TEX_COLOR);
        var12.addVertex(var0, (float)var1, (float)var2, 0.0F)
            .setUv((float)((var5 - var8) * var10), (float)((var6 - var9) * var10))
            .setColor(var11);
        int var13 = 48;

        for (int var14 = 0; var14 <= var13; var14++) {
            double var15 = var14 % var13 * ((Math.PI * 2) / var13);
            float var17 = var1 + (float)(Math.cos(var15) * var3);
            float var18 = var2 + (float)(Math.sin(var15) * var3);
            double var19 = var5 + Math.cos(var15) * var4;
            double var21 = var6 + Math.sin(var15) * var4;
            var12.addVertex(var0, var17, var18, 0.0F)
                .setUv((float)((var19 - var8) * var10), (float)((var21 - var9) * var10))
                .setColor(var11);
        }

        BufferUploader.drawWithShader(var12.buildOrThrow());
        DrawHelper.ring(var0, (float)var1, (float)var2, var3 - 1.0F, var3 + 2.5F, var13, -267382764);
    }
}
