package net.unbound.maps.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.unbound.maps.UnboundConfig;
import net.unbound.maps.UnboundMapsMod;
import org.joml.Matrix4f;

/**
 * Xaero-style entity radar for the minimap and the world map.
 *
 * <p>Head icons are cropped from the actual entity texture with NativeImage and
 * cached as small DynamicTextures, so every mob with a known head layout gets a
 * stable, correctly positioned face regardless of the texture's real size.
 * When no icon can be produced the mob's localized name is drawn instead.
 * Players always show their nickname and their real in-game skin head
 * (whatever the server sends, so Skin Restorer / custom skins work).</p>
 */
public final class EntityRadar {
    private static final int ICON = 9;
    private static final int EDGE_MARGIN = 4;
    /** face pixel rect (u, v, w, h) inside the entity texture, per entity type. */
    private static final Map<EntityType<?>, int[]> FACES = buildFaces();
    /** source texture -> cropped icon texture (null value = crop failed, use the name). */
    private static final Map<ResourceLocation, ResourceLocation> ICONS = new HashMap<>();
    private static int iconSeq;
    private static final int DOT_HOSTILE = 0xFFE05050;
    private static final int DOT_PASSIVE = 0xFF6FE06F;
    private static final int DOT_OTHER = 0xFFB0B0B0;

    private EntityRadar() {
    }

    /**
     * Head face rects from the vanilla model boxes: a box at texture (u,v) with
     * depth d has its front face at (u+d, v+d). Most heads are 8x8x8 (face at
     * 8,8), quadruped heads 6x6x6 (face at 6,6) - this is what made cows, pigs,
     * sheep etc. render as gray squares before.
     */
    private static Map<EntityType<?>, int[]> buildFaces() {
        Map<EntityType<?>, int[]> var0 = new HashMap<>();
        square8(var0, EntityType.ZOMBIE, EntityType.ZOMBIE_VILLAGER, EntityType.DROWNED, EntityType.HUSK);
        square8(var0, EntityType.ZOMBIFIED_PIGLIN, EntityType.PIGLIN, EntityType.PIGLIN_BRUTE);
        square8(var0, EntityType.SKELETON, EntityType.STRAY, EntityType.WITHER_SKELETON, EntityType.BOGGED);
        square8(var0, EntityType.CREEPER, EntityType.WITCH, EntityType.EVOKER, EntityType.VINDICATOR);
        square8(var0, EntityType.ILLUSIONER, EntityType.PILLAGER, EntityType.VILLAGER, EntityType.WANDERING_TRADER);
        square8(var0, EntityType.ENDERMAN, EntityType.PIG, EntityType.SNOW_GOLEM, EntityType.SQUID);
        square8(var0, EntityType.GLOW_SQUID, EntityType.GUARDIAN, EntityType.ELDER_GUARDIAN, EntityType.HOGLIN);
        square8(var0, EntityType.ZOGLIN, EntityType.RAVAGER, EntityType.WARDEN, EntityType.BLAZE);
        square8(var0, EntityType.PHANTOM, EntityType.PANDA);
        square6(var0, EntityType.COW, EntityType.MOOSHROOM, EntityType.SHEEP, EntityType.CAT);
        square6(var0, EntityType.OCELOT, EntityType.FOX, EntityType.POLAR_BEAR, EntityType.WOLF);
        var0.put(EntityType.BEE, new int[]{8, 8, 6, 6});
        square3(var0, EntityType.CHICKEN, EntityType.BAT);
        var0.put(EntityType.SPIDER, new int[]{8, 12, 8, 8});
        var0.put(EntityType.CAVE_SPIDER, new int[]{8, 12, 8, 8});
        var0.put(EntityType.GHAST, new int[]{9, 9, 9, 9});
        var0.put(EntityType.SLIME, new int[]{0, 0, 8, 8});
        var0.put(EntityType.MAGMA_CUBE, new int[]{0, 0, 8, 8});
        var0.put(EntityType.RABBIT, new int[]{3, 3, 4, 4});
        return var0;
    }

    private static void square8(Map<EntityType<?>, int[]> var0, EntityType<?>... var1) {
        for (EntityType<?> var5 : var1) {
            var0.put(var5, new int[]{8, 8, 8, 8});
        }
    }

