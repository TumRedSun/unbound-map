package net.unbound.maps.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.unbound.maps.UnboundConfig;
import net.unbound.maps.waypoints.Waypoint;
import net.unbound.maps.waypoints.WaypointManager;
import org.joml.Matrix4f;

public final class WorldBeaconRenderer {
    private WorldBeaconRenderer() {
    }

    public static void render(RenderLevelStageEvent var0) {
        Minecraft var1 = Minecraft.getInstance();
        LocalPlayer var2 = var1.player;
        ClientLevel var3 = var1.level;
        if (var2 != null && var3 != null) {
            boolean var4 = UnboundConfig.BEAMS_UNLIMITED.get();
            int var5 = var4 ? Integer.MAX_VALUE : UnboundConfig.BEAM_RENDER_DISTANCE.get();
            if (var5 > 0) {
                List<Waypoint> var6 = WaypointManager.currentWaypoints();
                if (!var6.isEmpty()) {
                    int var7 = var3.getMaxBuildHeight();
                    int var8 = var3.getMinBuildHeight();
                    Vec3 var9 = var0.getCamera().getPosition();
                    PoseStack var10 = var0.getPoseStack();
                    var10.pushPose();
                    var10.translate(-var9.x, -var9.y, -var9.z);
                    Matrix4f var11 = var10.last().pose();
                    RenderSystem.setShader(GameRenderer::getPositionColorShader);
                    RenderSystem.enableBlend();
                    RenderSystem.defaultBlendFunc();
                    RenderSystem.depthMask(false);
                    RenderSystem.disableCull();
                    boolean var12 = var4 || UnboundConfig.BEAM_SEE_THROUGH.get();
                    if (var12) {
                        RenderSystem.disableDepthTest();
                    }

                    try {
                        BufferBuilder var13 = Tesselator.getInstance().begin(Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
                        int var14 = 0;
                        double var15 = var5 == Integer.MAX_VALUE ? Double.MAX_VALUE : (double)var5 * var5;

                        for (Waypoint var18 : var6) {
                            if (var18.beacon) {
                                double var19 = var18.x + 0.5 - var9.x;
                                double var21 = var18.z + 0.5 - var9.z;
                                if (!(var19 * var19 + var21 * var21 > var15)) {
                                    float var23 = var18.x + 0.5F;
                                    float var24 = var18.z + 0.5F;
                                    float var25 = Math.max(var18.y - 1, var8);
                                    float var26 = var7;
                                    float var27 = 0.35F;
                                    int var28 = var18.colorARGB >>> 16 & 0xFF;
                                    int var29 = var18.colorARGB >>> 8 & 0xFF;
                                    int var30 = var18.colorARGB & 0xFF;
                                    int var31 = 70;
                                    vertex(var13, var11, var23 - var27, var25, var24, var28, var29, var30, var31);
                                    vertex(var13, var11, var23 - var27, var26, var24, var28, var29, var30, var31);
                                    vertex(var13, var11, var23 + var27, var26, var24, var28, var29, var30, var31);
                                    vertex(var13, var11, var23 + var27, var25, var24, var28, var29, var30, var31);
                                    vertex(var13, var11, var23, var25, var24 - var27, var28, var29, var30, var31);
                                    vertex(var13, var11, var23, var26, var24 - var27, var28, var29, var30, var31);
                                    vertex(var13, var11, var23, var26, var24 + var27, var28, var29, var30, var31);
                                    vertex(var13, var11, var23, var25, var24 + var27, var28, var29, var30, var31);
                                    var14++;
                                }
                            }
                        }

                        if (var14 > 0) {
                            BufferUploader.drawWithShader(var13.buildOrThrow());
                        }
                    } finally {
                        if (var12) {
                            RenderSystem.enableDepthTest();
                        }

                        RenderSystem.enableCull();
                        RenderSystem.depthMask(true);
                        RenderSystem.disableBlend();
                        var10.popPose();
                    }
                }
            }
        }
    }

    private static void vertex(BufferBuilder var0, Matrix4f var1, float var2, float var3, float var4, int var5, int var6, int var7, int var8) {
        var0.addVertex(var1, var2, var3, var4).setColor(var5, var6, var7, var8);
    }
}
