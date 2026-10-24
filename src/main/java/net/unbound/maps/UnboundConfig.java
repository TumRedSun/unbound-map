package net.unbound.maps;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.Builder;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;
import net.neoforged.neoforge.common.ModConfigSpec.EnumValue;
import net.neoforged.neoforge.common.ModConfigSpec.IntValue;

public final class UnboundConfig {
    public static final ModConfigSpec SPEC;
    public static final BooleanValue MINIMAP_ENABLED;
    public static final EnumValue<UnboundConfig.Shape> MINIMAP_SHAPE;
    public static final IntValue MINIMAP_SIZE;
    public static final BooleanValue SHOW_COORDS;
    public static final BooleanValue SHOW_BIOME;
    public static final BooleanValue DEATH_POINTS;
    public static final BooleanValue BEAMS_UNLIMITED;
    public static final IntValue BEAM_RENDER_DISTANCE;
    public static final BooleanValue BEAM_SEE_THROUGH;
    public static final BooleanValue SHOW_MARKERS_ON_SCREEN;
    public static final IntValue CAVE_RANGE;
    public static final ConfigValue<String> TELEPORT_COMMAND;

    private UnboundConfig() {
    }

    static {
        Builder var0 = new Builder();
        var0.push("minimap");
        MINIMAP_ENABLED = var0.comment("Show the HUD minimap").define("enabled", true);
        MINIMAP_SHAPE = var0.comment("Minimap frame shape: SQUARE or ROUND").defineEnum("shape", UnboundConfig.Shape.SQUARE);
        MINIMAP_SIZE = var0.comment("Minimap side length in GUI pixels (64 - 256)").defineInRange("size", 140, 64, 256);
        SHOW_COORDS = var0.comment("Show X/Y/Z under the minimap").define("showCoordinates", true);
        SHOW_BIOME = var0.comment("Show the current biome under the minimap").define("showBiome", true);
        var0.pop();
        var0.push("waypoints");
        DEATH_POINTS = var0.comment("Automatically add a red waypoint when the player dies").define("deathPoints", true);
        BEAMS_UNLIMITED = var0.comment(
                "Render beacon beams (and the on-screen waypoint labels)\n",
                "at unlimited distance, like Xaero does. When false,\n",
                "beamRenderDistance below is the hard cut-off."
            )
            .define("beamsUnlimited", true);
        BEAM_RENDER_DISTANCE = var0.comment(
                "Legacy distance cap in blocks. Only used when", " beamsUnlimited = false. 0 disables beams entirely."
            )
            .defineInRange("beamRenderDistance", 1024, 0, 1000000);
        BEAM_SEE_THROUGH = var0.comment("Draw beacon beams through terrain").define("beamsSeeThroughTerrain", true);
        SHOW_MARKERS_ON_SCREEN = var0.comment(
                "Show the floating waypoint labels in the game world:",
                " a colored dot with the name, icon and the distance in m,",
                " clamped to the screen edge when the point is off-screen"
            )
            .define("showMarkersOnScreen", true);
        var0.pop();
        var0.push("cave");
        CAVE_RANGE = var0.comment(
                "Cave mode maps the slice of blocks around the player's",
                " altitude: this many blocks above and below the eyes.",
                " Xaero-style slice, no fair-play restrictions:",
                " works at any depth, in every dimension."
            )
            .defineInRange("sliceRange", 10, 2, 64);
        var0.pop();
        var0.push("worldmap");
        TELEPORT_COMMAND = var0.comment(
                "Command used by the right-click 'Teleport here' menu entry.",
                "Placeholders: {x} {y} {z} are replaced with the clicked block coordinates.",
                "The leading slash is optional. Examples:",
                "  /tp @s {x} {y} {z}",
                "  /execute in minecraft:the_nether run tp @s {x} {y} {z}",
                "No fair-play or permission checks are performed client-side:",
                "the command is simply sent to the server, which decides."
            )
            .define("teleportCommand", "/tp @s {x} {y} {z}");
        var0.pop();
        SPEC = var0.build();
    }

    public static enum Shape {
        SQUARE,
        ROUND;
    }
}
