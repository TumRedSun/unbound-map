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
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
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
    private static final List<Waypoint> CURRENT = new ArrayList<>();
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
                    Waypoint var5 = new Waypoint(
                        UUID.randomUUID().toString(),
                        I18n.get("gui.unboundmaps.deathpoint"),
                        var4.getX(),
                        var4.getY(),
                        var4.getZ(),
                        -1749940,
                        false
                    );
                    CURRENT.add(var5);
                    lastDeathTime = var1.getGameTime();
                    saveNow();
                }

                wasDead = var3;
            }
        } else {
            if (loaded) {
                unload();
            }

            wasDead = false;
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
