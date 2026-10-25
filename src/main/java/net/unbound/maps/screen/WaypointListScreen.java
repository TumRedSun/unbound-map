package net.unbound.maps.screen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.unbound.maps.waypoints.Waypoint;
import net.unbound.maps.waypoints.WaypointManager;

public class WaypointListScreen extends Screen {
    private static final int ROW_H = 26;
    private static final int TOP = 32;
    private final Screen parent;
    private final List<Waypoint> sorted = new ArrayList<>();
    private double scroll;

    public WaypointListScreen() {
        this(null);
    }

    public WaypointListScreen(Screen var1) {
        super(Component.translatable("gui.unboundmaps.waypoints"));
        this.parent = var1;
    }

    protected void init() {
        this.addRenderableWidget(Button.builder(Component.translatable("gui.unboundmaps.new"), var1 -> {
            Minecraft var2 = Minecraft.getInstance();
            int var3 = var2.player != null ? var2.player.getBlockX() : 0;
            int var4 = var2.player != null ? var2.player.getBlockY() : 64;
            int var5 = var2.player != null ? var2.player.getBlockZ() : 0;
            Waypoint var6 = new Waypoint(UUID.randomUUID().toString(), I18n.get("gui.unboundmaps.defaultName"), var3, var4, var5, -1, true);
            var2.setScreen(new WaypointEditScreen(this, var6));
        }).bounds(this.width / 2 - 130, this.height - 26, 120, 20).build());
        if (this.parent != null) {
            this.addRenderableWidget(
                Button.builder(Component.translatable("gui.unboundmaps.back"), var1 -> this.minecraft.setScreen(this.parent))
                    .bounds(this.width / 2 + 10, this.height - 26, 120, 20)
                    .build()
            );
        }
    }

    private void resort() {
        Minecraft var1 = Minecraft.getInstance();
        double var2 = var1.player != null ? var1.player.getX() : 0.0;
        double var4 = var1.player != null ? var1.player.getZ() : 0.0;
        this.sorted.clear();
        this.sorted.addAll(WaypointManager.currentWaypoints());
        this.sorted.sort(Comparator.comparingDouble(var4x -> (var4x.x - var2) * (var4x.x - var2) + (var4x.z - var4) * (var4x.z - var4)));
    }

    /** The dark veil and blur are drawn manually before the rows. */
    @Override
    public void renderBackground(GuiGraphics var1, int var2, int var3, float var4) {
    }

    public void render(GuiGraphics var1, int var2, int var3, float var4) {
        this.renderBlurredBackground(var4);
        this.resort();
        var1.fill(0, 0, this.width, this.height, -1072689132);
        var1.drawCenteredString(this.font, this.title, this.width / 2, 12, -1);
        if (this.sorted.isEmpty()) {
            var1.drawCenteredString(this.font, I18n.get("gui.unboundmaps.noWaypoints"), this.width / 2, this.height / 2 - 10, -4668212);
        } else {
            int var5 = (this.height - 32 - 40) / 26;
            this.scroll = Mth.clamp(this.scroll, 0.0, Math.max(0, this.sorted.size() - var5));

            for (int var6 = 0; var6 < var5; var6++) {
                int var7 = var6 + (int)this.scroll;
                if (var7 >= this.sorted.size()) {
                    break;
                }

                Waypoint var8 = this.sorted.get(var7);
                int var9 = 32 + var6 * 26;
                boolean var10 = var2 >= 8 && var2 <= this.width - 8 && var3 >= var9 && var3 < var9 + 26 - 2;
                var1.fill(8, var9, this.width - 8, var9 + 26 - 2, var10 ? 1358954495 : 822083583);
                var1.fill(12, var9 + 5, 24, var9 + 17, -267382764);
                var1.fill(13, var9 + 6, 23, var9 + 16, var8.colorARGB);
                var1.drawString(this.font, var8.name, 30, var9 + 4, -1, true);
                String var11 = "X "
                    + var8.x
                    + "  Y "
                    + var8.y
                    + "  Z "
                    + var8.z
                    + (var8.beacon ? "  | " + I18n.get("gui.unboundmaps.beacon") : "");
                var1.drawString(this.font, var11, 30, var9 + 14, -4668212, false);
                int var12 = this.width - 110;
                var1.fill(var12, var9 + 3, var12 + 48, var9 + 19, 1627389951);
                var1.drawCenteredString(this.font, I18n.get("gui.unboundmaps.edit"), var12 + 24, var9 + 6, -1);
                var1.fill(var12 + 54, var9 + 3, var12 + 94, var9 + 19, 1627389951);
                var1.drawCenteredString(this.font, I18n.get("gui.unboundmaps.delete"), var12 + 74, var9 + 6, -1);
            }
        }

        super.render(var1, var2, var3, var4);
    }

    public boolean mouseClicked(double var1, double var3, int var5) {
        int var6 = (this.height - 32 - 40) / 26;

        for (int var7 = 0; var7 < var6; var7++) {
            int var8 = var7 + (int)this.scroll;
            if (var8 >= this.sorted.size()) {
                break;
            }

            Waypoint var9 = this.sorted.get(var8);
            int var10 = 32 + var7 * 26;
            if (var3 >= var10 && var3 < var10 + 26 - 2 && var1 >= this.width - 110) {
                int var11 = this.width - 110;
                if (var1 >= var11 && var1 <= var11 + 48) {
                    this.minecraft.setScreen(new WaypointEditScreen(this, var9));
                    return true;
                }

                if (var1 >= var11 + 54 && var1 <= var11 + 94) {
                    WaypointManager.remove(var9.id);
                    return true;
                }
            }
        }

        return super.mouseClicked(var1, var3, var5);
    }

    public boolean mouseScrolled(double var1, double var3, double var5, double var7) {
        this.scroll -= var7 * 2.0;
        return true;
    }

    public void onClose() {
        WaypointManager.saveNow();
        this.minecraft.setScreen(this.parent);
    }
}
