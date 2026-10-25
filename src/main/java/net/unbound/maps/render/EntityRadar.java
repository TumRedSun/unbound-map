package net.unbound.maps.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
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
    /** face pixel rect (u, v, w, h) inside the entity texture, per entity type - FALLBACK ONLY. */
    private static final Map<EntityType<?>, int[]> FACES = buildFaces();
    /** static per-type rects that beat the model derivation (see {@link #buildOverrides()}). */
    private static final Map<EntityType<?>, int[]> OVERRIDES = buildOverrides();
    /** face rect derived from the live entity model, normalized 0..1 (u0, v0, u1, v1); NULL_FACE = none. */
    private static final Map<EntityType<?>, float[]> MODEL_FACES = new HashMap<>();
    private static final float[] NULL_FACE = new float[0];
    /** source texture -> cropped icon texture (null value = crop failed, use the name). */
    private static final Map<ResourceLocation, ResourceLocation> ICONS = new HashMap<>();
    /** texture -> cached pixel size (the old code decoded the whole PNG on every icon draw). */
    private static final Map<ResourceLocation, int[]> TEXTURE_SIZES = new HashMap<>();
    // reflection handles into the baked model parts. ModelPart.cubes/children and
    // Cube.polygons are private, and Polygon/Vertex are private nested classes, so
    // everything below goes through raw reflection (names are stable at runtime:
    // NeoForge 1.21.x runs with the official mapping names in production too).
    // NOTE: Cube.polygons is ModelPart.Polygon[] - the Polygon/Vertex field lookups
    // must use the ARRAY'S COMPONENT type. Resolving them on the array class itself
    // (as 1.9.0 did) always failed, silently disabling the whole live-model path so
    // every mob fell back to the static table (pigs got "re-corrected" to a wrong
    // rect in 1.9.0, horses had no entry at all).
    private static final Field CUBES_FIELD = field(ModelPart.class, "cubes");
    private static final Field CHILDREN_FIELD = field(ModelPart.class, "children");
    private static final Field POLYGONS_FIELD = field(ModelPart.Cube.class, "polygons");
    private static final Field NORMAL_FIELD = declaredField(polyClass(), "normal");
    private static final Field VERTICES_FIELD = declaredField(polyClass(), "vertices");
    private static final Field U_FIELD = declaredField(vertexClass(), "u");
    private static final Field V_FIELD = declaredField(vertexClass(), "v");

    private static Class<?> polyClass() {
        return POLYGONS_FIELD == null ? null : POLYGONS_FIELD.getType().getComponentType();
    }

    private static Class<?> vertexClass() {
        return VERTICES_FIELD == null ? null : VERTICES_FIELD.getType().getComponentType();
    }
    private static int iconSeq;
    private static final int DOT_HOSTILE = 0xFFE05050;
    private static final int DOT_PASSIVE = 0xFF6FE06F;
    private static final int DOT_OTHER = 0xFFB0B0B0;

    private EntityRadar() {
    }

    /**
     * Head face rects from the vanilla model boxes - only used as a fallback when
     * the live model could not be inspected. A box at texture (u,v) with depth d has
     * its front face at (u+d, v+d): humanoid 8x8x8 heads at (8,8), pig 8x8x8 at
     * (8,8), cow 8x8x6 at (6,6), sheep 6x6x8 at (8,8), wolf 6x6x4 at (4,4).
     */
    private static Map<EntityType<?>, int[]> buildFaces() {
        Map<EntityType<?>, int[]> var0 = new HashMap<>();
        square8(var0, EntityType.ZOMBIE, EntityType.ZOMBIE_VILLAGER, EntityType.DROWNED, EntityType.HUSK);
        square8(var0, EntityType.ZOMBIFIED_PIGLIN, EntityType.PIGLIN, EntityType.PIGLIN_BRUTE);
        square8(var0, EntityType.SKELETON, EntityType.STRAY, EntityType.WITHER_SKELETON, EntityType.BOGGED);
        square8(var0, EntityType.CREEPER, EntityType.WITCH, EntityType.EVOKER, EntityType.VINDICATOR);
        square8(var0, EntityType.ILLUSIONER, EntityType.PILLAGER, EntityType.VILLAGER, EntityType.WANDERING_TRADER);
        square8(var0, EntityType.ENDERMAN, EntityType.SNOW_GOLEM, EntityType.SQUID);
        square8(var0, EntityType.GLOW_SQUID, EntityType.GUARDIAN, EntityType.ELDER_GUARDIAN, EntityType.HOGLIN);
        square8(var0, EntityType.ZOGLIN, EntityType.RAVAGER, EntityType.WARDEN, EntityType.BLAZE);
        square8(var0, EntityType.PHANTOM, EntityType.PANDA);
        // quadruped head rects verified against the real 1.21.1 models/textures:
        // pig head is an 8x8x8 box at texOffs(0,0) -> face at (8,8); cow 8x8x6 -> (6,6);
        // sheep 6x6x8 -> (8,8). The old (6,6,8,8) pig rect painted a broken face.
        var0.put(EntityType.PIG, new int[]{8, 8, 8, 8});
        var0.put(EntityType.COW, new int[]{6, 6, 8, 8});
        var0.put(EntityType.MOOSHROOM, new int[]{6, 6, 8, 8});
        var0.put(EntityType.SHEEP, new int[]{8, 8, 6, 6});
        var0.put(EntityType.WOLF, new int[]{4, 4, 6, 6});
        var0.put(EntityType.CAT, new int[]{5, 5, 5, 4});
        var0.put(EntityType.OCELOT, new int[]{5, 5, 5, 4});
        var0.put(EntityType.FOX, new int[]{7, 11, 6, 6});
        var0.put(EntityType.BEE, new int[]{8, 8, 6, 6});
        var0.put(EntityType.CHICKEN, new int[]{3, 3, 4, 6});
        var0.put(EntityType.BAT, new int[]{3, 3, 3, 3});
        var0.put(EntityType.SPIDER, new int[]{40, 12, 8, 8});
        var0.put(EntityType.CAVE_SPIDER, new int[]{40, 12, 8, 8});
        var0.put(EntityType.GHAST, new int[]{16, 16, 16, 16});
        var0.put(EntityType.SLIME, new int[]{8, 24, 8, 8});
        var0.put(EntityType.MAGMA_CUBE, new int[]{8, 24, 8, 8});
        var0.put(EntityType.RABBIT, new int[]{3, 3, 4, 4});
        return var0;
    }

    /**
     * Static rects that BEAT the live model derivation: the horse family's head
     * part front face is only the featureless 6x5 muzzle bridge, while the real
     * face with both eyes spans the whole head band at (0,20)-(20,25) of the
     * 64x64 horse/donkey/mule textures (same layout for every variant).
     */
    private static Map<EntityType<?>, int[]> buildOverrides() {
        Map<EntityType<?>, int[]> var0 = new HashMap<>();
        int[] horse = new int[]{0, 20, 20, 5};
        var0.put(EntityType.HORSE, horse);
        var0.put(EntityType.DONKEY, horse);
        var0.put(EntityType.MULE, horse);
        var0.put(EntityType.SKELETON_HORSE, horse);
        var0.put(EntityType.ZOMBIE_HORSE, horse);
        return var0;
    }

    private static void square8(Map<EntityType<?>, int[]> var0, EntityType<?>... var1) {
        for (EntityType<?> var5 : var1) {
            var0.put(var5, new int[]{8, 8, 8, 8});
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
        int[] rect = null;
        if (var3 instanceof Player var10) {
            PlayerSkin var11;
            try {
                var11 = var2.getSkinManager().getInsecureSkin(var10.getGameProfile());
            } catch (Exception var12) {
                var11 = null;
            }

            if (var11 != null) {
                var8 = var11.texture();
                rect = new int[]{8, 8, 8, 8};
                var7 = faceIcon(var2, var8, 8, 8, 8, 8);
            }

            var9 = var10.getGameProfile().getName();
        } else {
            // the face rect comes from the LIVE entity model (works for vanilla AND
            // modded mobs with a standard 'head' part); the static table is only a
            // fallback for exotic models
            rect = faceRect(var2, var3);
            if (rect != null) {
                try {
                    var8 = ((EntityRenderer)var2.getEntityRenderDispatcher().getRenderer(var3)).getTextureLocation(var3);
                } catch (Exception var14) {
                    var8 = null;
                    rect = null;
                }

                if (var8 != null) {
                    var7 = faceIcon(var2, var8, rect[0], rect[1], rect[2], rect[3]);
                }
            }

            // name fallback: living creatures only - boats/minecarts/etc stay silent dots
            if (var7 == null && var8 == null && var9 == null && var3 instanceof net.minecraft.world.entity.LivingEntity) {
                var9 = var3.getType().getDescription().getString();
            }
        }

        if (var6) {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 0.6F);
        }

        float var15 = var4 - (float)ICON / 2.0F;
        float var16 = var5 - (float)ICON / 2.0F;
        if (var7 != null) {
            DrawHelper.coloredQuad(var1, var15 - 1.0F, var16 - 1.0F, var15 + (float)ICON + 1.0F, var16 + (float)ICON + 1.0F, -872415232);
            blitIcon(var1, var7, var15, var16, (float)ICON, (float)ICON);
        } else if (var8 != null && rect != null) {
            DrawHelper.coloredQuad(var1, var15 - 1.0F, var16 - 1.0F, var15 + (float)ICON + 1.0F, var16 + (float)ICON + 1.0F, -872415232);
            int[] dims = textureSize(var2, var8);
            blitFace(var1, var8, var15, var16, (float)ICON, (float)ICON, (float)rect[0], (float)rect[1], (float)rect[2], (float)rect[3], (float)dims[0], (float)dims[1]);
        } else {
            // plain colored dot, no bulky dark box around it
            drawDot(var0, var1, var4, var5, dotColor(var3));
        }

        if (var9 != null) {
            Font var17 = var2.font;
            float var18 = (float)var17.width(var9) * 0.7F;
            float var19 = var5 + (float)ICON / 2.0F + 2.0F;
            if (LabelPlacer.tryPlaceCentered(var4, var19, var18, 8.0F)) {
                drawLabel(var0, var17, var4, var19, var9);
            }
        }

        if (var6) {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }

        return true;
    }

    /**
     * Real texture dimensions, cached per texture (the old code re-decoded the
     * whole PNG on EVERY icon draw). Sources: the resource PNG when readable,
     * otherwise the registered DynamicTexture (downloaded Skin Restorer skins),
     * falling back to the modern 64x64 skin layout.
     */
    private static int[] textureSize(Minecraft var0, ResourceLocation var1) {
        int[] var2 = TEXTURE_SIZES.get(var1);
        if (var2 != null) {
            return var2;
        }
        var2 = new int[]{64, 64};
        try {
            Optional<net.minecraft.server.packs.resources.Resource> var3 = var0.getResourceManager().getResource(var1);
            if (var3.isPresent()) {
                NativeImage var4;
                try (InputStream var5 = var3.get().open()) {
                    var4 = NativeImage.read(var5);
                }

                var2 = new int[]{var4.getWidth(), var4.getHeight()};
                var4.close();
            }
        } catch (Throwable var6) {
            try {
                net.minecraft.client.renderer.texture.AbstractTexture var7 = var0.getTextureManager().getTexture(var1);
                if (var7 instanceof DynamicTexture var8 && var8.getPixels() != null) {
                    var2 = new int[]{var8.getPixels().getWidth(), var8.getPixels().getHeight()};
                }
            } catch (Throwable var9) {
            }
        }

        if (TEXTURE_SIZES.size() > 512) {
            TEXTURE_SIZES.clear();
        }

        TEXTURE_SIZES.put(var1, var2);
        return var2;
    }

    /**
     * Face pixel rect {x, y, w, h} for a mob: read from the LIVE entity model's
     * 'head' part (works for every vanilla and modded model that keeps its cubes
     * under a head part, regardless of texture size), falling back to the static
     * per-type table for exotic layouts.
     */
    private static int[] faceRect(Minecraft var0, Entity var1) {
        // 1) static override (horse family head band with both eyes)
        int[] var11 = OVERRIDES.get(var1.getType());
        if (var11 != null) {
            int[] var12 = resolveRect(var0, var1, var11);
            if (var12 != null) {
                return var12;
            }
        }

        float[] var2 = MODEL_FACES.get(var1.getType());
        if (var2 == null) {
            var2 = deriveFace(var0, var1);
            if (MODEL_FACES.size() > 512) {
                MODEL_FACES.clear();
            }
            MODEL_FACES.put(var1.getType(), var2);
        }

        if (var2.length == 4) {
            ResourceLocation var3;
            try {
                var3 = ((EntityRenderer)var0.getEntityRenderDispatcher().getRenderer(var1)).getTextureLocation(var1);
            } catch (Exception var4) {
                return null;
            }

            int[] var5 = textureSize(var0, var3);
            int var6 = Mth.clamp(Math.round(var2[0] * var5[0]), 0, var5[0] - 1);
            int var7 = Mth.clamp(Math.round(var2[1] * var5[1]), 0, var5[1] - 1);
            int var8 = Mth.clamp(Math.round((var2[2] - var2[0]) * var5[0]), 1, var5[0] - var6);
            int var9 = Mth.clamp(Math.round((var2[3] - var2[1]) * var5[1]), 1, var5[1] - var7);
            return new int[]{var6, var7, var8, var9};
        }

        int[] var10 = FACES.get(var1.getType());
        if (var10 == null) {
            return null;
        }

        return resolveRect(var0, var1, var10);
    }

    /** Clamps a static pixel rect against the entity's texture size. */
    private static int[] resolveRect(Minecraft var0, Entity var1, int[] var10) {
        try {
            ResourceLocation var11 = ((EntityRenderer)var0.getEntityRenderDispatcher().getRenderer(var1)).getTextureLocation(var1);
            int[] var12 = textureSize(var0, var11);
            int var13 = Mth.clamp(var10[0], 0, var12[0] - 1);
            int var14 = Mth.clamp(var10[1], 0, var12[1] - 1);
            int var15 = Math.min(var10[2], var12[0] - var13);
            int var16 = Math.min(var10[3], var12[1] - var14);
            return var15 > 0 && var16 > 0 ? new int[]{var13, var14, var15, var16} : null;
        } catch (Exception var17) {
            return null;
        }
    }

    /**
     * Reads the front (north) face of the model's head part and returns its
     * texture UVs normalized to 0..1: {u0, v0, u1, v1}. NULL_FACE when the model
     * has no inspectable head.
     */
    private static float[] deriveFace(Minecraft var0, Entity var1) {
        try {
            net.minecraft.client.renderer.entity.EntityRenderer<?> var2 = var0.getEntityRenderDispatcher().getRenderer(var1);
            if (var2 instanceof LivingEntityRenderer<?, ?> var3) {
                EntityModel<?> var4 = var3.getModel();
                ModelPart var5 = headPart(var4);
                if (var5 != null) {
                    float[] var6 = northFaceRect(var5, 0);
                    if (var6 != null) {
                        return var6;
                    }
                }
            }
        } catch (Throwable var7) {
        }

        return NULL_FACE;
    }

    /** Finds the head ModelPart: public hierarchical lookup first, then reflection on 'head' fields. */
    private static ModelPart headPart(EntityModel<?> var0) {
        if (var0 instanceof HierarchicalModel<?> var1) {
            try {
                Optional<ModelPart> var2 = var1.getAnyDescendantWithName("head");
                if (var2.isPresent()) {
                    return var2.get();
                }
            } catch (Throwable var3) {
            }
        }

        for (Class<?> var4 = var0.getClass(); var4 != null && var4 != Object.class; var4 = var4.getSuperclass()) {
            for (Field var5 : var4.getDeclaredFields()) {
                if (ModelPart.class.isAssignableFrom(var5.getType())) {
                    String var6 = var5.getName();
                    if (var6.equals("head") || var6.equals("realHead")) {
                        try {
                            var5.setAccessible(true);
                            return (ModelPart)var5.get(var0);
                        } catch (Throwable var7) {
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Largest north-facing face rect within the part (and, when the part itself
     * has no cubes like the wolf's 'head' wrapper, within its children). The
     * largest wins so a small nose/snout box never beats the actual face.
     * All cube/polygon/vertex access is reflective - the nested types are private.
     */
    private static float[] northFaceRect(ModelPart var0, int var1) {
        if (POLYGONS_FIELD == null || NORMAL_FIELD == null || VERTICES_FIELD == null || U_FIELD == null || V_FIELD == null) {
            return null;
        }
        List<Object> var2 = cubesOf(var0);
        if (var2 != null && !var2.isEmpty()) {
            float var3 = 0.0F;
            float[] var4 = null;

            for (Object var5 : var2) {
                Object var6;
                try {
                    var6 = POLYGONS_FIELD.get(var5);
                } catch (Throwable var7) {
                    continue;
                }

                if (var6 == null) {
                    continue;
                }

                int var8 = java.lang.reflect.Array.getLength(var6);

                for (int var9 = 0; var9 < var8; var9++) {
                    Object var10 = java.lang.reflect.Array.get(var6, var9);
                    if (var10 == null) {
                        continue;
                    }

                    try {
                        org.joml.Vector3f var11 = (org.joml.Vector3f)NORMAL_FIELD.get(var10);
                        if (var11 == null || var11.z() > -0.5F) {
                            continue;
                        }

                        Object var12 = VERTICES_FIELD.get(var10);
                        if (var12 == null) {
                            continue;
                        }

                        int var13 = java.lang.reflect.Array.getLength(var12);
                        if (var13 == 0) {
                            continue;
                        }

                        float var14 = 1.0F;
                        float var15 = 1.0F;
                        float var16 = 0.0F;
                        float var17 = 0.0F;

                        for (int var18 = 0; var18 < var13; var18++) {
                            Object var19 = java.lang.reflect.Array.get(var12, var18);
                            if (var19 == null) {
                                continue;
                            }

                            float var20 = U_FIELD.getFloat(var19);
                            float var21 = V_FIELD.getFloat(var19);
                            var14 = Math.min(var14, var20);
                            var16 = Math.max(var16, var20);
                            var15 = Math.min(var15, var21);
                            var17 = Math.max(var17, var21);
                        }

                        float var22 = (var16 - var14) * (var17 - var15);
                        if (var22 > var3) {
                            var3 = var22;
                            var4 = new float[]{var14, var15, var16, var17};
                        }
                    } catch (Throwable var23) {
                    }
                }
            }

            if (var4 != null) {
                return var4;
            }
        }

        if (var1 < 2) {
            Map<String, ModelPart> var24 = childrenOf(var0);
            if (var24 != null) {
                for (ModelPart var25 : var24.values()) {
                    float[] var26 = northFaceRect(var25, var1 + 1);
                    if (var26 != null) {
                        return var26;
                    }
                }
            }
        }

        return null;
    }

    private static Field field(Class<?> var0, String var1) {
        try {
            Field var2 = var0.getDeclaredField(var1);
            var2.setAccessible(true);
            return var2;
        } catch (Throwable var3) {
            return null;
        }
    }

    /** Field lookup on a class known only reflectively (private nested classes). */
    private static Field declaredField(Class<?> var0, String var1) {
        if (var0 == null) {
            return null;
        }
        try {
            Field var2 = var0.getDeclaredField(var1);
            var2.setAccessible(true);
            return var2;
        } catch (Throwable var3) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> cubesOf(ModelPart var0) {
        if (CUBES_FIELD == null) {
            return null;
        }
        try {
            return (List<Object>)CUBES_FIELD.get(var0);
        } catch (Throwable var1) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ModelPart> childrenOf(ModelPart var0) {
        if (CHILDREN_FIELD == null) {
            return null;
        }
        try {
            return (Map<String, ModelPart>)CHILDREN_FIELD.get(var0);
        } catch (Throwable var1) {
            return null;
        }
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
     * tiny DynamicTexture. The PNG is read from the resource manager; when the
     * texture only exists in the texture manager (downloaded skins), its
     * DynamicTexture pixels are copied instead. A failed crop is remembered and
     * the caller falls back to a direct UV blit or the mob name.
     */
    private static ResourceLocation faceIcon(Minecraft var0, ResourceLocation var1, int var2, int var3, int var4, int var5) {
        if (ICONS.containsKey(var1)) {
            return ICONS.get(var1);
        }

        ResourceLocation var6 = null;
        try {
            NativeImage var7 = null;
            Optional<net.minecraft.server.packs.resources.Resource> var8 = var0.getResourceManager().getResource(var1);
            if (var8.isPresent()) {
                try (InputStream var9 = var8.get().open()) {
                    var7 = NativeImage.read(var9);
                }
            } else {
                net.minecraft.client.renderer.texture.AbstractTexture var10 = var0.getTextureManager().getTexture(var1);
                if (var10 instanceof DynamicTexture var11 && var11.getPixels() != null) {
                    NativeImage var12 = var11.getPixels();
                    var7 = new NativeImage(NativeImage.Format.RGBA, var12.getWidth(), var12.getHeight(), false);

                    for (int var13 = 0; var13 < var12.getHeight(); var13++) {
                        for (int var14 = 0; var14 < var12.getWidth(); var14++) {
                            var7.setPixelRGBA(var14, var13, var12.getPixelRGBA(var14, var13));
                        }
                    }
                }
            }

            if (var7 != null) {
                try {
                    int var15 = var7.getWidth();
                    int var16 = var7.getHeight();
                    int var17 = Mth.clamp(var2, 0, var15 - 1);
                    int var18 = Mth.clamp(var3, 0, var16 - 1);
                    int var19 = Math.min(var4, var15 - var17);
                    int var20 = Math.min(var5, var16 - var18);
                    if (var19 > 0 && var20 > 0) {
                        NativeImage var21 = new NativeImage(NativeImage.Format.RGBA, var19, var20, false);

                        for (int var22 = 0; var22 < var20; var22++) {
                            for (int var23 = 0; var23 < var19; var23++) {
                                var21.setPixelRGBA(var23, var22, var7.getPixelRGBA(var17 + var23, var18 + var22));
                            }
                        }

                        ResourceLocation var24 = UnboundMapsMod.id("radar_icon_" + iconSeq++);
                        var0.getTextureManager().register(var24, new DynamicTexture(var21));
                        var6 = var24;
                    }
                } finally {
                    var7.close();
                }
            }
        } catch (Throwable var25) {
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
