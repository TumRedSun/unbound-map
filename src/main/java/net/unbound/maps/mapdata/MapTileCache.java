package net.unbound.maps.mapdata;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.MapColor;
import net.unbound.maps.UnboundConfig;

/**
 * Computes and caches the 16x16 color tiles for both the minimap and the world map.
 *
 * BOTH views share ONE drawing pipeline ({@link #terrainBlockColor}): block map
 * color + biome tinting (grass/foliage/water) + water depth + lava glow, on top
 * of the same hillshade and the same elevation gradient.
 *
 * Surface tiles: the WORLD_SURFACE block goes through the pipeline, then the
 * full-strength elevation gradient (dark blue lowlands -> natural midlands ->
 * warm bright highlands -> white peaks, anchored at sea level) and the
 * cross-chunk hillshade.
 * Slice (cave) tiles: Xaero-style "cut lid" view at an EXACT height - everything
 * above the cut plane is treated as removed (the roof is cut off). The block at
 * the cut renders as the cross-section wall (slightly lifted, stone family
 * blended toward the bright rock tone), the first non-air block below an open
 * cut (a cave floor, a ravine bottom, lava) is rendered at any depth through the
 * same pipeline, lit by the light inside the cavity itself, with a subtler
 * elevation gradient so "what is higher" reads inside caves too.
 *
 * Final tiles are recorded in {@link MapStore} so explored-but-unloaded chunks
 * keep rendering from disk.
 */
public final class MapTileCache {
    public static final int UNKNOWN = -15723752;
    private static final int MAX_ENTRIES = 40000;
    private static final int MAX_SLICE_ENTRIES = 8000;
    private static final int MAX_SLICE_BUCKETS = 8;
    private static final Map<Long, MapTileCache.Entry> CHUNKS = new LinkedHashMap<>(4096, 0.75F, true);
    /** Slice (cave) tiles per exact cut Y; access-ordered so old heights can be trimmed. */
    private static final Map<Integer, Map<Long, MapTileCache.Entry>> SLICES = new LinkedHashMap<>(8, 0.75F, true);
    private static boolean cave;
    private static int sliceBucket;
    /** Minimap view state (always automatic, see {@link CaveState}). */
    private static boolean miniCave;
    private static int miniBucket;
    /** 16 perceptual light gradations: caves and torch-lit areas get many distinguishable steps. */
    private static final float[] LIGHT_CURVE = {
        0.16F, 0.19F, 0.23F, 0.27F, 0.31F, 0.35F, 0.40F, 0.45F,
        0.51F, 0.57F, 0.64F, 0.71F, 0.79F, 0.87F, 0.94F, 1.00F
    };
    /** How much sky light each night-dim step removes (config 0..3; 0 = Xaero-like constant colors). */
    private static final float NIGHT_STEP = 0.12F;
    private static int nightStrength = 0;
    /** Global look: gentle gamma lift (brighter mids) + mild saturation boost, applied to every rendered pixel. */
    private static final int[] ENHANCE_LUT = new int[256];
    private static final float ENHANCE_GAMMA = 0.92F;
    private static final float ENHANCE_SATURATION = 1.06F;

    static {
        for (int i = 0; i < 256; i++) {
            ENHANCE_LUT[i] = Math.round(255.0F * (float)Math.pow(i / 255.0, ENHANCE_GAMMA));
        }
    }

    private MapTileCache() {
    }

    /** True while the WORLD MAP renders the cave (slice) view. Driven by {@link CaveState}. */
    public static boolean isCave() {
        return cave;
    }

    /** World map active slice key (the exact cut Y + 512). */
    public static int caveBucket() {
        return sliceBucket;
    }

    /** True while the MINIMAP renders the cave (slice) view - always automatic. */
    public static boolean isMiniCave() {
        return miniCave;
    }

    /** Minimap active slice key (the exact cut Y + 512). */
    public static int miniBucket() {
        return miniBucket;
    }

    /**
     * Re-syncs the view flags from {@link CaveState} and invalidates caches
     * when the view mode or the slice height changed. Cheap to call every tick.
     */
    public static void onCaveViewChanged() {
        boolean newCave = CaveState.isWorldCave();
        int newBucket = bucketOf(CaveState.worldSliceY());
        if (newCave != cave || newBucket != sliceBucket) {
            cave = newCave;
            sliceBucket = newBucket;
            // the per-height tile caches are KEPT: moving up or down a cave reuses
            // the slices computed for heights you already visited (clearing them
            // made the view go dark for seconds on every height change)
            trimSlices();
        }

        boolean newMiniCave = CaveState.isMiniCave();
        int newMiniBucket = bucketOf(CaveState.miniSliceY());
        if (newMiniCave != miniCave || newMiniBucket != miniBucket) {
            miniCave = newMiniCave;
            miniBucket = newMiniBucket;
            trimSlices();
            MinimapTexture.invalidateAll();
        }

        // Day/night: the dim is applied at DRAW time (see nightFactor), so tiles
        // keep their colors around the clock and never re-bake when the sky
        // darkens. With strength 0 (default) the map is full-bright like Xaero's.
        nightStrength = intFlag(UnboundConfig.NIGHT_DIM_STRENGTH, 0);
    }

    /** Keeps only the most recently used slice buckets (the active ones are touched on access). */
    private static void trimSlices() {
        SLICES.get(sliceBucket); // refresh access order
        SLICES.get(miniBucket);
        while (SLICES.size() > MAX_SLICE_BUCKETS) {
            Integer oldest = null;
            for (Integer key : SLICES.keySet()) {
                if (key != sliceBucket && key != miniBucket) {
                    oldest = key;
                    break;
                }
            }
            if (oldest == null) {
                break;
            }
            SLICES.remove(oldest);
        }
    }

    /**
     * Uniform day/night dim factor for surface map tiles, applied at draw time
     * (shader color / vertex tint): 1.0 by day, down to 1 - strength*0.12 by
     * full night (strength 0 = constant Xaero-like colors, 3 = 36% darker).
     * The cave view is exempt: it is lit by block light, not by the sun.
     */
    public static float nightFactor(boolean caveView) {
        if (nightStrength <= 0 || caveView) {
            return 1.0F;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return 1.0F;
        }
        return 1.0F - Math.min(1.0F, nightStrength * NIGHT_STEP) * (float)mc.level.getSkyDarken() / 15.0F;
    }

