package net.unbound.maps.keybinds;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.unbound.maps.UnboundMapsClient;
import net.unbound.maps.mapdata.CaveState;
import net.unbound.maps.render.MinimapHud;
import net.unbound.maps.screen.WaypointListScreen;
import net.unbound.maps.screen.WorldMapScreen;

public final class UnboundKeys {
    public static final String CATEGORY = "key.categories.unboundmaps";
    public static final KeyMapping OPEN_MAP = new KeyMapping("key.unboundmaps.map", 77, "key.categories.unboundmaps");
    public static final KeyMapping OPEN_WAYPOINTS = new KeyMapping("key.unboundmaps.waypoints", 66, "key.categories.unboundmaps");
    public static final KeyMapping MINIMAP_BIG = new KeyMapping("key.unboundmaps.minimap_big", 90, "key.categories.unboundmaps");
    public static final KeyMapping ZOOM_IN = new KeyMapping("key.unboundmaps.zoom_in", 61, "key.categories.unboundmaps");
    public static final KeyMapping ZOOM_OUT = new KeyMapping("key.unboundmaps.zoom_out", 45, "key.categories.unboundmaps");
    public static final KeyMapping TOGGLE_CAVE = new KeyMapping("key.unboundmaps.cave", 67, "key.categories.unboundmaps");
    public static final KeyMapping CAVE_HEIGHT_DOWN = new KeyMapping("key.unboundmaps.cave_height_down", 44, "key.categories.unboundmaps");
    public static final KeyMapping CAVE_HEIGHT_UP = new KeyMapping("key.unboundmaps.cave_height_up", 46, "key.categories.unboundmaps");

    private UnboundKeys() {
    }

    public static void register(RegisterKeyMappingsEvent var0) {
        var0.register(OPEN_MAP);
        var0.register(OPEN_WAYPOINTS);
        var0.register(MINIMAP_BIG);
        var0.register(ZOOM_IN);
        var0.register(ZOOM_OUT);
        var0.register(TOGGLE_CAVE);
        var0.register(CAVE_HEIGHT_DOWN);
        var0.register(CAVE_HEIGHT_UP);
    }

    public static void handleTickInput() {
        Minecraft var0 = Minecraft.getInstance();
        if (var0.player != null && var0.screen == null) {
            while (OPEN_MAP.consumeClick()) {
                var0.setScreen(new WorldMapScreen());
            }

            while (OPEN_WAYPOINTS.consumeClick()) {
                var0.setScreen(new WaypointListScreen());
            }

            while (MINIMAP_BIG.consumeClick()) {
                MinimapHud.toggleBig();
            }

            while (ZOOM_IN.consumeClick()) {
                MinimapHud.zoomIn();
            }

            while (ZOOM_OUT.consumeClick()) {
                MinimapHud.zoomOut();
            }

            while (TOGGLE_CAVE.consumeClick()) {
                CaveState.toggleFixed();
            }

            while (CAVE_HEIGHT_DOWN.consumeClick()) {
                CaveState.adjustHeight(-8);
            }

            while (CAVE_HEIGHT_UP.consumeClick()) {
                CaveState.adjustHeight(8);
            }
        }
    }
}
