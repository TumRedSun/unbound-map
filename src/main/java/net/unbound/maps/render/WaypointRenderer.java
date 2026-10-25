package net.unbound.maps.render;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.unbound.maps.waypoints.Waypoint;
import net.unbound.maps.waypoints.WaypointManager;

public final class WaypointRenderer {
    private static final int MAX_MINIMAP_MARKERS = 16;

    private WaypointRenderer() {
    }

    public static void drawMinimapMarkers(GuiGraphics var0, float var1, float var2, float var3, float var4, double var5, double var7, boolean var9) {
        List<Waypoint> var10 = WaypointManager.currentWaypoints();
        if (!var10.isEmpty()) {
            Font var11 = Minecraft.getInstance().font;
            List<Waypoint> var12 = new ArrayList<>(var10);
            var12.sort(Comparator.comparingDouble(var4x -> dist2(var4x, var5, var7)));
            float var13 = var3 / var4;
            int var14 = 0;

            for (Waypoint var16 : var12) {
                if (var14 >= 16) {
                    break;
                }

                float var17 = (float)(var16.x + 0.5 - var5);
                float var18 = (float)(var16.z + 0.5 - var7);
                float var19 = var17 * var13;
                float var20 = var18 * var13;
                float var21 = (float)Mth.length(var19, var20);
                if (var9) {
                    float var22 = var3 - 7.0F;
                    var19 = Mth.clamp(var19, -var22, var22);
                    var20 = Mth.clamp(var20, -var22, var22);
                } else if (var21 > var3 - 7.0F) {
                    float var25 = (var3 - 7.0F) / var21;
                    var19 *= var25;
                    var20 *= var25;
                }

                float var26 = var1 + var19;
                float var23 = var2 + var20;
                // slim Xaero-style marker: colored square + thin dark outline
                var0.fill((int)var26 - 2, (int)var23 - 2, (int)var26 + 2, (int)var23 + 2, var16.colorARGB);
                var0.renderOutline((int)var26 - 3, (int)var23 - 3, 6, 6, -1879048192);
                var14++;
            }

            // labels pass second so they can be placed without overlapping each other
            int var15b = 0;
            for (Waypoint var16b : var12) {
                if (var15b >= 16) {
                    break;
                }
                float var17b = (float)(var16b.x + 0.5 - var5);
                float var18b = (float)(var16b.z + 0.5 - var7);
                float var19b = var17b * var13;
                float var20b = var18b * var13;
                float var21b = (float)Mth.length(var19b, var20b);
                if (var9) {
                    float var22b = var3 - 7.0F;
                    var19b = Mth.clamp(var19b, -var22b, var22b);
                    var20b = Mth.clamp(var20b, -var22b, var22b);
                } else if (var21b > var3 - 7.0F) {
                    float var25b = (var3 - 7.0F) / var21b;
                    var19b *= var25b;
                    var20b *= var25b;
                }

                float var26b = var1 + var19b;
                float var23b = var2 + var20b;
                float var27 = (float)var11.width(var16b.name) * 0.7F;
                float var28 = var23b - 15.0F;
                if (LabelPlacer.tryPlaceCentered(var26b, var28, var27, 8.0F)) {
                    PoseStack var24 = var0.pose();
                    var24.pushPose();
                    var24.translate(var26b, var28, 0.0F);
                    var24.scale(0.7F, 0.7F, 1.0F);
                    var0.drawString(var11, var16b.name, -var11.width(var16b.name) / 2, 0, -1, true);
                    var24.popPose();
                }
                var15b++;
            }
        }
    }

    public static void drawWorldMapMarkers(GuiGraphics var0, double var1, double var3, double var5, double var7, double var9) {
        List<Waypoint> var11 = WaypointManager.currentWaypoints();
        if (!var11.isEmpty()) {
            Font var12 = Minecraft.getInstance().font;

            for (Waypoint var14 : var11) {
                double var15 = var5 + (var14.x + 0.5 - var1) / var9;
                double var17 = var7 + (var14.z + 0.5 - var3) / var9;
                if (!Double.isFinite(var15) || !Double.isFinite(var17)) {
                    continue;
                }
                if (!(var15 < -24.0) && !(var17 < -24.0) && !(var15 > var5 * 2.0 + 24.0) && !(var17 > var7 * 2.0 + 24.0)) {
                    int var19 = Mth.floor(var15);
                    int var20 = Mth.floor(var17);
                    var0.fill(var19 - 2, var20 - 2, var19 + 3, var20 + 3, var14.colorARGB);
                    var0.renderOutline(var19 - 3, var20 - 3, 7, 7, -1879048192);
                    String var21 = var14.name + " (" + (int)Math.sqrt(dist2(var14, var1, var3)) + " m)";
                    float var22 = (float)var12.width(var21);
                    if (LabelPlacer.tryPlaceCentered(var19 + 0.5F, var20 - 13.0F, var22, 10.0F)) {
                        var0.drawString(var12, var21, var19 - var12.width(var21) / 2, var20 - 12, -1, true);
                    }
                }
            }
        }
    }

    private static double dist2(Waypoint var0, double var1, double var3) {
        double var5 = var0.x - var1;
        double var7 = var0.z - var3;
        return var5 * var5 + var7 * var7;
    }
}
