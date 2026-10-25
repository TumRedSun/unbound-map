package net.unbound.maps.mapdata;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.unbound.maps.UnboundMapsMod;
import net.unbound.maps.util.StoragePaths;

/**
 * Persistent store for explored map data, kept per server/world and per dimension.
 * <p>
 * Chunks are packed into 32x32-chunk region files ({@code r.<rx>.<rz>.bin}, gzip-compressed
 * binary: 256 final tile colors + 256 surface heights per chunk). Discovered biomes and
 * blocks go into a human-readable {@code discovery.json}. Everything is written lazily:
 * dirty regions are flushed by {@link #saveDirty()} on an autosave timer, on dimension
 * change and on logout.
 */
public final class MapStore {
    private static final int REGION_BITS = 5;
    private static final int REGION_CHUNKS = 1 << REGION_BITS;
    private static final int MAX_LOADED_REGIONS = 16;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static ResourceKey<Level> dimension;
    private static Path dimensionDir;
    private static final Map<Long, Region> REGIONS = new LinkedHashMap<>(16, 0.75F, true);
    private static final LongOpenHashSet DIRTY_REGIONS = new LongOpenHashSet();
    private static final Set<String> BIOMES = new TreeSet<>();
    private static final Set<String> BLOCKS = new TreeSet<>();
    private static boolean discoveryDirty;
    private static boolean enabled = true;

    private MapStore() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Switches the active dimension (saving any dirty data of the previous one). */
    public static void setDimension(ResourceKey<Level> var0) {
        if (sameDimension(var0)) {
            return;
        }
        saveDirty();
        REGIONS.clear();
        DIRTY_REGIONS.clear();
        BIOMES.clear();
        BLOCKS.clear();
        dimension = var0;
        dimensionDir = null;
        enabled = UnboundConfigSafe.persistEnabled();
        if (!enabled) {
            return;
        }
        Path var1 = StoragePaths.map(Minecraft.getInstance());
        if (var0 != null && var1 != null) {
            String var2 = var0.location().getNamespace() + "__" + var0.location().getPath();
            dimensionDir = var1.resolve(var2);
            loadDiscovery();
        }
    }

    public static void setEnabled(boolean var0) {
        enabled = var0;
    }

    private static boolean sameDimension(ResourceKey<Level> var0) {
        return dimension == null ? var0 == null : (var0 != null && dimension.location().equals(var0.location()));
    }

    private static long regionKey(int var0, int var1) {
        return ((long)(var0 >> REGION_BITS) & 0xFFFFFFFFL) << 32 | (long)(var1 >> REGION_BITS) & 0xFFFFFFFFL;
    }

    /** Records a freshly computed live chunk (final colors incl. shading) plus all biomes/blocks seen in it. */
    public static void chunkExplored(
        ResourceKey<Level> var0, long var1, int[] var2, int[] var3, java.util.Set<String> var4, java.util.Set<String> var5
    ) {
        if (!enabled || var0 == null) {
            return;
        }
        if (!sameDimension(var0)) {
            setDimension(var0);
        }
        if (dimensionDir == null) {
            return;
        }
        int var6 = ChunkPos.getX(var1);
        int var7 = ChunkPos.getZ(var1);
        long var8 = regionKey(var6, var7);
        Region var10 = region(var8);
        var10.chunks.put(var1, new ChunkData(var2.clone(), var3.clone()));
        var10.dirty = true;
        DIRTY_REGIONS.add(var8);
        if (!var4.isEmpty()) {
            BIOMES.addAll(var4);
            discoveryDirty = true;
        }
        if (!var5.isEmpty()) {
            BLOCKS.addAll(var5);
            discoveryDirty = true;
        }
    }

    /** Returns the stored colors of an unloaded chunk, or null when never explored. */
    public static int[] storedColors(ResourceKey<Level> var0, long var1) {
        if (!enabled || var0 == null || !sameDimension(var0) || dimensionDir == null) {
            return null;
        }
        Region var2 = region(regionKey(ChunkPos.getX(var1), ChunkPos.getZ(var1)));
        ChunkData var3 = var2.chunks.get(var1);
        return var3 == null ? null : var3.colors;
    }

    /** Returns the stored heights of an unloaded chunk, or null when never explored. */
    public static int[] storedHeights(ResourceKey<Level> var0, long var1) {
        if (!enabled || var0 == null || !sameDimension(var0) || dimensionDir == null) {
            return null;
        }
        Region var2 = region(regionKey(ChunkPos.getX(var1), ChunkPos.getZ(var1)));
        ChunkData var3 = var2.chunks.get(var1);
        return var3 == null ? null : var3.heights;
    }

