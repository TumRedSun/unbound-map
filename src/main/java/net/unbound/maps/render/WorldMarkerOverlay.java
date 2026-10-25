package net.unbound.maps.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw.Layer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.unbound.maps.UnboundConfig;
import net.unbound.maps.UnboundMapsDebug;
import net.unbound.maps.util.ScreenProjector;
import net.unbound.maps.waypoints.Waypoint;
import net.unbound.maps.waypoints.WaypointManager;

public final class WorldMarkerOverlay {
    private static final int MAX_LABELS = 12;
    private static final double MIN_DISTANCE = 4.0;
    private static final int MARGIN_X = 14;
    private static final int MARGIN_TOP = 22;
    private static final int MARGIN_BOTTOM = 34;
    public static final Layer LAYER = WorldMarkerOverlay::render;

    private WorldMarkerOverlay() {
    }

    private static void render(GuiGraphics var0, DeltaTracker var1) {
        Minecraft var2 = Minecraft.getInstance();
        LocalPlayer var3 = var2.player;
        ClientLevel var4 = var2.level;
        if (var3 != null && var4 != null && var2.screen == null && UnboundConfig.SHOW_MARKERS_ON_SCREEN.get()) {
            try {
                List<Waypoint> var5 = WaypointManager.currentWaypoints();
                if (var5.isEmpty()) {
                    return;
                }

                Camera var6 = var2.gameRenderer.getMainCamera();
                Vec3 var7 = var6.getPosition();
                float var8 = var6.getYRot();
                float var9 = var6.getXRot();
                double var10 = var2.options.fov().get();
                int var12 = var0.guiWidth();
                int var13 = var0.guiHeight();
                Font var14 = var2.font;
                List<Waypoint> var16 = new ArrayList<>(var5);
                var16.sort(Comparator.comparingDouble(var1x -> {
                    double var2x = var1x.x + 0.5 - var7.x;
                    double var4x = var1x.y + 0.5 - var7.y;
                    double var6x = var1x.z + 0.5 - var7.z;
                    return var2x * var2x + var4x * var4x + var6x * var6x;
                }));
                int var17 = 0;
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();

                for (Waypoint var19 : var16) {
                    if (var17 >= 12) {
                        break;
                    }

                    double var20 = var19.x + 0.5;
                    double var22 = var19.y + 1.0;
                    double var24 = var19.z + 0.5;
                    ScreenProjector.Result var26 = ScreenProjector.project(var20, var22, var24, var7.x, var7.y, var7.z, var8, var9, var10, var12, var13);
                    if (!Float.isFinite(var26.x) || !Float.isFinite(var26.y)) {
                        continue;
                    }
                    if (!(var26.distance < 4.0)) {
                        drawLabel(var0, var14, var19, var26, var12, var13);
                        var17++;
                    }
                }

                RenderSystem.disableBlend();
            } catch (Exception var27) {
                UnboundMapsDebug.note(var27);
            }
        }
    }

    private static void drawLabel(GuiGraphics var0, Font var1, Waypoint var2, ScreenProjector.Result var3, int var4, int var5) {
        int var6 = (int)Mth.clamp(var3.x, 14.0F, (float)Math.max(var4 - 14, 14));
        int var7 = (int)Mth.clamp(var3.y, 22.0F, (float)Math.max(var5 - 34, 22));
        var0.fill(var6 - 2, var7 - 2, var6 + 3, var7 + 3, var2.colorARGB);
        var0.renderOutline(var6 - 3, var7 - 3, 7, 7, var2.beacon ? -922746881 : -267382764);
        String var8 = (int)Math.round(var3.distance) + "m";
        String var9 = var2.name;
        String var10 = var9 + " " + var8;
        int var11 = var3.onScreen ? -1 : -641084710;
        PoseStack var12 = var0.pose();
        var12.pushPose();
        var12.translate(var6, var7 + 7.0F, 0.0F);
        var12.scale(0.75F, 0.75F, 1.0F);
        int var13 = -var1.width(var10) / 2;
        var13 = Mth.clamp(var13, (int)((2 - var6) / 0.75F), (int)((var4 - 2 - var6) / 0.75F) - var1.width(var10));
        var0.drawString(var1, var9, var13, 0, var11, true);
        var0.drawString(var1, var8, var13 + var1.width(var9) + 4, 0, -6311745, true);
        var12.popPose();
    }
}