    private static void square6(Map<EntityType<?>, int[]> var0, EntityType<?>... var1) {
        for (EntityType<?> var5 : var1) {
            var0.put(var5, new int[]{6, 6, 6, 6});
        }
    }

    private static void square3(Map<EntityType<?>, int[]> var0, EntityType<?>... var1) {
        for (EntityType<?> var5 : var1) {
            var0.put(var5, new int[]{3, 3, 3, 3});
        }
    }

    public static void draw(GuiGraphics var0, float var1, float var2, float var3, double var4, double var6, double var8, boolean var10) {
        if (!flag(UnboundConfig.ENTITY_RADAR, true)) {
            return;
        }
        Minecraft var11 = Minecraft.getInstance();
        LocalPlayer var12 = var11.player;
        if (var12 == null || var11.level == null) {
            return;
        }
        int var13 = radarRange();
        double var14 = (double)var13 * (double)var13;
        float var16 = var3 / (float)var4;
        Matrix4f var17 = var0.pose().last().pose();
        int var18 = 0;

        for (Entity var20 : var11.level.entitiesForRendering()) {
            if (var20 == var12 || var20.isSpectator()) {
                continue;
            }
            if (!categoryEnabled(var20)) {
                continue;
            }
            if (!heightOk(var20, var12)) {
                continue;
            }
            double var21 = var20.getX() - var6;
            double var23 = var20.getZ() - var8;
            if (var21 * var21 + var23 * var23 > var14) {
                continue;
            }
            float var25 = (float)(var21 * (double)var16);
            float var26 = (float)(var23 * (double)var16);
            float var27 = (float)Mth.length(var25, var26);
            boolean var28 = false;
            if (var10) {
                float var29 = var3 - (float)EDGE_MARGIN;
                if (var27 > var29) {
                    var25 = Mth.clamp(var25, -var29, var29);
                    var26 = Mth.clamp(var26, -var29, var29);
                    var28 = true;
                }
            } else if (var27 > var3 - (float)EDGE_MARGIN) {
                float var30 = (var3 - (float)EDGE_MARGIN) / var27;
                var25 *= var30;
                var26 *= var30;
                var28 = true;
            }

            float var31 = var1 + var25;
            float var32 = var2 + var26;
            if (drawIcon(var0, var17, var11, var20, var31, var32, var28)) {
                if (++var18 >= 48) {
                    break;
                }
            }
        }
    }

    private static boolean drawIcon(
        GuiGraphics var0, Matrix4f var1, Minecraft var2, Entity var3, float var4, float var5, boolean var6
    ) {
        ResourceLocation var7 = null;
        ResourceLocation var8 = null;
        String var9 = null;
        if (var3 instanceof Player var10) {
            PlayerSkin var11;
            try {
                var11 = var2.getSkinManager().getInsecureSkin(var10.getGameProfile());
            } catch (Exception var12) {
                var11 = null;
            }

            if (var11 != null) {
                var8 = var11.texture();
                var7 = faceIcon(var2, var8, 8, 8, 8, 8);
            }

            var9 = var10.getGameProfile().getName();
        } else {
            int[] var13 = FACES.get(var3.getType());
            if (var13 != null) {
                try {
                    var8 = ((EntityRenderer)var2.getEntityRenderDispatcher().getRenderer(var3)).getTextureLocation(var3);
                } catch (Exception var14) {
                    var8 = null;
                }

                if (var8 != null) {
                    var7 = faceIcon(var2, var8, var13[0], var13[1], var13[2], var13[3]);
                    if (var7 == null) {
                        var8 = null;
                    }
                }
            }
        }

        if (var7 == null && var8 == null && var9 == null) {
            var9 = var3.getType().getDescription().getString();
        }

        if (var6) {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 0.6F);
        }

        float var15 = var4 - (float)ICON / 2.0F;
        float var16 = var5 - (float)ICON / 2.0F;
        DrawHelper.coloredQuad(var1, var15 - 1.0F, var16 - 1.0F, var15 + (float)ICON + 1.0F, var16 + (float)ICON + 1.0F, -872415232);
        if (var7 != null) {
            blitIcon(var1, var7, var15, var16, (float)ICON, (float)ICON);
        } else if (var8 != null) {
            blitFace(var1, var8, var15, var16, (float)ICON, (float)ICON, 8.0F, 8.0F, 8.0F, 8.0F, 64.0F, 64.0F);
        } else {
            drawDot(var0, var1, var4, var5, dotColor(var3));
        }