    private static Region region(long var0) {
        Region var2 = REGIONS.get(var0);
        if (var2 == null) {
            var2 = loadRegion(var0);
            REGIONS.put(var0, var2);
            evictRegions();
        }
        return var2;
    }

    private static void evictRegions() {
        while (REGIONS.size() > MAX_LOADED_REGIONS) {
            var var0 = REGIONS.entrySet().iterator();
            Map.Entry<Long, Region> var1 = (Map.Entry<Long, Region>)var0.next();
            if (DIRTY_REGIONS.contains((Long)var1.getKey())) {
                saveRegion((Long)var1.getKey(), (Region)var1.getValue());
                DIRTY_REGIONS.remove((Long)var1.getKey());
            }
            var0.remove();
        }
    }

    private static Path regionFile(long var0) {
        int var1 = (int)(var0 >> 32);
        int var2 = (int)(var0 & 0xFFFFFFFFL);
        return dimensionDir.resolve("r." + var1 + "." + var2 + ".bin");
    }

    private static Region loadRegion(long var0) {
        Region var1 = new Region();
        if (dimensionDir == null) {
            return var1;
        }
        Path var2 = regionFile(var0);
        if (!Files.exists(var2)) {
            return var1;
        }
        try {
            DataInputStreamLite var3 = new DataInputStreamLite(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(var2)), 65536));
            int var4 = var3.readInt();
            if (var4 != 1) {
                var3.close();
                return var1;
            }
            int var5 = var3.readInt();
            for (int var6 = 0; var6 < var5; var6++) {
                long var7 = var3.readLong();
                int[] var9 = new int[256];
                for (int var10 = 0; var10 < 256; var10++) {
                    var9[var10] = var3.readInt();
                }
                int[] var11 = new int[256];
                for (int var12 = 0; var12 < 256; var12++) {
                    var11[var12] = var3.readShort();
                }
                var1.chunks.put(var7, new ChunkData(var9, var11));
            }
            var3.close();
        } catch (Exception var13) {
            UnboundMapsMod.LOGGER.warn("Unbound Maps: failed to read map region {}: {}", var2.getFileName(), var13.toString());
        }
        return var1;
    }

    private static void saveRegion(long var0, Region var1) {
        if (dimensionDir == null) {
            return;
        }
        Path var2 = regionFile(var0);
        Path var3 = dimensionDir.resolve(var2.getFileName() + ".tmp");
        try {
            Files.createDirectories(dimensionDir);
            DataOutputStreamLite var4 = new DataOutputStreamLite(
                new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(var3)), 65536)
            );
            var4.writeInt(1);
            var4.writeInt(var1.chunks.size());
            for (Map.Entry<Long, ChunkData> var6 : var1.chunks.entrySet()) {
                var4.writeLong((Long)var6.getKey());
                int[] var7 = ((ChunkData)var6.getValue()).colors;
                for (int var10 = 0; var10 < 256; var10++) {
                    var4.writeInt(var7[var10]);
                }
                int[] var8 = ((ChunkData)var6.getValue()).heights;
                for (int var11 = 0; var11 < 256; var11++) {
                    var4.writeShort(var8[var11]);
                }
            }
            var4.flush();
            var4.close();
            Files.move(var3, var2, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception var9) {
            UnboundMapsMod.LOGGER.warn("Unbound Maps: failed to write map region {}: {}", var2.getFileName(), var9.toString());
        }
    }

    /** Flushes dirty regions and the discovery index. Called on autosave, dimension change and logout. */
    public static void saveDirty() {
        if (!enabled || dimension == null) {
            return;
        }
        if (!DIRTY_REGIONS.isEmpty()) {
            for (long var0 : DIRTY_REGIONS.toLongArray()) {
                Region var1 = REGIONS.get(var0);
                if (var1 != null && var1.dirty) {
                    saveRegion(var0, var1);
                    var1.dirty = false;
                }
            }
            DIRTY_REGIONS.clear();
        }
        if (discoveryDirty) {
            saveDiscovery();
            discoveryDirty = false;
        }
    }

    private static Path discoveryFile() {
        return dimensionDir.resolve("discovery.json");
    }

    private static void loadDiscovery() {
        Path var0 = discoveryFile();
        if (!Files.exists(var0)) {
            return;
        }
        try (BufferedReader var1 = Files.newBufferedReader(var0, StandardCharsets.UTF_8)) {
            JsonObject var2 = JsonParser.parseReader(var1).getAsJsonObject();
            BIOMES.clear();
            BLOCKS.clear();
            if (var2.has("biomes")) {
                for (JsonElement var3 : var2.getAsJsonArray("biomes")) {
                    BIOMES.add(var3.getAsString());
                }
            }
            if (var2.has("blocks")) {
                for (JsonElement var3x : var2.getAsJsonArray("blocks")) {
                    BLOCKS.add(var3x.getAsString());
                }
            }
        } catch (Exception var5) {
            UnboundMapsMod.LOGGER.warn("Unbound Maps: failed to read discovery.json: {}", var5.toString());
        }
    }

    private static void saveDiscovery() {
        if (dimensionDir == null) {
            return;
        }
        try {
            Files.createDirectories(dimensionDir);
            JsonObject var0 = new JsonObject();
            JsonArray var1 = new JsonArray();
            for (String var2 : BIOMES) {
                var1.add(var2);
            }
            var0.add("biomes", var1);
            JsonArray var3 = new JsonArray();
            for (String var4 : BLOCKS) {
                var3.add(var4);
            }
            var0.add("blocks", var3);
            BufferedWriter var5 = Files.newBufferedWriter(discoveryFile(), StandardCharsets.UTF_8);
            GSON.toJson((com.google.gson.JsonElement)var0, var5);
            var5.close();
        } catch (Exception var6) {
            UnboundMapsMod.LOGGER.warn("Unbound Maps: failed to write discovery.json: {}", var6.toString());
        }
    }

    /** Read-only view for potential UI/debug usage. */
    public static List<String> discoveredBiomes() {
        return new ArrayList<>(BIOMES);
    }

    public static List<String> discoveredBlocks() {
        return new ArrayList<>(BLOCKS);
    }

    private static final class Region {
        final Map<Long, ChunkData> chunks = new LinkedHashMap<>(256, 0.75F, false);
        boolean dirty;
    }

    private record ChunkData(int[] colors, int[] heights) {
    }

    /** Minimal little-endian stream adapter (avoids pulling the full java.io data classes into the hot path). */
    private static final class DataInputStreamLite {
        private final java.io.InputStream in;

        DataInputStreamLite(java.io.InputStream var0) {
            this.in = var0;
        }

        int readInt() throws java.io.IOException {
            int var0 = 0;
            for (int var1 = 3; var1 >= 0; var1--) {
                var0 |= (this.in.read() & 0xFF) << var1 * 8;
            }
            return var0;
        }

        short readShort() throws java.io.IOException {
            int var0 = (this.in.read() & 0xFF) << 8 | this.in.read() & 0xFF;
            return (short)var0;
        }

        long readLong() throws java.io.IOException {
            long var0 = 0L;
            for (int var2 = 7; var2 >= 0; var2--) {
                var0 |= (long)(this.in.read() & 0xFF) << var2 * 8;
            }
            return var0;
        }

        void close() throws java.io.IOException {
            this.in.close();
        }
    }

    private static final class DataOutputStreamLite {
        private final java.io.OutputStream out;

        DataOutputStreamLite(java.io.OutputStream var0) {
            this.out = var0;
        }

        void writeInt(int var0) throws java.io.IOException {
            for (int var1 = 3; var1 >= 0; var1--) {
                this.out.write(var0 >>> var1 * 8 & 0xFF);
            }
        }

        void writeShort(int var0) throws java.io.IOException {
            this.out.write((var0 >> 8 & 0xFF));
            this.out.write(var0 & 0xFF);
        }

        void writeLong(long var0) throws java.io.IOException {
            for (int var2 = 7; var2 >= 0; var2--) {
                this.out.write((int)(var0 >>> var2 * 8 & 0xFFL));
            }
        }

        void flush() throws java.io.IOException {
            this.out.flush();
        }

        void close() throws java.io.IOException {
            this.out.close();
        }
    }

    // Helper holder so MapStore does not depend on the config class loading order.
    private static final class UnboundConfigSafe {
        static boolean persistEnabled() {
            try {
                return net.unbound.maps.UnboundConfig.PERSIST_MAP.get();
            } catch (NullPointerException | IllegalStateException var0) {
                return true;
            }
        }
    }
}