    /**
     * Xaero-like light for the cave (slice) view only: block light (torches, lava,
     * glowstone) keeps its glow, sky light in the cavity follows the day cycle.
     */
    private static float lightShade(ClientLevel var0, int var1, int var2) {
        float var4 = LIGHT_CURVE[var2 & 15];
        float var5 = LIGHT_CURVE[var1 & 15];
        if (nightStrength > 0) {
            var5 *= 1.0F - nightStrength * NIGHT_STEP * (float)var0.getSkyDarken() / 15.0F;
        }

        return Math.max(0.16F, Math.max(var4, var5));
    }

    /**
     * The final look of EVERY map pixel (surface, cave slice, live or stored):
     * a gentle gamma lift makes mid-tones brighter and softer, a mild saturation
     * boost keeps the colors juicy. Lava and other self-lit blocks stay vivid.
     */
    public static int enhance(int var0) {
        if (var0 == UNKNOWN || (var0 >>> 24) != 0xFF) {
            return var0;
        }
        int var1 = ENHANCE_LUT[var0 & 0xFF];
        int var2 = ENHANCE_LUT[var0 >>> 8 & 0xFF];
        int var3 = ENHANCE_LUT[var0 >>> 16 & 0xFF];
        float var4 = 0.299F * var1 + 0.587F * var2 + 0.114F * var3;
        int var5 = clamp255(Math.round(var4 + (var1 - var4) * ENHANCE_SATURATION));
        int var6 = clamp255(Math.round(var4 + (var2 - var4) * ENHANCE_SATURATION));
        int var7 = clamp255(Math.round(var4 + (var3 - var4) * ENHANCE_SATURATION));
        return var0 & 0xFF000000 | var7 << 16 | var6 << 8 | var5;
    }

    private static int clamp255(int var0) {
        return var0 < 0 ? 0 : Math.min(255, var0);
    }

    /** Lava glows: blend the map color toward a vivid bright orange, no depth darkening. */
    private static int lavaColor(int var0) {
        int var1 = Math.round((var0 & 0xFF) * 0.55F + 255.0F * 0.45F);
        int var2 = Math.round((var0 >>> 8 & 0xFF) * 0.55F + 176.0F * 0.45F);
        int var3 = Math.round((var0 >>> 16 & 0xFF) * 0.55F + 64.0F * 0.45F);
        return var0 & 0xFF000000 | clamp255(var3) << 16 | clamp255(var2) << 8 | clamp255(var1);
    }

    /**
     * The ONE color pipeline both views share ("the same drawing logic as the
     * normal mode"): block map color + biome grass/foliage/water tint + water
     * depth darkening + lava glow. The surface view feeds it the WORLD_SURFACE
     * block, the cave slice feeds it the cut-plane wall or the cavity floor, so
     * a cave entrance reads green, a dirt pocket brown and a lava lake glowing
     * on BOTH maps.
     */
    private static int terrainBlockColor(ClientLevel var0, ChunkAccess var1, BlockPos var2, BlockState var3, boolean var4) {
        MapColor var5 = var3.getMapColor(var1, var2);
        int var6 = mapColorAbgr(var5);
        if (var4) {
            Biome var7 = var0.getBiome(var2).value();
            if (var5 == MapColor.WATER) {
                var6 = abgrFromRgb(var7.getWaterColor());
            } else if (var3.is(BlockTags.LEAVES)) {
                // Xaero-like readable foliage: tinting the PLANT map
                // color (zero R/B channels) produced a near-black
                // (0,90,0) that made every forest look burned. The
                // GRASS base keeps R/B, the biome foliage tint decides
                // the hue, and a slight darkening keeps canopies
                // distinct from the grass floor without going black.
                var6 = shadeAbgr(tintAbgr(mapColorAbgr(MapColor.GRASS), var7.getFoliageColor()), 0.88F);
            } else if (var5 == MapColor.GRASS || var5 == MapColor.PLANT) {
                // Xaero-like uniform grass: plant blocks (short/tall
                // grass, ferns, crops...) keep the PLANT map color whose
                // zero R/B channels made tinted pixels a darker, different
                // green than the grass_block next to them (salt-and-pepper
                // noise). Rendering them on the GRASS base color makes
                // plains one smooth, monolithic color.
                int var8 = var5 == MapColor.PLANT ? mapColorAbgr(MapColor.GRASS) : var6;
                var6 = tintAbgr(var8, var7.getGrassColor((double)var2.getX(), (double)var2.getZ()));
            }
        }

        FluidState var9 = var1.getFluidState(var2);
        if (!var9.isEmpty()) {
            if (var9.is(net.minecraft.tags.FluidTags.LAVA)) {
                // lava emits full light: keep it glowing hot instead of the
                // water-style depth darkening that made it look dark red
                return lavaColor(var6);
            }

            int var10 = 0;

            BlockPos var11;
            for (var11 = var2.immutable(); var10 < 12 && !var1.getFluidState(var11).isEmpty(); var10++) {
                var11 = var11.below();
            }

            var6 = shadeAbgr(var6, Math.max(0.45F, 1.0F - var10 * 0.07F));
        }

        return var6;
    }

    /**
     * Elevation gradient (the readable "what is higher / what is lower" cue):
     * a hypsometric ramp anchored at sea level. Low ground tints dark and cool
     * (deep blue), mid ground keeps the natural biome colors, high ground warms
     * up and fades toward white peaks. The tint is weakest in the middle range,
     * so biome colors and the slope hillshade stay visible where you navigate.
     * Stops: {deltaY, r, g, b, alpha} in RGB, interpolated linearly.
     */
    private static final float[][] GRADIENT_STOPS = new float[][]{
        {-64.0F, 30.0F, 62.0F, 108.0F, 0.50F},
        {-24.0F, 60.0F, 94.0F, 130.0F, 0.34F},
        {0.0F, 108.0F, 144.0F, 164.0F, 0.16F},
        {16.0F, 128.0F, 128.0F, 128.0F, 0.0F},
        {48.0F, 250.0F, 224.0F, 148.0F, 0.15F},
        {96.0F, 255.0F, 238.0F, 198.0F, 0.30F},
        {144.0F, 255.0F, 250.0F, 234.0F, 0.44F},
        {192.0F, 255.0F, 255.0F, 255.0F, 0.58F}
    };

    /** The anchor Y of the elevation gradient: sea level per dimension. */
    private static int heightAnchor(ClientLevel var0) {
        if (var0.dimension() == Level.NETHER) {
            return 32;
        }

        return var0.dimension() == Level.END ? 64 : 63;
    }

