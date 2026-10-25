package net.unbound.maps.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * Minimal drawing helpers. Everything above the map is drawn with axis-aligned
 * quads (same vertex order as GuiGraphics.fill) or four-point polygons, so no
 * custom triangle winding can ever be culled or rendered inconsistently.
 */
public final class DrawHelper {
    private DrawHelper() {
    }

    public static void texturedQuad(
        Matrix4f var0, ResourceLocation var1, float var2, float var3, float var4, float var5, float var6, float var7, float var8, float var9
    ) {
        RenderSystem.setShaderTexture(0, var1);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        BufferBuilder var10 = Tesselator.getInstance().begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        var10.addVertex(var0, var2, var3, 0.0F).setUv(var6, var7);
        var10.addVertex(var0, var2, var5, 0.0F).setUv(var6, var9);
        var10.addVertex(var0, var4, var5, 0.0F).setUv(var8, var9);
        var10.addVertex(var0, var4, var3, 0.0F).setUv(var8, var7);
        BufferUploader.drawWithShader(var10.buildOrThrow());
    }

    /** Textured quad with a per-vertex ABGR tint (used for the Xaero-like night dim). */
    public static void texturedQuadTinted(
        Matrix4f var0, ResourceLocation var1, float var2, float var3, float var4, float var5, float var6, float var7, float var8, float var9, int var10
    ) {
        RenderSystem.setShaderTexture(0, var1);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        BufferBuilder var11 = Tesselator.getInstance().begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        var11.addVertex(var0, var2, var3, 0.0F).setUv(var6, var7).setColor(var10);
        var11.addVertex(var0, var2, var5, 0.0F).setUv(var6, var9).setColor(var10);
        var11.addVertex(var0, var4, var5, 0.0F).setUv(var8, var9).setColor(var10);
        var11.addVertex(var0, var4, var3, 0.0F).setUv(var8, var7).setColor(var10);
        BufferUploader.drawWithShader(var11.buildOrThrow());
    }

    public static void coloredQuad(Matrix4f var0, float var1, float var2, float var3, float var4, int var5) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder var6 = Tesselator.getInstance().begin(Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        addColored(var6, var0, var1, var2, var5);
        addColored(var6, var0, var1, var4, var5);
        addColored(var6, var0, var3, var4, var5);
        addColored(var6, var0, var3, var2, var5);
        BufferUploader.drawWithShader(var6.buildOrThrow());
    }

    /**
     * Draws a convex four-point polygon (given clockwise in GUI coordinates,
     * where Y grows downward - the same orientation GuiGraphics.fill uses).
     * Non-finite coordinates are skipped: a single NaN vertex (e.g. from a
     * corrupted entity rotation) used to rasterize as random "ghost" rectangles
     * on some drivers - this was the recurring UI artifact.
     */
    public static void poly4(Matrix4f var0, float[] var1, int var2) {
        for (float v : var1) {
            if (!Float.isFinite(v)) {
                return;
            }
        }
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.disableCull();
        BufferBuilder var3 = Tesselator.getInstance().begin(Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        addColored(var3, var0, var1[0], var1[1], var2);
        addColored(var3, var0, var1[2], var1[3], var2);
        addColored(var3, var0, var1[4], var1[5], var2);
        addColored(var3, var0, var1[6], var1[7], var2);
        BufferUploader.drawWithShader(var3.buildOrThrow());
        RenderSystem.enableCull();
    }

    public static void disc(Matrix4f var0, float var1, float var2, float var3, int var4, int var5) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder var6 = Tesselator.getInstance().begin(Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        var6.addVertex(var0, var1, var2, 0.0F).setColor(var5);

        for (int var7 = 0; var7 <= var4; var7++) {
            double var8 = var7 % var4 * ((Math.PI * 2) / var4);
            var6.addVertex(var0, var1 + (float)Math.cos(var8) * var3, var2 + (float)Math.sin(var8) * var3, 0.0F).setColor(var5);
        }

        BufferUploader.drawWithShader(var6.buildOrThrow());
    }

    public static void ring(Matrix4f var0, float var1, float var2, float var3, float var4, int var5, int var6) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder var7 = Tesselator.getInstance().begin(Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);

        for (int var8 = 0; var8 <= var5; var8++) {
            double var9 = var8 % var5 * ((Math.PI * 2) / var5);
            float var11 = (float)Math.cos(var9);
            float var12 = (float)Math.sin(var9);
            var7.addVertex(var0, var1 + var11 * var4, var2 + var12 * var4, 0.0F).setColor(var6);
            var7.addVertex(var0, var1 + var11 * var3, var2 + var12 * var3, 0.0F).setColor(var6);
        }

        BufferUploader.drawWithShader(var7.buildOrThrow());
    }

