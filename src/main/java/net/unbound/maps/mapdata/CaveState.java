package net.unbound.maps.mapdata;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.tags.BlockTags;
import net.unbound.maps.UnboundConfig;

/**
 * Xaero-style cave mode state, shared by the minimap and the world map but with
 * separate view rules:
 *
 * <ul>
 *   <li><b>Minimap</b> - always automatic: the cut-lid slice engages while the
 *   player is underground and follows the player's height. The C key pins the
 *   slice view on until pressed again, so the slice can also be inspected on
 *   the surface.</li>
 *   <li><b>World map</b> - manual, like Xaero's world map: the height slider's
 *   leftmost position is AUTO (slice follows the player while underground), any
 *   other position shows the slice around that fixed height.</li>
 * </ul>
 *
 * <p>Underground detection combines THREE independent signals - the old single
 * OCEAN_FLOOR heightmap check failed to engage for players on servers whose
 * surface lay far above them (and it cannot see the difference between a real
 * lid and an open sky pocket):</p>
 *
 * <ol>
 *   <li><b>Solid lid overhead</b> - a motion-blocking, non-foliage, non-liquid
 *   block directly above the player's head (the first block of a cave/room
 *   ceiling). This is the primary signal the player asked for; it reads the
 *   actual blocks, so it never depends on heightmap sync.</li>
 *   <li><b>Cave biome</b> - the biome at the player's position is an
 *   underground biome (vanilla dripstone/lush/deep dark plus modded cave
 *   biomes such as Terralith's cave/* and *_caves families). Big open caves
 *   have ceilings far overhead and pockets of open sky; the biome filter
 *   catches exactly those cases.</li>
 *   <li><b>Below the surface</b> - the classic OCEAN_FLOOR check (eyes at or
 *   below the motion-blocking surface), kept as a fallback so swimming
 *   underwater still switches like Xaero's.</li>
 * </ol>
 */
public final class CaveState {
    private static final int HYSTERESIS_TICKS = 10;
    /** Re-scan of the overhead column at most every 20 ticks even when standing still. */
    private static final int LID_RESCAN_TICKS = 20;
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
    private static boolean lidAboveHead;
    private static boolean caveBiomeHere;
    private static long lastLidScanGameTime = Long.MIN_VALUE;

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
     * Per-tick update. The three underground signals are combined; a short
     * hysteresis avoids flicker when one signal oscillates at a border (jumping
     * into a cave entrance, swimming at the surface...).
     */
    public static void tick(ClientLevel level, LocalPlayer player) {
        if (level == null || player == null) {
            underground = false;
            undergroundStable = false;
            initialized = false;
            lidAboveHead = false;
            caveBiomeHere = false;
            lastLidScanGameTime = Long.MIN_VALUE;
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
        boolean moved = pos != lastUndergroundPos;
        boolean rescanTime = level.getGameTime() - lastLidScanGameTime >= LID_RESCAN_TICKS;

        if (!initialized || moved || rescanTime) {
            // fresh detection: the block scan is cached per position and re-run at
            // most twice a second while standing still (blocks overhead may change
            // - the player mines the ceiling - without the player moving)
            initialized = true;
            lastUndergroundPos = pos;
            lastLidScanGameTime = level.getGameTime();
            pendingTicks = 0;
            lidAboveHead = solidLidAboveHead(level, player, eye);
            caveBiomeHere = isCaveBiomeHere(level, player);
            underground = lidAboveHead || caveBiomeHere || belowSurface(level, player, eye);
            applyUnderground();
            return;
        }

        // cheap per-tick re-check: the cached lid/biome flags plus the heightmap
        // lookup; the expensive overhead block scan stays cached
        boolean now = caveBiomeHere || lidAboveHead || belowSurface(level, player, eye);
        if (now == underground) {
            pendingTicks = 0;
        } else if (++pendingTicks >= HYSTERESIS_TICKS) {
            pendingTicks = 0;
            underground = now;
            applyUnderground();
        }
    }

    /**
     * Signal 1: a solid lid directly above the head. Scans the player's column
     * upward from just above the eyes and stops at the FIRST motion-blocking,
     * non-liquid, non-foliage block - a cave or room ceiling. Open sky above
     * (the surface case) scans all the way to the build limit and returns
     * false; leaves and plants are skipped so shade under a tree stays the
     * surface view, and water is skipped so diving does not read as a lid
     * (the below-surface check handles water).
     */
    private static boolean solidLidAboveHead(ClientLevel level, LocalPlayer player, int eye) {
        int maxY = level.getMaxBuildHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(player.getBlockX(), eye + 1, player.getBlockZ());
        for (int y = pos.getY(); y < maxY; y++) {
            pos.setY(y);
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !state.getFluidState().isEmpty()) {
                continue;
            }
            if (state.is(BlockTags.LEAVES)) {
                continue;
            }
            return state.blocksMotion();
        }
        return false;
    }

    /**
     * Signal 2: the player stands in an underground biome. The filter matches
     * vanilla's three cave biomes plus modded cave-biome families without
     * false-positive surface biomes: the path must BE a cave name
     * (dripstone_caves, lush_caves, deep_dark), live in a cave/ or caves/
     * folder (Terralith "cave/diorite_caves"), or end with _caves/_cave.
     */
    private static boolean isCaveBiomeHere(ClientLevel level, LocalPlayer player) {
        Holder<Biome> holder = level.getBiome(player.blockPosition());
        ResourceKey<Biome> key = holder.unwrapKey().orElse(null);
        if (key == null) {
            return false;
        }
        ResourceLocation id = key.location();
        String path = id.getPath();
        if (id.getNamespace().equals("minecraft")) {
            return path.equals("dripstone_caves") || path.equals("lush_caves") || path.equals("deep_dark");
        }
        return path.equals("cave") || path.equals("caves")
            || path.startsWith("cave/") || path.startsWith("caves/")
            || path.endsWith("_caves") || path.endsWith("_cave");
    }

    /**
     * Signal 3: the classic surface check. The eyes at or below the
     * OCEAN_FLOOR heightmap (motion-blocking blocks incl. fluids) mean the
     * player is under the surface - swimming ON water still shows the top
     * view, diving or mining switches to the slice view.
     */
    private static boolean belowSurface(ClientLevel level, LocalPlayer player, int eye) {
        int floor = level.getHeight(Types.OCEAN_FLOOR, player.getBlockX(), player.getBlockZ());
        return eye <= floor;
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