    /** Blends the ramp tint over an ABGR map color; strength scales the alpha (1 = surface, 0.6 = slice). */
    private static int applyHeightGradient(int var0, int var1, int var2, float var3) {
        float var4 = Mth.clamp((float)(var1 - var2), GRADIENT_STOPS[0][0], GRADIENT_STOPS[GRADIENT_STOPS.length - 1][0]);
        float[] var5 = GRADIENT_STOPS[0];
        float[] var6 = GRADIENT_STOPS[GRADIENT_STOPS.length - 1];

        for (int var7 = 1; var7 < GRADIENT_STOPS.length; var7++) {
            if (var4 <= GRADIENT_STOPS[var7][0]) {
                var5 = GRADIENT_STOPS[var7 - 1];
                var6 = GRADIENT_STOPS[var7];
                break;
            }
        }

        float var8 = var6[0] - var5[0];
        float var9 = var8 <= 0.0F ? 0.0F : (var4 - var5[0]) / var8;
        float var10 = (var5[4] + (var6[4] - var5[4]) * var9) * var3;
        if (var10 <= 0.004F) {
            return var0;
        }

        int var11 = var0 & 0xFF;
        int var12 = var0 >>> 8 & 0xFF;
        int var13 = var0 >>> 16 & 0xFF;
        float var14 = var5[1] + (var6[1] - var5[1]) * var9;
        float var15 = var5[2] + (var6[2] - var5[2]) * var9;
        float var16 = var5[3] + (var6[3] - var5[3]) * var9;
        int var17 = clamp255(Math.round(var11 + (var14 - var11) * var10));
        int var18 = clamp255(Math.round(var12 + (var15 - var12) * var10));
        int var19 = clamp255(Math.round(var13 + (var16 - var13) * var10));
        return var0 & 0xFF000000 | var19 << 16 | var18 << 8 | var17;
    }

    /**
     * Slice view height key = the EXACT cut Y + 512 (no 8-block quantization).
     * The cut plane must never sit below the player's feet: for 7 of every 8
     * heights the old bucket-bottom cut landed INSIDE the floor under the
     * player (their own gallery rendered as solid bright rock), and the later
     * "scan the whole 8-block band" band hack made one continuous cavern
     * flicker between bright rock and dark cavity following its 3D ceiling
     * relief - the cave shattered into "broken blocks painting whole chunks".
     * A single plane at the exact Y is what Xaero renders and reads cleanly.
     */
    public static int bucketOf(int var0) {
        return var0 + 512;
    }

    /**
     * Cut height of a slice bucket - the EXACT cut Y (the key stores Y + 512,
     * see {@link #bucketOf}).
     */
    public static int bucketCenterY(int var0) {
        return var0 - 512;
    }

    public static int sliceRange() {
        try {
            return UnboundConfig.CAVE_RANGE.get();
        } catch (NullPointerException | IllegalStateException var1) {
            return 10;
        }
    }

    private static boolean flag(net.neoforged.neoforge.common.ModConfigSpec.BooleanValue var0, boolean var1) {
        try {
            return var0.get();
        } catch (NullPointerException | IllegalStateException var2) {
            return var1;
        }
    }

    private static int intFlag(net.neoforged.neoforge.common.ModConfigSpec.IntValue var0, int var1) {
        try {
            return var0.get();
        } catch (NullPointerException | IllegalStateException var2) {
            return var1;
        }
    }

    public static int colorFor(ClientLevel var0, int var1, int var2, boolean var3, int var4) {
        return var3 ? getSliceColor(var0, var1, var2, var4) : getColor(var0, var1, var2);
    }

    public static int columnY(ClientLevel var0, int var1, int var2, int var3, boolean var4) {
        long var5 = ChunkPos.asLong(var1 >> 4, var2 >> 4);
        if (var4) {
            Map<Long, MapTileCache.Entry> var6 = SLICES.get(sliceBucket);
            if (var6 != null) {
                MapTileCache.Entry var7 = var6.get(var5);
                if (var7 != null) {
                    int var8 = var7.heights[(var2 & 15) << 4 | var1 & 15];
                    if (var8 != Integer.MIN_VALUE) {
                        return var8;
                    }
                }
            }

            return sliceTopY(var0, var1, var2, var3);
        } else {
            MapTileCache.Entry var6 = CHUNKS.get(var5);
            if (var6 != null) {
                int var7 = var6.heights[(var2 & 15) << 4 | var1 & 15];
                if (var7 != Integer.MIN_VALUE) {
                    // heights hold the Y of the TOP surface block itself
                    return var7;
                }
            }

            int var8 = surfaceHeight(var0, var1, var2);
            return var8 != Integer.MIN_VALUE ? var8 : var3;
        }
    }

    public static boolean contains(long var0) {
        return CHUNKS.containsKey(var0);
    }

    /**
     * True when a redraw of this chunk would come straight from the cache (cheap),
     * false when it would trigger a fresh heightmap / cut-lid computation.
     * Used to budget fresh work without throttling cache redraws.
     */
    public static boolean isCached(long var0, boolean var1, int var2) {
        if (var1) {
            Map<Long, MapTileCache.Entry> var3 = SLICES.get(var2);
            return var3 != null && var3.containsKey(var0);
        }
        return CHUNKS.containsKey(var0);
    }

    /**
     * True when the tile cache has no LIVE data for this chunk in the given view
     * mode (nothing computed yet, or only the persistent-store copy while the real
     * chunk is loaded) - such chunks are picked up by the periodic loaded-chunk scan
     * so freshly explored terrain reaches the maps without waiting for the player.
     */
    public static boolean needsRefresh(ClientLevel var0, long var1, boolean var2, int var3) {
        if (var2) {
            Map<Long, MapTileCache.Entry> var4 = SLICES.get(var3);
            return var4 == null || !var4.containsKey(var1);
        }
        MapTileCache.Entry var5 = CHUNKS.get(var1);
        if (var5 == null) {
            return true;
        }
        int var6 = ChunkPos.getX(var1);
        int var7 = ChunkPos.getZ(var1);
        return var5.stored && var0.getChunk(var6, var7, ChunkStatus.FULL, false) != null;
    }

    public static void clearAll() {
        CHUNKS.clear();
        SLICES.clear();
    }