        if (var9 != null) {
            drawLabel(var0, var2.font, var4, var5 + (float)ICON / 2.0F + 1.0F, var9);
        }

        if (var6) {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }

        return true;
    }

    private static void drawLabel(GuiGraphics var0, Font var1, float var2, float var3, String var4) {
        var0.pose().pushPose();
        var0.pose().translate(var2, var3, 0.0F);
        var0.pose().scale(0.7F, 0.7F, 1.0F);
        var0.drawString(var1, var4, -var1.width(var4) / 2, 0, -1, true);
        var0.pose().popPose();
    }

    /**
     * Crops the face rect from the entity/skin texture once and caches it as a
     * tiny DynamicTexture. Reading the real PNG makes the crop stable: texture
     * dimensions, sub-folders and modded reskins are all handled. A failed crop
     * is remembered and the caller falls back to the mob name.
     */
    private static ResourceLocation faceIcon(Minecraft var0, ResourceLocation var1, int var2, int var3, int var4, int var5) {
        if (ICONS.containsKey(var1)) {
            return ICONS.get(var1);
        }

        ResourceLocation var6 = null;
        try {
            Optional<net.minecraft.server.packs.resources.Resource> var7 = var0.getResourceManager().getResource(var1);
            if (var7.isPresent()) {
                NativeImage var8;
                try (InputStream var9 = var7.get().open()) {
                    var8 = NativeImage.read(var9);
                }

                try {
                    int var10 = var8.getWidth();
                    int var11 = var8.getHeight();
                    int var12 = Mth.clamp(var2, 0, var10 - 1);
                    int var13 = Mth.clamp(var3, 0, var11 - 1);
                    int var14 = Math.min(var4, var10 - var12);
                    int var15 = Math.min(var5, var11 - var13);
                    if (var14 > 0 && var15 > 0) {
                        NativeImage var16 = new NativeImage(NativeImage.Format.RGBA, var14, var15, false);

                        for (int var17 = 0; var17 < var15; var17++) {
                            for (int var18 = 0; var18 < var14; var18++) {
                                var16.setPixelRGBA(var18, var17, var8.getPixelRGBA(var12 + var18, var13 + var17));
                            }
                        }

                        ResourceLocation var19 = UnboundMapsMod.id("radar_icon_" + iconSeq++);
                        var0.getTextureManager().register(var19, new DynamicTexture(var16));
                        var6 = var19;
                    }
                } finally {
                    var8.close();
                }
            }
        } catch (Throwable var20) {
            var6 = null;
        }

        if (ICONS.size() > 256) {
            ICONS.clear();
        }

        ICONS.put(var1, var6);
        return var6;
    }

    /** Draws a cropped icon texture (full 0..1 UV). */
    private static void blitIcon(Matrix4f var0, ResourceLocation var1, float var2, float var3, float var4, float var5) {
        RenderSystem.setShaderTexture(0, var1);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        BufferBuilder var6 = Tesselator.getInstance().begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        var6.addVertex(var0, var2, var3, 0.0F).setUv(0.0F, 0.0F);
        var6.addVertex(var0, var2, var3 + var5, 0.0F).setUv(0.0F, 1.0F);
        var6.addVertex(var0, var2 + var4, var3 + var5, 0.0F).setUv(1.0F, 1.0F);
        var6.addVertex(var0, var2 + var4, var3, 0.0F).setUv(1.0F, 0.0F);
        BufferUploader.drawWithShader(var6.buildOrThrow());
    }

    private static void blitFace(
        Matrix4f var0,
        ResourceLocation var1,
        float var2,
        float var3,
        float var4,
        float var5,
        float var6,
        float var7,
        float var8,
        float var9,
        float var10,
        float var11
    ) {
        RenderSystem.setShaderTexture(0, var1);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        float var12 = var6 / var10;
        float var13 = var7 / var11;
        float var14 = (var6 + var8) / var10;
        float var15 = (var7 + var9) / var11;
        BufferBuilder var16 = Tesselator.getInstance().begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        var16.addVertex(var0, var2, var3, 0.0F).setUv(var12, var13);
        var16.addVertex(var0, var2, var3 + var4, 0.0F).setUv(var12, var15);
        var16.addVertex(var0, var2 + var4, var3 + var4, 0.0F).setUv(var14, var15);
        var16.addVertex(var0, var2 + var4, var3, 0.0F).setUv(var14, var13);
        BufferUploader.drawWithShader(var16.buildOrThrow());
    }

    private static void drawDot(GuiGraphics var0, Matrix4f var1, float var2, float var3, int var4) {
        DrawHelper.coloredQuad(var1, var2 - 2.5F, var3 - 2.5F, var2 + 2.5F, var3 + 2.5F, -267382764);
        DrawHelper.coloredQuad(var1, var2 - 1.5F, var3 - 1.5F, var2 + 1.5F, var3 + 1.5F, var4);
    }

    private static int dotColor(Entity var0) {
        if (var0 instanceof Enemy) {
            return DOT_HOSTILE;
        } else if (
            var0 instanceof Animal
                || var0 instanceof WaterAnimal
                || var0 instanceof TamableAnimal
                || var0 instanceof AbstractVillager
        ) {
            return DOT_PASSIVE;
        } else {
            return DOT_OTHER;
        }
    }

    private static boolean categoryEnabled(Entity var0) {
        if (var0 instanceof Player) {
            return flag(UnboundConfig.RADAR_PLAYERS, true);
        } else if (var0 instanceof Enemy) {
            return flag(UnboundConfig.RADAR_HOSTILES, true);
        } else {
            return flag(UnboundConfig.RADAR_PASSIVES, true);
        }
    }

    private static int radarRange() {
        try {
            return UnboundConfig.RADAR_RANGE.get();
        } catch (NullPointerException | IllegalStateException var0) {
            return 64;
        }
    }

    private static int verticalRange() {
        try {
            return UnboundConfig.RADAR_Y_RANGE.get();
        } catch (NullPointerException | IllegalStateException var0) {
            return 10;
        }
    }

    /** Entities far above or below the player are hidden: a bat 50 blocks down is noise. */
    private static boolean heightOk(Entity var0, Entity var1) {
        int var2 = verticalRange();
        if (var2 <= 0) {
            return true;
        } else {
            double var3 = var0.getY(0.5F) - var1.getY(0.5F);
            return Math.abs(var3) <= (double)var2;
        }
    }

    /** Entity icons on the world map: same filters, positions follow the map view. */
    public static void drawWorldMap(GuiGraphics var0, double var1, double var3, int var5, int var6, double var7, LocalPlayer var9) {
        if (!flag(UnboundConfig.ENTITY_RADAR, true)) {
            return;
        }
        Minecraft var10 = Minecraft.getInstance();
        if (var9 == null || var10.level == null) {
            return;
        }
        int var11 = radarRange();
        double var12 = (double)var11 * (double)var11;
        Matrix4f var14 = var0.pose().last().pose();
        int var15 = 0;

        for (Entity var17 : var10.level.entitiesForRendering()) {
            if (var17 == var9 || var17.isSpectator()) {
                continue;
            }
            if (!categoryEnabled(var17) || !heightOk(var17, var9)) {
                continue;
            }
            double var18 = var17.getX() - var1;
            double var20 = var17.getZ() - var3;
            if (var18 * var18 + var20 * var20 > var12) {
                continue;
            }
            float var22 = (float)(var5 / 2.0 + var18 / var7);
            float var23 = (float)(var6 / 2.0 + var20 / var7);
            if (var22 < -8.0F || var23 < -8.0F || var22 > (float)var5 + 8.0F || var23 > (float)var6 + 8.0F) {
                continue;
            }
            if (drawIcon(var0, var14, var10, var17, var22, var23, false)) {
                if (++var15 >= 100) {
                    break;
                }
            }
        }
    }

    private static boolean flag(net.neoforged.neoforge.common.ModConfigSpec.BooleanValue var0, boolean var1) {
        try {
            return var0.get();
        } catch (NullPointerException | IllegalStateException var2) {
            return var1;
        }
    }
}