    /**
     * The "you are here" arrow (Xaero-style): a white kite with a dark outline
     * and a soft shadow, pointing along the player's yaw. Built from two
     * four-point polygons in the proven fill orientation, so it is always
     * visible regardless of the current cull state.
     *
     * @param yaw player rotation; 0 = south (+Z), 90 = west (-X)
     */
    public static void playerArrow(Matrix4f var0, float var1, float var2, float var3) {
        if (!Float.isFinite(var3)) {
            return;
        }
        double var4 = Math.toRadians(var3);
        float var6 = (float)(-Math.sin(var4));
        float var7 = (float)Math.cos(var4);
        float var8 = -var7;
        float var9 = var6;
        drawKite(var0, var1, var2, var6, var7, var8, var9, 12.0F, 5.0F, 1.5F, 1711276032);
        drawKite(var0, var1, var2, var6, var7, var8, var9, 11.0F, 4.6F, 1.0F, -436207616);
        drawKite(var0, var1, var2, var6, var7, var8, var9, 10.0F, 4.2F, 0.0F, -1);
    }

    private static void drawKite(
        Matrix4f var0, float var1, float var2, float var3, float var4, float var5, float var6, float var7, float var8, float var9, int var10
    ) {
        float var11 = var1 + var3 * var7;
        float var12 = var2 + var4 * var7;
        float var13 = var1 - var3 * var9;
        float var14 = var2 - var4 * var9;
        float var15 = var1 - var5 * var8;
        float var16 = var2 - var6 * var8;
        float var17 = var1 + var5 * var8;
        float var18 = var2 + var6 * var8;
        poly4(var0, new float[]{var11, var12, var15, var16, var13, var14, var17, var18}, var10);
    }

    /** Rectangle outline drawn as four thin quads, crisp at any zoom. */
    public static void outline(Matrix4f var0, float var1, float var2, float var3, float var4, float var5, int var6) {
        if (!Float.isFinite(var1) || !Float.isFinite(var2) || !Float.isFinite(var3) || !Float.isFinite(var4)) {
            return;
        }
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder var7 = Tesselator.getInstance().begin(Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        quad(var7, var0, var1, var2, var3, var2 + var5, var6);
        quad(var7, var0, var1, var4 - var5, var3, var4, var6);
        quad(var7, var0, var1, var2 + var5, var1 + var5, var4 - var5, var6);
        quad(var7, var0, var3 - var5, var2 + var5, var3, var4 - var5, var6);
        BufferUploader.drawWithShader(var7.buildOrThrow());
    }

    private static void quad(BufferBuilder var0, Matrix4f var1, float var2, float var3, float var4, float var5, int var6) {
        addColored(var0, var1, var2, var3, var6);
        addColored(var0, var1, var2, var5, var6);
        addColored(var0, var1, var4, var5, var6);
        addColored(var0, var1, var4, var3, var6);
    }

    private static void addColored(BufferBuilder var0, Matrix4f var1, float var2, float var3, int var4) {
        int var5 = var4 >>> 24 & 0xFF;
        int var6 = var4 >>> 16 & 0xFF;
        int var7 = var4 >>> 8 & 0xFF;
        int var8 = var4 & 0xFF;
        var0.addVertex(var1, var2, var3, 0.0F).setColor(var6, var7, var8, var5);
    }
}