    public static void invalidate(long var0) {
        MapTileCache.Entry var2 = CHUNKS.remove(var0);
        boolean var3 = false;

        for (Map var5 : SLICES.values()) {
            if (var5.remove(var0) != null) {
                var3 = true;
            }
        }

        if (var2 != null || var3) {
            MinimapTexture.markChunkDirty(var0);
            WorldMapCells.markChunkDirty(var0);
        }
    }

    public static int getColor(ClientLevel var0, int var1, int var2) {
        int var3 = var1 >> 4;
        int var4 = var2 >> 4;
        long var5 = ChunkPos.asLong(var3, var4);
        MapTileCache.Entry var7 = CHUNKS.get(var5);
        if (var7 == null) {
            var7 = computeEntry(var0, var3, var4, true);
            if (var7 == null) {
                return -15723752;
            }

            storeWithLimit(CHUNKS, var5, var7, 40000);
            MinimapTexture.markChunkDirty(var5);
            WorldMapCells.markChunkDirty(var5);
        } else if (var7.stored && var0.getChunk(var3, var4, ChunkStatus.FULL, false) != null) {
            var7 = computeEntry(var0, var3, var4, true);
            if (var7 == null) {
                return -15723752;
            }

            CHUNKS.put(var5, var7);
            MinimapTexture.markChunkDirty(var5);
            WorldMapCells.markChunkDirty(var5);
        } else if (!var7.shaded) {
            shadeEntry(var0, var3, var4, var7);
            MinimapTexture.markChunkDirty(var5);
            WorldMapCells.markChunkDirty(var5);
        }

        return enhance(var7.colors[(var2 & 15) << 4 | var1 & 15]);
    }

    public static int surfaceHeight(ClientLevel var0, int var1, int var2) {
        ChunkAccess var3 = var0.getChunk(var1 >> 4, var2 >> 4, ChunkStatus.FULL, false);
        return var3 == null ? Integer.MIN_VALUE : var3.getHeight(Types.WORLD_SURFACE, var1 & 15, var2 & 15);
    }

    /**
     * Computes a chunk entry from live world data (biome tint + water tint),
     * falling back to the persistent store when the chunk is unloaded.
     * Surface colors are full-bright Xaero-style: map color + biome tint + water
     * depth + hillshade. Day/night is a uniform draw-time dim (the draw-time night dim),
     * so tiles never depend on the time of day (the old per-column light capture
     * sampled the sky INSIDE opaque surface blocks and blacked out the land at night).
     * When {@code allowShade} is true the hillshade pass runs immediately;
     * neighbors needed by it are pre-computed raw (one level of recursion at most).
     */
    private static MapTileCache.Entry computeEntry(ClientLevel var0, int var1, int var2, boolean var3) {
        long var4 = ChunkPos.asLong(var1, var2);
        ChunkAccess var6 = var0.getChunk(var1, var2, ChunkStatus.FULL, false);
        if (var6 == null) {
            if (MapStore.isEnabled()) {
                MapStore.StoredChunk var7 = MapStore.storedChunk(var0.dimension(), var4);
                if (var7 != null) {
                    // legacy (pre-1.11.0) region data is repaired by MapStore.loadRegion
                    // right when it is read, so stored colors are safe to render as-is.
                    // Stored light nibbles (v2 format) were captured with the old buggy
                    // sampling and are intentionally ignored: surface tiles are full-bright
                    return new MapTileCache.Entry(
                        var7.colors(), var7.heights() == null ? defaultHeights() : var7.heights(), true, true
                    );
                }
            }

            return null;
        } else {
            boolean var29 = flag(UnboundConfig.BIOME_SHADING, true);
            boolean var30 = flag(UnboundConfig.LIGHT_SHADING, true);
            boolean var31 = MapStore.isEnabled();
            boolean gradOn = flag(UnboundConfig.TERRAIN_GRADIENT, true);
            int[] var32 = new int[256];
            int[] var33 = new int[256];
            int var9 = var1 << 4;
            int var10 = var2 << 4;
            int var11 = var0.getMinBuildHeight();
            MutableBlockPos var12 = new MutableBlockPos();

            for (int var13 = 0; var13 < 16; var13++) {
                for (int var14 = 0; var14 < 16; var14++) {
                    int var15 = var13 << 4 | var14;
                    // ChunkAccess.getHeight already returns the Y of the TOP non-air
                    // block (getFirstAvailable - 1) - the old code read h-1 and painted
                    // the block UNDER the surface (dirt where grass should be, air
                    // under tree canopies -> black "unknown" forest, "chunk noise").
                    int var16 = var6.getHeight(Types.WORLD_SURFACE, var14, var13);
                    var33[var15] = var16;
                    if (var16 <= var11) {
                        var32[var15] = -15723752;
                    } else {
                        var12.set(var9 + var14, var16, var10 + var13);
                        BlockState var17 = var6.getBlockState(var12);
                        if (var17.isAir()) {
                            var32[var15] = -15723752;
                        } else {
                            // ONE shared pipeline with the cave slice (see
                            // terrainBlockColor): map color + biome tint +
                            // water depth + lava glow
                            int var19 = terrainBlockColor(var0, var6, var12, var17, var29);
                            // elevation gradient on land columns: water already
                            // darkens with depth, lava must stay vivid
                            if (gradOn && var17.getFluidState().isEmpty()) {
                                var19 = applyHeightGradient(var19, var16, heightAnchor(var0), 1.0F);
                            }

                            var32[var15] = var19;
                        }
                    }
                }
            }

            MapTileCache.Entry var34 = new MapTileCache.Entry(var32, var33, false, false);
            if (var3) {
                shadeEntry(var0, var1, var2, var34);
            }

            if (var3 && var31) {
                recordToStore(var0, var1, var2, var4, var34);
            }

            if (var3) {
                // the neighbors may have been shaded while this chunk did not exist
                // yet (their border columns fell back to their own height). Re-shade
                // them once so slopes continue smoothly across the border - the old
                // clamped sampling burned permanent "chunk lines" into both maps.
                markNeighborsForReshade(var1, var2);
            }

            return var34;
        }
    }

