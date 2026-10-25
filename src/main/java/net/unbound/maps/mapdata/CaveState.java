package net.unbound.maps.mapdata;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.unbound.maps.UnboundConfig;

/**
 * Xaero-style cave mode state shared by the minimap and the world map.
 *
 * <p>The mode is either AUTO (the map shows the surface while the player is
 * above ground and a slice that follows the player while they are below it,
 * detected with the OCEAN_FLOOR heightmap) or FIXED (the map always shows the
 * slice around one fixed Y level, chosen with the height slider). The slider's
 * leftmost position is AUTO, like in Xaero's world map.</p>
 */
public final class CaveState {
    private static final int HYSTERESIS_TICKS = 10;
    private static boolean auto = true;
    private static int fixedY = 64;
    private static boolean underground;
    private static boolean undergroundStable;
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

    /** True while the map should render the cave slice (both maps together). */
    public static boolean isCave() {
        return auto ? undergroundStable : true;
    }

    /** The Y the slice is centered on: the player's feet in AUTO, the slider otherwise. */
    public static int sliceY() {
        if (auto) {
            LocalPlayer player = Minecraft.getInstance().player;
            return player != null ? player.getBlockY() : fixedY;
        }
        return fixedY;
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
        if (value) {
            undergroundStable = underground;
            MapTileCache.onCaveViewChanged();
        }
    }

    public static void setFixed(int y) {
        if (auto || y != fixedY) {
            auto = false;
            fixedY = y;
            MapTileCache.onCaveViewChanged();
        }
    }

    /** C key: pin the view at the player's current height, or return to AUTO. */
    public static void toggleFixed() {
        if (auto) {
            LocalPlayer player = Minecraft.getInstance().player;
            setFixed(player != null ? player.getBlockY() : 64);
        } else {
            setAuto(true);
        }
    }

    /** [ and ] keys: step the fixed height by 8 blocks, leaving AUTO when pressed. */
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
        int eye = Mth.floor(player.getEyeY());
        long pos = player.blockPosition().asLong();
        boolean changedColumn = pos != lastUndergroundPos;
        if (!initialized || changedColumn) {
            initialized = true;
            lastUndergroundPos = pos;
            pendingTicks = 0;
            int floor = level.getHeight(Types.OCEAN_FLOOR, player.getBlockX(), player.getBlockZ());
            underground = eye < floor;
            if (undergroundStable != underground) {
                undergroundStable = underground;
                MapTileCache.onCaveViewChanged();
            }
            return;
        }
        int floor = level.getHeight(Types.OCEAN_FLOOR, player.getBlockX(), player.getBlockZ());
        boolean now = eye < floor;
        if (now == underground) {
            pendingTicks = 0;
        } else if (++pendingTicks >= HYSTERESIS_TICKS) {
            pendingTicks = 0;
            underground = now;
            if (undergroundStable != underground) {
                undergroundStable = underground;
                MapTileCache.onCaveViewChanged();
            }
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
