package net.unbound.maps.waypoints;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.FMLPaths;
import net.unbound.maps.UnboundConfig;
import net.unbound.maps.UnboundMapsMod;
import net.unbound.maps.api.ApiWaypoint;
import net.unbound.maps.mapdata.MinimapTexture;
import net.unbound.maps.util.StoragePaths;

public final class WaypointManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** How many 'old death' waypoints are kept at most (oldest are dropped). */
    private static final int MAX_OLD_DEATH_POINTS = 10;
    /** Standing within 3 blocks of a death point for 5 seconds (100 ticks) clears it. */
    private static final double DEATH_CLEAR_RADIUS_SQ = 9.0;
    private static final int DEATH_CLEAR_TICKS = 100;
    private static final List<Waypoint> CURRENT = new ArrayList<>();
    /** Per-death-waypoint dwell counter (ticks already spent within the clear radius). */
    private static final Map<String, Integer> DEATH_DWELL = new HashMap<>();
    private static ResourceKey<Level> currentDimension;
    private static Path storageDir;
    private static boolean loaded;
    private static boolean wasDead;
    private static long lastDeathTime;

    private WaypointManager() {
    }

    public static List<Waypoint> currentWaypoints() {
        return loaded ? CURRENT : List.of();
    }

    public static boolean contains(String var0) {
        return loaded && CURRENT.stream().anyMatch(var1 -> var1.id.equals(var0));
    }

    public static void clientTick() {
        Minecraft var0 = Minecraft.getInstance();
        ClientLevel var1 = var0.level;
        if (var1 != null && var0.player != null) {
            ResourceKey var2 = var1.dimension();
            if (!loaded || !var2.equals(currentDimension)) {
                if (loaded) {
                    saveNow();
                }

                currentDimension = var2;
                storageDir = resolveStorageDir(var0);
                loadNow();
                MinimapTexture.invalidateAll();
            }

            if (UnboundConfig.DEATH_POINTS.get()) {
                boolean var3 = var0.player.isDeadOrDying();
                if (var3 && !wasDead && var1.getGameTime() - lastDeathTime > 600L) {
                    BlockPos var4 = var0.player.blockPosition();
                    // demote the previous death point to 'old death point' so the
                    // player can always tell where they died LAST from where they
                    // died earlier
                    for (Waypoint var5x : CURRENT) {
                        if (var5x.type == Waypoint.TYPE_DEATH) {
                            var5x.type = Waypoint.TYPE_OLD_DEATH;
                            var5x.name = I18n.get("gui.unboundmaps.deathpointOld");
                            var5x.colorARGB = UnboundConfig.DEATH_OLD_COLOR.get();
                        }
                    }

                    Waypoint var5 = new Waypoint(
                        UUID.randomUUID().toString(),
                        I18n.get("gui.unboundmaps.deathpoint"),
                        var4.getX(),
                        var4.getY(),
                        var4.getZ(),
                        UnboundConfig.DEATH_COLOR.get(),
                        UnboundConfig.DEATH_BEACON.get(),
                        Waypoint.TYPE_DEATH
                    );
                    CURRENT.add(var5);
                    trimOldDeathPoints();
                    lastDeathTime = var1.getGameTime();
                    saveNow();
                }

                wasDead = var3;
            }

            tickDeathDwell(var0);
        } else {
            if (loaded) {
                unload();
            }

            wasDead = false;
        }
    }

    /**
     * Death waypoints vanish once the player has spent 5 seconds within a
     * 3-block radius of them: you came back, picked up your stuff, the marker
     * has done its job. Applies to current and old death points alike.
     */
    private static void tickDeathDwell(Minecraft var0) {
        if (CURRENT.isEmpty()) {
            return;
        }
        net.minecraft.world.phys.Vec3 var1 = var0.player.position();
        boolean var2 = false;
        Iterator<Waypoint> var3 = CURRENT.iterator();

        while (var3.hasNext()) {
            Waypoint var4 = var3.next();
            if (!var4.isDeath()) {
                continue;
            }
            double var5 = var1.x - (var4.x + 0.5);
            double var7 = var1.y - (var4.y + 0.5);
            double var9 = var1.z - (var4.z + 0.5);
            if (var5 * var5 + var7 * var7 + var9 * var9 <= DEATH_CLEAR_RADIUS_SQ) {
                int var11 = DEATH_DWELL.merge(var4.id, 1, Integer::sum);
                if (var11 >= DEATH_CLEAR_TICKS) {
                    var3.remove();
                    DEATH_DWELL.remove(var4.id);
                    var2 = true;
                    var0.player.displayClientMessage(Component.translatable("gui.unboundmaps.deathCleared"), true);
                }
            } else {
                DEATH_DWELL.remove(var4.id);
            }
        }

        if (var2) {
            saveNow();
        }
    }

    /** Keeps at most {@link #MAX_OLD_DEATH_POINTS} old death waypoints (oldest first out). */
    private static void trimOldDeathPoints() {
        int var0 = 0;

        for (Waypoint var2 : CURRENT) {
            if (var2.type == Waypoint.TYPE_OLD_DEATH) {
                var0++;
            }
        }

        int var1 = var0 - MAX_OLD_DEATH_POINTS;
        if (var1 <= 0) {
            return;
        }
        Iterator<Waypoint> var4 = CURRENT.iterator();

        while (var4.hasNext() && var1 > 0) {
            if (var4.next().type == Waypoint.TYPE_OLD_DEATH) {
                var4.remove();
                var1--;
            }
        }
    }

    public static void add(Waypoint var0) {
        CURRENT.add(var0);
        saveNow();
    }

    public static void remove(String var0) {
        CURRENT.removeIf(var1 -> var1.id.equals(var0));
        saveNow();
    }

    public static void update() {
        saveNow();
    }

    public static void saveNow() {
        if (loaded && storageDir != null && currentDimension != null) {
            try {
                Files.createDirectories(storageDir);
                JsonArray var0 = new JsonArray();

                for (Waypoint var2 : CURRENT) {
                    var0.add(var2.toJson());
                }

                try (BufferedWriter var7 = Files.newBufferedWriter(fileFor(currentDimension), StandardCharsets.UTF_8)) {
                    GSON.toJson(var0, var7);
                }
            } catch (Exception var6) {
                UnboundMapsMod.LOGGER.warn("Unbound Maps: failed to save waypoints: {}", var6.toString());
            }
        }
    }

    public static boolean apiAdd(ResourceKey<Level> var0, ApiWaypoint var1) {
        if (!loaded || !var0.equals(currentDimension)) {
            return false;
        } else if (var1.id() != null && !var1.id().isEmpty()) {
            remove(var1.id());
            CURRENT.add(new Waypoint(var1.id(), var1.name(), var1.x(), var1.y(), var1.z(), var1.colorARGB(), var1.beacon()));
            saveNow();
            return true;
        } else {
            return false;
        }
    }

    public static boolean apiRemove(ResourceKey<Level> var0, String var1) {
        if (loaded && var0.equals(currentDimension)) {
            boolean var2 = CURRENT.removeIf(var1x -> var1x.id.equals(var1));
            if (var2) {
                saveNow();
            }

            return var2;
        } else {
            return false;
        }
    }

    public static List<ApiWaypoint> apiList(ResourceKey<Level> var0) {
        if (loaded && var0.equals(currentDimension)) {
            List<ApiWaypoint> var1 = new ArrayList<>(CURRENT.size());

            for (Waypoint var3 : CURRENT) {
                var1.add(new ApiWaypoint(var3.id, var3.name, var3.x, var3.y, var3.z, var3.colorARGB, var3.beacon));
            }

            return var1;
        } else {
            return List.of();
        }
    }

    private static void loadNow() {
        CURRENT.clear();
        DEATH_DWELL.clear();
        loaded = true;
        wasDead = false;
        if (storageDir != null && currentDimension != null) {
            Path var0 = fileFor(currentDimension);
            if (Files.exists(var0)) {
                try (BufferedReader var1 = Files.newBufferedReader(var0, StandardCharsets.UTF_8)) {
                    for (JsonElement var4 : JsonParser.parseReader(var1).getAsJsonArray()) {
                        try {
                            CURRENT.add(Waypoint.fromJson(var4.getAsJsonObject()));
                        } catch (Exception var7) {
                        }
                    }
                } catch (Exception var9) {
                    UnboundMapsMod.LOGGER.warn("Unbound Maps: failed to load waypoints: {}", var9.toString());
                }
            }
        }
    }

    private static void unload() {
        saveNow();
        CURRENT.clear();
        loaded = false;
        currentDimension = null;
    }

    private static Path resolveStorageDir(Minecraft var0) {
        return StoragePaths.waypoints(var0);
    }

    private static Path fileFor(ResourceKey<Level> var0) {
        ResourceLocation var1 = var0.location();
        return storageDir.resolve(var1.getNamespace() + "__" + var1.getPath() + ".json");
    }
}