    /** Persists a chunk: hillshaded full-bright colors, surface biome palette, discovery sets. */
    private static void recordToStore(
        ClientLevel var0, int var1, int var2, long var3, MapTileCache.Entry var5
    ) {
        java.util.Set<String> var7 = new java.util.HashSet<>();
        java.util.Set<String> var8 = new java.util.HashSet<>();
        MutableBlockPos var9 = new MutableBlockPos();
        java.util.List<String> var10 = new java.util.ArrayList<>();
        java.util.Map<String, Byte> var11 = new java.util.HashMap<>();
        byte[] var12 = new byte[256];

        try {
            for (int var13 = 0; var13 < 256; var13++) {
                if (var5.colors[var13] != -15723752) {
                    // the top surface block itself (heights hold its Y)
                    var9.set((var1 << 4) + (var13 & 15), Math.max(var5.heights[var13], var0.getMinBuildHeight()), (var2 << 4) + (var13 >> 4));
                    BlockState var14 = var0.getBlockState(var9);
                    var8.add(BuiltInRegistries.BLOCK.getKey(var14.getBlock()).toString());
                    String var15 = var0.getBiome(var9)
                        .unwrapKey()
                        .map(var0x -> var0x.location().toString())
                        .orElse(null);
                    if (var15 != null) {
                        var7.add(var15);
                        Byte var16 = var11.get(var15);
                        if (var16 == null) {
                            if (var10.size() < 255) {
                                var16 = (byte)var10.size();
                                var10.add(var15);
                                var11.put(var15, var16);
                            } else {
                                var16 = (byte)254;
                            }
                        }

                        var12[var13] = var16;
                    } else {
                        var12[var13] = -1;
                    }
                } else {
                    var12[var13] = -1;
                }
            }
        } catch (Exception var17) {
            var7.clear();
            var8.clear();
        }

        MapStore.chunkExplored(
            var0.dimension(), var3, var5.colors, var5.heights, var10.toArray(new String[0]), var12, var7, var8
        );
    }

    private static int[] defaultHeights() {
        int[] var0 = new int[256];
        java.util.Arrays.fill(var0, Integer.MIN_VALUE);
        return var0;
    }

    /**
     * Hillshade: darkens slopes facing away, brightens slopes facing the light.
     * Deterministic AND seamless: border columns sample the REAL height of the
     * adjacent chunk (from its cached tile or, if it is loaded but not yet
     * computed, straight from its heightmap). Heights never depend on the scan
     * order, so a chunk shades identically no matter when it is drawn; the old
     * clamped-to-own-border sampling left every border column unshaded and
     * burned visible chunk grid lines into hillsides.
     */
    private static void shadeEntry(ClientLevel var0, int var1, int var2, MapTileCache.Entry var3) {
        if (var3.shaded) {
            return;
        }
        boolean var4 = flag(UnboundConfig.TERRAIN_SHADING, true);
        if (var4) {
            if (var3.base == null) {
                var3.base = var3.colors.clone();
            }

            for (int var9 = 0; var9 < 16; var9++) {
                for (int var10 = 0; var10 < 16; var10++) {
                    int var11 = var9 << 4 | var10;
                    if (var3.base[var11] != -15723752) {
                        int var12 = var3.heights[var11];
                        int var13 = crossBorderHeight(var0, var1, var2, var10 - 1, var9 - 1, var12, var3);
                        float var14 = Mth.clamp(1.0F + (float)(var12 - var13) * 0.06F, 0.82F, 1.18F);
                        int var15 = crossBorderHeight(var0, var1, var2, var10 + 1, var9, var12, var3);
                        int var16 = crossBorderHeight(var0, var1, var2, var10, var9 + 1, var12, var3);
                        if (Math.abs(var12 - var15) >= 4 || Math.abs(var12 - var16) >= 4) {
                            var14 *= 0.94F;
                        }

                        if (var14 != 1.0F) {
                            var3.colors[var11] = shadeAbgr(var3.base[var11], var14);
                        } else {
                            var3.colors[var11] = var3.base[var11];
                        }
                    } else {
                        var3.colors[var11] = -15723752;
                    }
                }
            }
        }

        var3.shaded = true;
    }

    /** Marks the four direct neighbors for one re-shade now that this chunk exists. */
    private static void markNeighborsForReshade(int var0, int var1) {
        int[][] var2 = new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

        for (int[] var6 : var2) {
            int var7 = var0 + var6[0];
            int var8 = var1 + var6[1];
            long var9 = ChunkPos.asLong(var7, var8);
            MapTileCache.Entry var10 = CHUNKS.get(var9);
            // stored entries keep their on-disk (already shaded) colors - re-running
            // the shade pass on them would darken them a second time
            if (var10 != null && var10.shaded && !var10.stored && var10.base != null) {
                var10.shaded = false;
                System.arraycopy(var10.base, 0, var10.colors, 0, 256);
                MinimapTexture.markChunkDirty(var9);
                WorldMapCells.markChunkDirty(var9);
            }
        }
    }

    /**
     * Height of a local column; (lx, lz) may leave 0..15 by ONE block into the
     * direct neighbor chunk. Falls back to the chunk's cached tile, then to the
     * live neighbor heightmap, then to the own height (never triggers neighbor
     * tile computation, so shading stays cheap and non-recursive).
     */
    private static int crossBorderHeight(ClientLevel var0, int var1, int var2, int var3, int var4, int var5, MapTileCache.Entry var6) {
        int var7 = Mth.clamp(var3, -1, 16);
        int var8 = Mth.clamp(var4, -1, 16);
        if (var7 >= 0 && var7 < 16 && var8 >= 0 && var8 < 16) {
            int var9 = var6.heights[var8 << 4 | var7];
            return var9 != Integer.MIN_VALUE ? var9 : var5;
        }

        int var10 = var1 + (var7 >> 4);
        int var11 = var2 + (var8 >> 4);
        int var12 = var7 & 15;
        int var13 = var8 & 15;
        MapTileCache.Entry var14 = CHUNKS.get(ChunkPos.asLong(var10, var11));
        if (var14 != null) {
            int var15 = var14.heights[var13 << 4 | var12];
            return var15 != Integer.MIN_VALUE ? var15 : var5;
        }

        ChunkAccess var16 = var0.getChunk(var10, var11, ChunkStatus.FULL, false);
        if (var16 != null) {
            int var17 = var16.getHeight(Types.WORLD_SURFACE, var12, var13);
            return var17 != Integer.MIN_VALUE ? var17 : var5;
        }

        return var5;
    }

