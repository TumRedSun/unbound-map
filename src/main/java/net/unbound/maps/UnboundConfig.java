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
    public static final EnumValue<UnboundConfig.Anchor> MINIMAP_ANCHOR;
    public static final BooleanValue MINIMAP_BIG_CENTERED;
    public static final BooleanValue SHOW_COORDS;
    public static final BooleanValue SHOW_BIOME;
    public static final BooleanValue DEATH_POINTS;
    public static final IntValue DEATH_COLOR;
    public static final IntValue DEATH_OLD_COLOR;
    public static final BooleanValue DEATH_BEACON;
    public static final BooleanValue SHOW_OLD_DEATH_POINTS;
    public static final BooleanValue BEAMS_UNLIMITED;
    public static final IntValue BEAM_RENDER_DISTANCE;
    public static final BooleanValue BEAM_SEE_THROUGH;
    public static final BooleanValue SHOW_MARKERS_ON_SCREEN;
    public static final IntValue CAVE_RANGE;
    public static final BooleanValue AUTO_CAVE_MODE;
    public static final BooleanValue TERRAIN_SHADING;
    public static final BooleanValue BIOME_SHADING;
    public static final BooleanValue LIGHT_SHADING;
    public static final IntValue NIGHT_DIM_STRENGTH;
    public static final BooleanValue ENTITY_RADAR;
    public static final IntValue RADAR_RANGE;
    public static final BooleanValue RADAR_PLAYERS;
    public static final BooleanValue RADAR_HOSTILES;
    public static final BooleanValue RADAR_PASSIVES;
    public static final IntValue RADAR_Y_RANGE;
    public static final BooleanValue PERSIST_MAP;
    public static final ConfigValue<String> TELEPORT_COMMAND;

    private UnboundConfig() {
    }

    static {
        Builder var0 = new Builder();
        var0.push("minimap");
        MINIMAP_ENABLED = var0.comment("Show the HUD minimap").define("enabled", true);
        MINIMAP_SHAPE = var0.comment("Minimap frame shape: SQUARE or ROUND").defineEnum("shape", UnboundConfig.Shape.SQUARE);
        MINIMAP_SIZE = var0.comment("Minimap side length in GUI pixels (64 - 256)").defineInRange("size", 140, 64, 256);
        MINIMAP_ANCHOR = var0.comment(
                "Corner of the screen where the minimap HUD is shown."
            )
            .defineEnum("anchor", UnboundConfig.Anchor.TOP_RIGHT);
        MINIMAP_BIG_CENTERED = var0.comment(
                "When the enlarged minimap is toggled (default key Z), place it\n",
                "in the center of the screen instead of the configured corner."
            )
            .define("bigMapCentered", true);
        SHOW_COORDS = var0.comment("Show X/Y/Z under the minimap").define("showCoordinates", true);
        SHOW_BIOME = var0.comment("Show the current biome under the minimap").define("showBiome", true);
        var0.pop();
        var0.push("waypoints");
        DEATH_POINTS = var0.comment(
                "Automatically add a death waypoint when the player dies.\n",
                "The newest death point is drawn with deathPointColor and\n",
                "labeled as the current one, older ones become 'old death\n",
                "points'. Standing within 3 blocks of a death point for 5\n",
                "seconds removes it automatically (you came back for your loot)."
            )
            .define("deathPoints", true);
        DEATH_COLOR = var0.comment("Color (ARGB) of the newest death waypoint")
            .defineInRange("deathPointColor", 0xFFFD5B5C, Integer.MIN_VALUE, Integer.MAX_VALUE);
        DEATH_OLD_COLOR = var0.comment("Color (ARGB) of old death waypoints")
            .defineInRange("oldDeathPointColor", 0xFF8F4D4F, Integer.MIN_VALUE, Integer.MAX_VALUE);
        DEATH_BEACON = var0.comment("Draw a beacon beam at death waypoints")
            .define("deathPointBeacon", false);
        SHOW_OLD_DEATH_POINTS = var0.comment("Show the old (not the newest) death waypoints")
            .define("showOldDeathPoints", true);
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
                "Cave mode (Xaero-style 'cut lid' view): everything above the",
                " view height is removed and the first solid block below it is",
                " shown at any depth. This value only controls how many blocks",
                " below the view height keep full brightness before the",
                " depth fade starts.",
                " Xaero-style slice, no fair-play restrictions:",
                " works at any depth, in every dimension."
            )
            .defineInRange("sliceRange", 10, 2, 64);
        AUTO_CAVE_MODE = var0.comment(
                "Automatically switch between surface and cave mode:",
                " cave mode engages when ANY of these is true -",
                "  1. a solid lid (motion-blocking, non-foliage block) sits",
                "     directly above the player's head (a cave/room ceiling),",
                "  2. the player stands in an underground biome (vanilla",
                "     dripstone/lush/deep dark plus modded cave biomes such as",
                "     Terralith's cave/* and *_caves families),",
                "  3. the player's eyes are below the solid terrain surface",
                "     (OCEAN_FLOOR heightmap), so diving switches to the slice",
                "     view while swimming ON water still shows the top view.",
                " Pressing C toggles the mode manually and overrides",
                " the automation until the underground state changes again."
            )
            .define("autoCaveMode", true);
        var0.pop();
        var0.push("shading");
        TERRAIN_SHADING = var0.comment(
                "Terrain depth shading: slopes facing away are darkened,",
                " slopes facing the light are brightened (hillshade,",
                " computed across chunk borders)."
            )
            .define("terrainDepth", true);
        BIOME_SHADING = var0.comment(
                "Biome shading: grass and foliage get their biome color,",
                " water gets the biome water color (swamp, jungle, badlands...)."
            )
            .define("biomeColors", true);
        LIGHT_SHADING = var0.comment(
                "Light shading: surface tiles under roofs/leaves darken,",
                " cave mode tiles are lit by block light (torches glow)."
            )
            .define("lightShading", true);
        NIGHT_DIM_STRENGTH = var0.comment(
                "Night dimming strength: 0 = the map keeps its full daytime",
                " colors around the clock (Xaero-style, always readable),",
                " 1..3 = a subtle dusk/night tint on top (max 36% darker)."
            )
            .defineInRange("nightDimStrength", 0, 0, 3);
        var0.pop();
        var0.push("radar");
        ENTITY_RADAR = var0.comment(
                "Show live entities on the minimap: player heads from skins,",
                " mob heads from their textures where the face layout is known,",
                " colored dots for everything else."
            )
            .define("enabled", true);
        RADAR_RANGE = var0.comment("Radar radius in blocks (16 - 512)").defineInRange("range", 64, 16, 512);
        RADAR_PLAYERS = var0.comment("Show other players on the radar").define("showPlayers", true);
        RADAR_HOSTILES = var0.comment("Show hostile mobs on the radar").define("showHostiles", true);
        RADAR_PASSIVES = var0.comment("Show passive mobs and villagers on the radar").define("showPassives", true);
        RADAR_Y_RANGE = var0.comment(
                "Show entities on the radar only within this many blocks",
                " of your own altitude, so mobs in caves far below or",
                " high in the sky do not clutter the map. 0 shows",
                " entities at any height."
            )
            .defineInRange("verticalRange", 10, 0, 1024);
        var0.pop();
        var0.push("persistence");
        PERSIST_MAP = var0.comment(
                "Save the explored map to disk per server/world/dimension:",
                " terrain colors and heights per chunk, plus the full list of",
                " biomes and blocks you have ever seen (discovery.json).",
                " Files live in <world>/unboundmaps/map/<dimension> for",
                " singleplayer and in unboundmaps/servers/<ip>/map/<dimension>",
                " for multiplayer. Unloaded chunks keep rendering from the cache."
            )
            .define("enabled", true);
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

    public static enum Anchor {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT;
    }
}
