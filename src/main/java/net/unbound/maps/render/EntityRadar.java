package net.unbound.maps.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.monster.Enemy;
import net.unbound.maps.UnboundConfig;
import org.joml.Matrix4f;

/**
 * Xaero-style entity radar for the minimap: player heads from their skins,
 * mob heads cropped from the entity textures where the face layout is known,
 * category-colored dots for everything else.
 */
public final class EntityRadar {
    private static final int ICON = 8;
    private static final int EDGE_MARGIN = 4;
    private static final Map<EntityType<?>, long[]> FACES = buildFaces();
    private static final int DOT_HOSTILE = 0xFFE05050;
    private static final int DOT_PASSIVE = 0xFF6FE06F;
    private static final int DOT_OTHER = 0xFFB0B0B0;

    private EntityRadar() {
    }

    /** face u,v + texture w,h packed into one long[4] per entity type. */
    private static Map<EntityType<?>, long[]> buildFaces() {
        Map<EntityType<?>, long[]> var0 = new HashMap<>();
        humanoid(var0, EntityType.ZOMBIE, 64L, 64L);
        humanoid(var0, EntityType.ZOMBIE_VILLAGER, 64L, 64L);
        humanoid(var0, EntityType.DROWNED, 64L, 64L);
        humanoid(var0, EntityType.HUSK, 64L, 64L);
        humanoid(var0, EntityType.ZOMBIFIED_PIGLIN, 64L, 64L);
        humanoid(var0, EntityType.PIGLIN, 64L, 64L);
        humanoid(var0, EntityType.PIGLIN_BRUTE, 64L, 64L);
        humanoid(var0, EntityType.SKELETON, 64L, 32L);
        humanoid(var0, EntityType.STRAY, 64L, 32L);
        humanoid(var0, EntityType.WITHER_SKELETON, 64L, 32L);
        humanoid(var0, EntityType.BOGGED, 64L, 64L);
        humanoid(var0, EntityType.CREEPER, 64L, 32L);
        humanoid(var0, EntityType.WITCH, 64L, 64L);
        humanoid(var0, EntityType.EVOKER, 64L, 64L);
        humanoid(var0, EntityType.VINDICATOR, 64L, 64L);
        humanoid(var0, EntityType.ILLUSIONER, 64L, 64L);
        humanoid(var0, EntityType.PILLAGER, 64L, 64L);
        humanoid(var0, EntityType.VILLAGER, 64L, 64L);
        humanoid(var0, EntityType.WANDERING_TRADER, 64L, 64L);
        humanoid(var0, EntityType.ENDERMAN, 64L, 32L);
        humanoid(var0, EntityType.SNOW_GOLEM, 64L, 64L);
        humanoid(var0, EntityType.PIG, 64L, 32L);
        humanoid(var0, EntityType.COW, 64L, 32L);
        humanoid(var0, EntityType.MOOSHROOM, 64L, 32L);
        humanoid(var0, EntityType.SHEEP, 64L, 32L);
        humanoid(var0, EntityType.CHICKEN, 64L, 32L);
        humanoid(var0, EntityType.WOLF, 64L, 32L);
        face(var0, EntityType.SPIDER, 8L, 12L, 64L, 32L);
        face(var0, EntityType.CAVE_SPIDER, 8L, 12L, 64L, 32L);
        return var0;
    }

    private static void humanoid(Map<EntityType<?>, long[]> var0, EntityType<?> var1, long var2, long var3) {
        face(var0, var1, 8L, 8L, var2, var3);
    }

    private static void face(Map<EntityType<?>, long[]> var0, EntityType<?> var1, long var2, long var3, long var4, long var5) {
        var0.put(var1, new long[]{var2, var3, var4, var5});
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
        long[] var7 = null;
        ResourceLocation var8 = null;
        int var9 = dotColor(var3);
        if (var3 instanceof net.minecraft.world.entity.player.Player var10) {
            PlayerSkin var11 = var2.getSkinManager().getInsecureSkin(var10.getGameProfile());
            if (var11 != null) {
                var8 = var11.texture();
                var7 = new long[]{8L, 8L, 64L, 64L};
            }
        } else {
            var7 = FACES.get(var3.getType());
            if (var7 != null) {
                try {
                    var8 = ((net.minecraft.client.renderer.entity.EntityRenderer)var2.getEntityRenderDispatcher().getRenderer(var3))
                        .getTextureLocation(var3);
                } catch (Exception var12) {
                    var7 = null;
                }
            }
        }

        if (var7 == null || var8 == null) {
            drawDot(var0, var1, var4, var5, var9);
            return true;
        } else {
            if (var6) {
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 0.6F);
            }
            blitFace(
                var1,
                var8,
                var4 - (float)ICON / 2.0F,
                var5 - (float)ICON / 2.0F,
                (float)ICON,
                (float)var7[0],
                (float)var7[1],
                8.0F,
                8.0F,
                (float)var7[2],
                (float)var7[3]
            );
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            return true;
        }
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
        float var10
    ) {
        RenderSystem.setShaderTexture(0, var1);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        float var11 = var5 / var9;
        float var12 = var6 / var10;
        float var13 = (var5 + var7) / var9;
        float var14 = (var6 + var8) / var10;
        BufferBuilder var15 = Tesselator.getInstance().begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        var15.addVertex(var0, var2, var3, 0.0F).setUv(var11, var12);
        var15.addVertex(var0, var2, var3 + var4, 0.0F).setUv(var11, var14);
        var15.addVertex(var0, var2 + var4, var3 + var4, 0.0F).setUv(var13, var14);
        var15.addVertex(var0, var2 + var4, var3, 0.0F).setUv(var13, var12);
        BufferUploader.drawWithShader(var15.buildOrThrow());
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
        if (var0 instanceof net.minecraft.world.entity.player.Player) {
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

    private static boolean flag(net.neoforged.neoforge.common.ModConfigSpec.BooleanValue var0, boolean var1) {
        try {
            return var0.get();
        } catch (NullPointerException | IllegalStateException var2) {
            return var1;
        }
    }
}