    /**
     * Slice hillshade - the SAME slope math as the surface view, applied to the
     * cut-lid heights: walls sit at the cut plane, cavity floors at their floor
     * Y, so every wall/floor boundary and every bit of floor relief gets the
     * surface-style slope shading and cavities pop out of the rock like they do
     * on the surface map. Neighbor samples come from the same slice bucket;
     * unknown neighbors fall back to the own height (re-shaded later, see
     * {@link #markSliceNeighborsForReshade}), so shading stays cheap and
     * non-recursive.
     */
    private static void shadeSliceChunk(ClientLevel var0, int var1, int var2, int var3, MapTileCache.Entry var4) {
        if (var4.shaded) {
            return;
        }

        boolean var5 = flag(UnboundConfig.TERRAIN_SHADING, true);
        if (var5) {
            if (var4.base == null) {
                var4.base = var4.colors.clone();
            }

            for (int var9 = 0; var9 < 16; var9++) {
                for (int var10 = 0; var10 < 16; var10++) {
                    int var11 = var9 << 4 | var10;
                    if (var4.base[var11] != -15723752 && var4.heights[var11] != Integer.MIN_VALUE) {
                        int var12 = var4.heights[var11];
                        int var13 = sliceNeighborHeight(var0, var1, var2, var10 - 1, var9 - 1, var12, var3, var4);
                        float var14 = Mth.clamp(1.0F + (float)(var12 - var13) * 0.06F, 0.82F, 1.18F);
                        int var15 = sliceNeighborHeight(var0, var1, var2, var10 + 1, var9, var12, var3, var4);
                        int var16 = sliceNeighborHeight(var0, var1, var2, var10, var9 + 1, var12, var3, var4);
                        if (Math.abs(var12 - var15) >= 4 || Math.abs(var12 - var16) >= 4) {
                            var14 *= 0.94F;
                        }

                        if (var14 != 1.0F) {
                            var4.colors[var11] = shadeAbgr(var4.base[var11], var14);
                        } else {
                            var4.colors[var11] = var4.base[var11];
                        }
                    } else if (var4.base != null) {
                        var4.colors[var11] = var4.base[var11];
                    }
                }
            }
        }

        var4.shaded = true;
    }

    /** Height of a slice column; (lx, lz) may leave 0..15 by ONE block into the direct neighbor chunk. */
    private static int sliceNeighborHeight(ClientLevel var0, int var1, int var2, int var3, int var4, int var5, int var6, MapTileCache.Entry var7) {
        int var8 = Mth.clamp(var3, -1, 16);
        int var9 = Mth.clamp(var4, -1, 16);
        if (var8 >= 0 && var8 < 16 && var9 >= 0 && var9 < 16) {
            int var10 = var7.heights[var9 << 4 | var8];
            return var10 != Integer.MIN_VALUE ? var10 : var5;
        }

        Map<Long, MapTileCache.Entry> var11 = SLICES.get(var6);
        if (var11 != null) {
            MapTileCache.Entry var12 = var11.get(ChunkPos.asLong(var1 + (var8 >> 4), var2 + (var9 >> 4)));
            if (var12 != null) {
                int var13 = var12.heights[(var9 & 15) << 4 | var8 & 15];
                return var13 != Integer.MIN_VALUE ? var13 : var5;
            }
        }

        return var5;
    }

    /** Re-shades the four direct neighbors IN THE SAME SLICE BUCKET now that this chunk exists. */
    private static void markSliceNeighborsForReshade(int var0, int var1, int var2) {
        int[][] var3 = new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        Map<Long, MapTileCache.Entry> var4 = SLICES.get(var2);
        if (var4 == null) {
            return;
        }

        for (int[] var8 : var3) {
            long var9 = ChunkPos.asLong(var0 + var8[0], var1 + var8[1]);
            MapTileCache.Entry var10 = var4.get(var9);
            // stored entries keep their approximation colors - re-running the
            // shade pass on them would darken them a second time
            if (var10 != null && var10.shaded && !var10.stored && var10.base != null) {
                var10.shaded = false;
                System.arraycopy(var10.base, 0, var10.colors, 0, 256);
                MinimapTexture.markChunkDirty(var9);
                WorldMapCells.markChunkDirty(var9);
            }
        }
    }

    public static int getSliceColor(ClientLevel var0, int var1, int var2, int var3) {
        int var4 = var1 >> 4;

        int var5 = var2 >> 4;
        long var6 = ChunkPos.asLong(var4, var5);
        Map<Long, MapTileCache.Entry> var8 = SLICES.computeIfAbsent(var3, var0x -> new LinkedHashMap<>(512, 0.75F, true));
        MapTileCache.Entry var9 = var8.get(var6);
        if (var9 == null) {
            var9 = computeSliceChunk(var0, var4, var5, var3);
            if (var9 == null) {
                return -15723752;
            }

            storeWithLimit(var8, var6, var9, 8000);
        } else if (!var9.shaded) {
            // deferred hillshade: a neighbor was (re-)shaded after this entry
            // and reset it for one more pass - finish it on the first redraw,
            // exactly like the surface view does in getColor
            shadeSliceChunk(var0, var4, var5, var3, var9);
        }

        // CRITICAL FIX: the pixel row comes from the LOCAL block Z (var2 & 15).
        // The old code indexed with the CHUNK Z (var5 & 15), so every pixel of a
        // chunk read the SAME tile row - the whole cave view smeared into
        // horizontal streaks while the surface view (getColor) kept its correct
        // index and rendered fine.
        return enhance(var9.colors[(var2 & 15) << 4 | var1 & 15]);
    }

    public static int sliceTopY(ClientLevel var0, int var1, int var2, int var3) {
        ChunkAccess var4 = var0.getChunk(var1 >> 4, var2 >> 4, ChunkStatus.FULL, false);
        if (var4 == null) {
            return var3;
        } else {
            int var5 = sliceTop(var4, var1 & 15, var2 & 15, bucketCenterY(sliceBucket), var0.getMinBuildHeight(), var0.getMaxBuildHeight());
            return var5 == Integer.MIN_VALUE ? var3 : var5;
        }
    }

