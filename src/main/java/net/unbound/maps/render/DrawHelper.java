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

    public static void coloredQuad(Matrix4f var0, float var1, float var2, float var3, float var4, int var5) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder var6 = Tesselator.getInstance().begin(Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        addColored(var6, var0, var1, var2, var5);
        addColored(var6, var0, var1, var4, var5);
        addColored(var6, var0, var3, var4, var5);
        addColored(var6, var0, var3, var2, var5);
        BufferUploader.drawWithShader(var6.buildOrThrow());
    }

    public static void coloredTriangle(Matrix4f var0, float var1, float var2, float var3, float var4, float var5, float var6, int var7) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder var8 = Tesselator.getInstance().begin(Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        addColored(var8, var0, var1, var2, var7);
        addColored(var8, var0, var3, var4, var7);
        addColored(var8, var0, var5, var6, var7);
        BufferUploader.drawWithShader(var8.buildOrThrow());
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

    public static void playerArrow(Matrix4f var0, float var1, float var2, float var3) {
        double var4 = Math.toRadians(var3);
        float var6 = (float)(-Math.sin(var4));
        float var7 = (float)Math.cos(var4);
        arrowShape(var0, var1, var2, var6, var7, 11.5F, 8.0F, 1711276032);
        arrowShape(var0, var1, var2, var6, var7, 10.0F, 6.9F, -251657176);
        arrowShape(var0, var1, var2, var6, var7, 8.0F, 5.4F, -1);
    }

    /** Rectangle outline drawn as four thin quads, crisp at any zoom. */
    public static void outline(Matrix4f var0, float var1, float var2, float var3, float var4, float var5, int var6) {
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

    private static void arrowShape(Matrix4f var0, float var1, float var2, float var3, float var4, float var5, float var6, int var7) {
        double var8 = Math.toRadians(140.0);
        float var10 = (float)(var3 * Math.cos(var8) - var4 * Math.sin(var8));
        float var11 = (float)(var3 * Math.sin(var8) + var4 * Math.cos(var8));
        coloredTriangle(var0, var1 + var3 * var5, var2 + var4 * var5, var1 + var10 * var6, var2 + var11 * var6, var1 - var10 * var6, var2 - var11 * var6, var7);
    }

    private static void addColored(BufferBuilder var0, Matrix4f var1, float var2, float var3, int var4) {
        int var5 = var4 >>> 24 & 0xFF;
        int var6 = var4 >>> 16 & 0xFF;
        int var7 = var4 >>> 8 & 0xFF;
        int var8 = var4 & 0xFF;
        var0.addVertex(var1, var2, var3, 0.0F).setColor(var6, var7, var8, var5);
    }
}
