package net.unbound.maps.util;

import java.util.Locale;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.loading.FMLPaths;

/** Resolves per-server / per-world storage directories, shared by waypoints and the map cache. */
public final class StoragePaths {
    private StoragePaths() {
    }

    /** true when the client is in a singleplayer world. */
    public static boolean isSingleplayer(Minecraft var0) {
        return var0.getSingleplayerServer() != null;
    }

    /**
     * Base storage directory for the current world: {@code <world>/unboundmaps} in
     * singleplayer or {@code <gamedir>/unboundmaps/servers/<sanitized ip>} on a server.
     */
    public static Path base(Minecraft var0) {
        if (var0.getSingleplayerServer() != null) {
            return var0.getSingleplayerServer().getWorldPath(LevelResource.ROOT).resolve("unboundmaps");
        }
        ServerData var1 = var0.getCurrentServer();
        String var2 = var1 == null ? "unknown" : sanitize(var1.ip);
        return FMLPaths.GAMEDIR.get().resolve("unboundmaps/servers").resolve(var2);
    }

    public static Path waypoints(Minecraft var0) {
        return base(var0).resolve("waypoints");
    }

    public static Path map(Minecraft var0) {
        return base(var0).resolve("map");
    }

    public static String sanitize(String var0) {
        StringBuilder var1 = new StringBuilder();

        for (char var5 : var0.toLowerCase(Locale.ROOT).toCharArray()) {
            boolean var6 = var5 >= 'a' && var5 <= 'z' || var5 >= '0' && var5 <= '9' || var5 == '.' || var5 == '-' || var5 == '_';
            var1.append(var6 ? var5 : '_');
        }

        return var1.length() > 64 ? var1.substring(0, 64) : var1.toString();
    }
}