    private static MapTileCache.Entry computeSliceChunk(ClientLevel var0, int var1, int var2, int var3) {
        ChunkAccess var4 = var0.getChunk(var1, var2, ChunkStatus.FULL, false);
        if (var4 == null) {
            // explored-but-unloaded chunk: rebuild the cut-lid view approximately
            // from the persistent store, so cave mode covers the WHOLE explored map
            return storedSliceChunk(var0, var1, var2, var3);
        } else {
            boolean var29 = flag(UnboundConfig.LIGHT_SHADING, true);
            boolean biomeShade = flag(UnboundConfig.BIOME_SHADING, true);
            boolean gradOn = flag(UnboundConfig.TERRAIN_GRADIENT, true);
            int var5 = bucketCenterY(var3);
            int var6 = sliceRange() * 2;
            int var7 = var0.getMinBuildHeight();
            int var8 = var0.getMaxBuildHeight();
            // Xaero-style single-plane cut at the EXACT view height: everything
            // above the cut is removed ("the roof is cut off"), and the coloring
            // is the SAME pipeline as the surface view (terrainBlockColor: map
            // color + biome tint + water depth + lava glow), so caves read like
            // the normal map. Solid-at-cut columns show the cross-section wall,
            // air-at-cut columns show the cavity floor below at any depth.
            int[] var9 = new int[256];
            int[] var10 = new int[256];
            int var11 = var1 << 4;
            int var12 = var2 << 4;
            MutableBlockPos var13 = new MutableBlockPos();
            int var30 = Mth.clamp(var5, var7, var8 - 1);

            for (int var14 = 0; var14 < 16; var14++) {
                for (int var15 = 0; var15 < 16; var15++) {
                    int var16 = var15 << 4 | var14;
                    var13.set(var11 + var14, var30, var12 + var15);
                    BlockState var17 = var4.getBlockState(var13);
                    if (!var17.isAir()) {
                        // solid at the cut: the cross-section wall. Real block
                        // color from the shared pipeline, slightly lifted so the
                        // cut reads as a wall; the stone family blends toward the
                        // bright rock tone (raw deepslate/sculk/tuff map colors
                        // are near-black and would read as holes) while ores
                        // keep their sparkle.
                        var9[var16] = var30;
                        int var36 = terrainBlockColor(var0, var4, var13, var17, biomeShade);
                        if (var17.getFluidState().isEmpty()) {
                            MapColor var41 = var17.getMapColor(var4, var13);
                            if ((var41 == MapColor.STONE || var41 == MapColor.DEEPSLATE)
                                && !(var17.getBlock() instanceof DropExperienceBlock)) {
                                var36 = blendAbgr(var36, rockTone(var0, var30), 0.55F);
                            }

                            var36 = shadeAbgr(var36, 1.10F);
                        }

                        var10[var16] = var36;
                    } else {
                        // air at the cut: the roof is cut away - open cavity.
                        // Render the floor below (any depth) in its REAL colors,
                        // lit by the light inside the cavity itself.
                        int var34 = sliceTop(var4, var14, var15, var30, var7, var8);
                        var9[var16] = var34;
                        if (var34 == Integer.MIN_VALUE) {
                            var10[var16] = -15723752;
                        } else {
                            var13.set(var11 + var14, var34, var12 + var15);
                            BlockState var35 = var4.getBlockState(var13);
                            int var36 = terrainBlockColor(var0, var4, var13, var35, biomeShade);
                            float var37;
                            if (!var35.getFluidState().isEmpty()) {
                                // water/lava floors read like the surface map (the
                                // pipeline already applied lava glow / water depth)
                                var37 = sliceDepthDim(var34, var5, var6);
                            } else {
                                // cave floor: readable mid-dark by default (the
                                // tunnel shape reads against the lifted walls),
                                // torch/sky light brightens it - capped below the
                                // wall tone so fully lit floors never dissolve
                                // into the surrounding rock
                                float var39 = 0.5F;
                                if (var29) {
                                    var13.set(var11 + var14, Math.min(var34 + 1, var8 - 1), var12 + var15);
                                    int var40 = var0.getBrightness(LightLayer.SKY, var13);
                                    int var42 = var0.getBrightness(LightLayer.BLOCK, var13);
                                    var39 = Math.max(var39, Math.min(0.9F, lightShade(var0, var40, var42)));
                                }

                                var37 = var39 * sliceDepthDim(var34, var5, var6);
                            }

                            var36 = shadeAbgr(var36, var37);
                            // subtle ABSOLUTE height cue inside the cave (half
                            // strength): a floor at Y=20 and a floor at Y=50 keep
                            // different tones even inside one view
                            if (gradOn && var35.getFluidState().isEmpty()) {
                                var36 = applyHeightGradient(var36, var34, heightAnchor(var0), 0.6F);
                            }

                            var10[var16] = var36;
                        }
                    }
                }
            }

            MapTileCache.Entry var44 = new MapTileCache.Entry(var10, var9, false, false);
            shadeSliceChunk(var0, var1, var2, var3, var44);
            // the neighbors may have been shaded while this chunk did not exist
            // yet (their border columns fell back to their own height) - re-shade
            // them once so the slice shading continues smoothly across the border
            markSliceNeighborsForReshade(var1, var2, var3);
            return var44;
        }
    }

    /** Depth fade used by the cut-lid view: columns far below the view height get slightly darker. */
    private static float sliceDepthDim(int var0, int var1, int var2) {
        return 1.0F - Math.min((float)Math.abs(var0 - var1) / (float)Math.max(1, var2), 1.0F) * 0.22F;
    }

    /** Linear blend of two ABGR colors; t = 0 returns a, t = 1 returns b. */
    private static int blendAbgr(int var0, int var1, float var2) {
        int var3 = clamp255(Math.round((float)(var0 & 0xFF) + ((float)(var1 & 0xFF) - (float)(var0 & 0xFF)) * var2));
        int var4 = clamp255(Math.round((float)(var0 >>> 8 & 0xFF) + ((float)(var1 >>> 8 & 0xFF) - (float)(var0 >>> 8 & 0xFF)) * var2));
        int var5 = clamp255(Math.round((float)(var0 >>> 16 & 0xFF) + ((float)(var1 >>> 16 & 0xFF) - (float)(var0 >>> 16 & 0xFF)) * var2));
        return var0 & 0xFF000000 | var5 << 16 | var4 << 8 | var3;
    }

    /** Uniform bright rock tone per dimension/depth class (ABGR). */
    private static int rockTone(ClientLevel var0, int var1) {
        if (var0.dimension() == Level.NETHER) {
            return 0xFF3A303F;
        }

        if (var0.dimension() == Level.END) {
            return 0xFFB8D2D5;
        }

        return var1 < 0 ? 0xFF74747A : 0xFFA2A2A4;
    }

