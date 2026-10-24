package net.unbound.maps.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.unbound.maps.waypoints.Waypoint;
import net.unbound.maps.waypoints.WaypointManager;

public class WaypointEditScreen extends Screen {
    private static final int[] PALETTE = new int[]{-1749940, -1727668, -1712564, -9640628, -11740699, -11761435, -5223195, -1749853, -1, -13619152};
    private final Screen parent;
    private final Waypoint waypoint;
    private final boolean isNew;
    private EditBox nameBox;
    private EditBox xBox;
    private EditBox yBox;
    private EditBox zBox;
    private int colorButtonX;
    private int colorButtonY;
    private int colorIndex;
    private boolean beacon;
    private boolean invalidCoords;

    public WaypointEditScreen(Screen var1, Waypoint var2) {
        super(Component.translatable("gui.unboundmaps.editTitle"));
        this.parent = var1;
        this.waypoint = var2;
        this.isNew = !WaypointManager.contains(var2.id);
        this.beacon = var2.beacon;

        for (int var3 = 0; var3 < PALETTE.length; var3++) {
            if (PALETTE[var3] == var2.colorARGB) {
                this.colorIndex = var3;
                break;
            }
        }
    }

    protected void init() {
        int var1 = this.width / 2;
        this.nameBox = new EditBox(this.font, var1 - 90, 44, 180, 18, Component.translatable("gui.unboundmaps.name"));
        this.nameBox.setMaxLength(48);
        this.nameBox.setValue(this.waypoint.name);
        this.nameBox.setHint(Component.translatable("gui.unboundmaps.name"));
        this.addRenderableWidget(this.nameBox);
        this.xBox = new EditBox(this.font, var1 - 90, 78, 56, 18, Component.literal("X"));
        this.yBox = new EditBox(this.font, var1 - 30, 78, 56, 18, Component.literal("Y"));
        this.zBox = new EditBox(this.font, var1 + 30, 78, 56, 18, Component.literal("Z"));
        this.xBox.setValue(String.valueOf(this.waypoint.x));
        this.yBox.setValue(String.valueOf(this.waypoint.y));
        this.zBox.setValue(String.valueOf(this.waypoint.z));
        this.xBox.setFilter(var0 -> var0.isEmpty() || var0.matches("-?\\d{1,9}"));
        this.yBox.setFilter(var0 -> var0.isEmpty() || var0.matches("-?\\d{1,9}"));
        this.zBox.setFilter(var0 -> var0.isEmpty() || var0.matches("-?\\d{1,9}"));
        this.xBox.setMaxLength(10);
        this.yBox.setMaxLength(10);
        this.zBox.setMaxLength(10);
        this.addRenderableWidget(this.xBox);
        this.addRenderableWidget(this.yBox);
        this.addRenderableWidget(this.zBox);
        this.colorButtonX = var1 - 90;
        this.colorButtonY = 104;
        this.addRenderableWidget(
            Button.builder(Component.empty(), var1x -> this.colorIndex = (this.colorIndex + 1) % PALETTE.length)
                .bounds(this.colorButtonX, this.colorButtonY, 88, 18)
                .build()
        );
        this.addRenderableWidget(Button.builder(this.beaconLabel(), var1x -> {
            this.beacon = !this.beacon;
            var1x.setMessage(this.beaconLabel());
        }).bounds(var1 + 2, this.colorButtonY, 88, 18).build());
        this.addRenderableWidget(
            Button.builder(Component.translatable("gui.unboundmaps.done"), var1x -> this.save()).bounds(var1 - 90, this.height - 30, 88, 20).build()
        );
        this.addRenderableWidget(
            Button.builder(Component.translatable("gui.unboundmaps.cancel"), var1x -> this.minecraft.setScreen(this.parent))
                .bounds(var1 + 2, this.height - 30, 88, 20)
                .build()
        );
    }

    private Component beaconLabel() {
        return Component.translatable(this.beacon ? "gui.unboundmaps.beacon.on" : "gui.unboundmaps.beacon.off");
    }

    public void render(GuiGraphics var1, int var2, int var3, float var4) {
        var1.fill(0, 0, this.width, this.height, -1072689132);
        int var5 = this.width / 2;
        var1.drawCenteredString(this.font, this.title, var5, 16, -1);
        var1.drawString(this.font, I18n.get("gui.unboundmaps.name"), var5 - 90, 34, -4668212, false);
        var1.drawString(this.font, "X", var5 - 90, 68, -4668212, false);
        var1.drawString(this.font, "Y", var5 - 30, 68, -4668212, false);
        var1.drawString(this.font, "Z", var5 + 30, 68, -4668212, false);
        super.render(var1, var2, var3, var4);
        var1.fill(this.colorButtonX + 34, this.colorButtonY + 2, this.colorButtonX + 54, this.colorButtonY + 16, -15724528);
        var1.fill(this.colorButtonX + 35, this.colorButtonY + 3, this.colorButtonX + 53, this.colorButtonY + 15, PALETTE[this.colorIndex]);
        if (this.invalidCoords) {
            var1.drawCenteredString(this.font, I18n.get("gui.unboundmaps.invalidCoords"), var5, this.height - 42, -1749940);
        }
    }

    private void save() {
        try {
            int var1 = Integer.parseInt(this.xBox.getValue().trim());
            int var2 = Integer.parseInt(this.yBox.getValue().trim());
            int var3 = Integer.parseInt(this.zBox.getValue().trim());
            String var4 = this.nameBox.getValue().trim();
            if (var4.isEmpty()) {
                var4 = I18n.get("gui.unboundmaps.defaultName");
            }

            this.waypoint.name = var4;
            this.waypoint.x = var1;
            this.waypoint.y = var2;
            this.waypoint.z = var3;
            this.waypoint.colorARGB = PALETTE[this.colorIndex];
            this.waypoint.beacon = this.beacon;
            if (this.isNew) {
                WaypointManager.add(this.waypoint);
            } else {
                WaypointManager.update();
            }

            this.invalidCoords = false;
            this.minecraft.setScreen(this.parent);
        } catch (NumberFormatException var5) {
            this.invalidCoords = true;
        }
    }

    public boolean keyPressed(int var1, int var2, int var3) {
        if (var1 == 257) {
            this.save();
            return true;
        } else {
            return super.keyPressed(var1, var2, var3);
        }
    }

    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
