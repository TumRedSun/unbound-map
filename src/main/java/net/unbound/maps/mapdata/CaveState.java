package net.unbound.maps.mapdata;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.unbound.maps.UnboundConfig;

/**
 * Xaero-style cave mode state, shared by the minimap and the world map but with
 * separate view rules:
 *
 * <ul>
 *   <li><b>Minimap</b> - always automatic: the cut-lid slice engages while the
 *   player is below the solid surface (OCEAN_FLOOR heightmap) and follows the
 *   player's height. The C key pins the slice view on until pressed again, so
 *   the slice can also be inspected on the surface.</li>
 *   <li><b>World map</b> - manual, like Xaero's world map: the height slider's
 *   leftmost position is AUTO (slice follows the player while underground), any
 *   other position shows the slice around that fixed height.</li>
 * </ul>
 *
 * <p>Splitting the rules removes the old trap where dragging the slider once
 * left the MINIMAP stuck in a fixed cave view forever, never showing the
 * player's own height automatically.</p>
 */
public final class CaveState {
    private static final int HYSTERESIS_TICKS = 10;
    /** Slider at the leftmost AUTO position: the world map slice follows the player. */
    private static boolean auto = true;
    private static int fixedY = 64;
    private static boolean underground;
    private static boolean undergroundStable;
    /** C key: force the cave slice view on (the minimap slice follows the player). */
    private static boolean pinned;
    private static int pendingTicks;
    private static long lastUndergroundPos = Long.MIN_VALUE;
    private static boolean initialized;

    private CaveState() {
    }

    public static boolean isAuto() {
        return auto;
    }

    public static int fixedY() {
        return fixedY;
    }

    public static boolean isPinned() {
        return pinned;
    }

    /** True while the WORLD MAP should render the cave slice (the C pin peeks the slice on both maps). */
    public static boolean isWorldCave() {
        return pinned || (auto ? undergroundStable : true);
    }

    /** True while the MINIMAP should render the cave slice (always automatic). */
    public static boolean isMiniCave() {
        return pinned || undergroundStable;
    }

    /** Kept for compatibility: the world-map view drives the shared world cells. */
    public static boolean isCave() {
        return isWorldCave();
    }

    /** The Y the WORLD MAP slice is centered on: the player's feet in AUTO, the slider otherwise. */
    public static int worldSliceY() {
        if (auto) {
            LocalPlayer player = Minecraft.getInstance().player;
            return player != null ? player.getBlockY() : fixedY;
        }
        return fixedY;
    }

    /** The Y the MINIMAP slice is centered on: the player's feet (or the pinned fixed height). */
    public static int miniSliceY() {
        if (pinned && !auto) {
            return fixedY;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null ? player.getBlockY() : fixedY;
    }

    /** Slider fraction: 0 = AUTO, 1..N = fixed heights mapped linearly over the build range. */
    public static double sliderFraction() {
        if (auto) {
            return 0.0;
        }
        ClientLevel level = Minecraft.getInstance().level;
        int min = level != null ? level.getMinBuildHeight() : -64;
        int max = level != null ? level.getMaxBuildHeight() - 1 : 319;
        return Mth.clamp((fixedY - min) / (double)Math.max(1, max - min), 0.02, 1.0);
    }

    /** Slider drag: fraction 0 snaps to AUTO, anything above picks a fixed height. */
    public static void setFromSlider(double fraction) {
        if (fraction <= 0.005) {
            setAuto(true);
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        int min = level != null ? level.getMinBuildHeight() : -64;
        int max = level != null ? level.getMaxBuildHeight() - 1 : 319;
        int y = Mth.floor(min + Mth.clamp(fraction, 0.0, 1.0) * (max - min));
        setFixed(y);
    }

    public static void setAuto(boolean value) {
        auto = value;
        pinned = false;
        MapTileCache.onCaveViewChanged();
    }

    public static void setFixed(int y) {
        if (auto || y != fixedY) {
            auto = false;
            fixedY = y;
        }

        pinned = false;
        MapTileCache.onCaveViewChanged();
    }

    /** C key: pin the cave slice on the minimap (it follows the player), or release the pin. */
    public static void toggleFixed() {
        pinned = !pinned;
        MapTileCache.onCaveViewChanged();
    }

    /** [ and ] keys: step the fixed height by 8 blocks, leaving AUTO (and the pin). */
    public static void adjustHeight(int delta) {
        int y = auto ? (Minecraft.getInstance().player != null ? Minecraft.getInstance().player.getBlockY() : 64) : fixedY;
        setFixed(Mth.clamp(y + delta, -2032, 2032));
    }

    /**
     * Per-tick update: detects "below the solid surface" with the OCEAN_FLOOR
     * heightmap (water and leaves excluded, so swimming on a lake keeps the
     * top view) and applies a short hysteresis to avoid flicker at the border.
     */
    public static void tick(ClientLevel level, LocalPlayer player) {
        if (level == null || player == null) {
            underground = false;
            undergroundStable = false;
            initialized = false;
            return;
        }
        if (!configAllowsAuto()) {
            // auto switching is disabled in the mod config: the maps keep the top
            // view unless the player pins the slice manually (C key)
            underground = false;
            applyUnderground();
            initialized = false;
            return;
        }
        int eye = Mth.floor(player.getEyeY());
        long pos = player.blockPosition().asLong();
        boolean changedColumn = pos != lastUndergroundPos;
        if (!initialized || changedColumn) {
            initialized = true;
            lastUndergroundPos = pos;
            pendingTicks = 0;
            int floor = level.getHeight(Types.OCEAN_FLOOR, player.getBlockX(), player.getBlockZ());
            underground = eye < floor;
            applyUnderground();
            return;
        }
        int floor = level.getHeight(Types.OCEAN_FLOOR, player.getBlockX(), player.getBlockZ());
        boolean now = eye < floor;
        if (now == underground) {
            pendingTicks = 0;
        } else if (++pendingTicks >= HYSTERESIS_TICKS) {
            pendingTicks = 0;
            underground = now;
            applyUnderground();
        }
    }

    private static void applyUnderground() {
        if (undergroundStable != underground) {
            undergroundStable = underground;
            MapTileCache.onCaveViewChanged();
        }
    }

    /** True when the auto switcher is enabled in the config (used for the hint text). */
    public static boolean configAllowsAuto() {
        try {
            return UnboundConfig.AUTO_CAVE_MODE.get();
        } catch (NullPointerException | IllegalStateException e) {
            return true;
        }
    }
}