    /**
     * Approximate cut-lid for an explored-but-unloaded chunk, rebuilt from the
     * persistent store (colors + surface heights, no per-block data), aligned
     * with the live single-plane slice:
     * - columns whose real surface lies BELOW the cut height are open at the
     *   cut (valleys, ocean floors, caverns the store cannot see) - the floor
     *   shows the stored surface color, capped dark like the live cavity path;
     * - columns whose terrain rises to or above the cut height are solid at
     *   the cut: neutral stone/deepslate grey, matching the full-bright rock
     *   of live chunks.
     */
    private static MapTileCache.Entry storedSliceChunk(ClientLevel var0, int var1, int var2, int var3) {
        if (!MapStore.isEnabled()) {
            return null;
        }

        MapStore.StoredChunk var4 = MapStore.storedChunk(var0.dimension(), ChunkPos.asLong(var1, var2));
        if (var4 == null) {
            return null;
        }

        int var5 = bucketCenterY(var3);
        int var6 = sliceRange() * 2;
        int var11 = rockTone(var0, var5);
        int[] var7 = var4.colors();
        int[] var8 = var4.heights() == null ? defaultHeights() : var4.heights();
        int[] var9 = new int[256];
        int[] var10 = new int[256];

        boolean gradOn = flag(UnboundConfig.TERRAIN_GRADIENT, true);
        int anchor = heightAnchor(var0);

        for (int var12 = 0; var12 < 256; var12++) {
            if (var7[var12] != UNKNOWN && var8[var12] != Integer.MIN_VALUE) {
                int var13 = var8[var12];
                var10[var12] = var13;
                if (var13 >= var5) {
                    // terrain reaches the cut plane: solid at the cut, same grey as live rock
                    var9[var12] = shadeAbgr(var11, 0.9F);
                } else {
                    // open at the cut down to the stored surface: cavity floor.
                    // The store keeps the SURFACE colors (no per-block data), so
                    // this stays an approximation - it is dimmed like an unlit
                    // live floor (0.55 base x depth fade) so stored and live
                    // cavities keep the same readable darkness, and the same
                    // subtle elevation gradient is applied on top.
                    int var14 = shadeAbgr(var7[var12], 0.55F * sliceDepthDim(var13, var5, var6));
                    if (gradOn) {
                        var14 = applyHeightGradient(var14, var13, anchor, 0.6F);
                    }

                    var9[var12] = var14;
                }
            } else {
                var9[var12] = UNKNOWN;
                var10[var12] = Integer.MIN_VALUE;
            }
        }

        return new MapTileCache.Entry(var9, var10, true, false);
    }

    /**
     * The "cut lid" scan: everything above {@code cutY} is treated as removed.
     * Returns the first non-air block at or below {@code cutY}, scanning all the
     * way to the world bottom, so cave floors, ravines and lava lakes far below
     * the view height stay visible. Whole air-only sections are skipped.
     */
    private static int sliceTop(ChunkAccess var0, int var1, int var2, int var3, int var4, int var5) {
        LevelChunkSection[] var6 = var0.getSections();
        if (var6 == null || var6.length == 0) {
            return Integer.MIN_VALUE;
        } else {
            int var7 = Math.min(var3, var5 - 1);
            int var8 = Mth.clamp((var7 - var4) >> 4, 0, var6.length - 1);
            MutableBlockPos var9 = new MutableBlockPos();

            for (int var10 = var8; var10 >= 0; var10--) {
                LevelChunkSection var11 = var6[var10];
                if (var11 != null && !var11.hasOnlyAir()) {
                    int var12 = var4 + (var10 << 4);

                    for (int var13 = Math.min(var7, var12 + 15); var13 >= var12; var13--) {
                        var9.set(var1, var13, var2);
                        if (!var0.getBlockState(var9).isAir()) {
                            return var13;
                        }
                    }
                }
            }

            return Integer.MIN_VALUE;
        }
    }

    private static void storeWithLimit(Map<Long, MapTileCache.Entry> var0, long var1, MapTileCache.Entry var3, int var4) {
        if (var0.size() >= var4) {
            int var5 = var4 / 4;
            Iterator<Map.Entry<Long, MapTileCache.Entry>> var6 = var0.entrySet().iterator();

            while (var5-- > 0 && var6.hasNext()) {
                var6.next();
                var6.remove();
            }
        }

        var0.put(var1, var3);
    }

    public static int mapColorAbgr(MapColor var0) {
        int var1 = var0.col & 16777215;
        int var2 = var1 >> 16 & 0xFF;
        int var3 = var1 >> 8 & 0xFF;
        int var4 = var1 & 0xFF;
        return 0xFF000000 | var4 << 16 | var3 << 8 | var2;
    }

    public static int abgrFromRgb(int var0) {
        int var1 = var0 & 16777215;
        int var2 = var1 >> 16 & 0xFF;
        int var3 = var1 >> 8 & 0xFF;
        int var4 = var1 & 0xFF;
        return 0xFF000000 | var4 << 16 | var3 << 8 | var2;
    }

    /** Multiplies an ABGR color by an RGB tint (used for biome grass/foliage colors). */
    public static int tintAbgr(int var0, int var1) {
        int var2 = (var1 >> 16 & 0xFF) * (var0 & 0xFF) / 255;
        int var3 = (var1 >> 8 & 0xFF) * (var0 >>> 8 & 0xFF) / 255;
        int var4 = (var1 & 0xFF) * (var0 >>> 16 & 0xFF) / 255;
        return var0 & 0xFF000000 | var4 << 16 | var3 << 8 | var2;
    }

    public static int shadeAbgr(int var0, float var1) {
        int var2 = var0 & 0xFF000000;
        int var3 = Math.min(255, (int)((var0 & 0xFF) * var1));
        int var4 = Math.min(255, (int)((var0 >>> 8 & 0xFF) * var1));
        int var5 = Math.min(255, (int)((var0 >>> 16 & 0xFF) * var1));
        return var2 | var5 << 16 | var4 << 8 | var3;
    }

    public static final class Entry {
        public final int[] colors;
        public final int[] heights;
        public boolean shaded;
        public final boolean stored;
        /** Unshaded colors (lazily cloned on first shade) so a re-shade never darkens twice. */
        public int[] base;

        Entry(int[] var1, int[] var2) {
            this.colors = var1;
            this.heights = var2;
            this.shaded = true;
            this.stored = false;
            this.base = null;
        }

        Entry(int[] var1, int[] var2, boolean var3, boolean var4) {
            this.colors = var1;
            this.heights = var2;
            this.shaded = var3;
            this.stored = var4;
            this.base = null;
        }
    }
}
