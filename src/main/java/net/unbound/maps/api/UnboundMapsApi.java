package net.unbound.maps.api;

import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.unbound.maps.waypoints.WaypointManager;

public final class UnboundMapsApi {
    private UnboundMapsApi() {
    }

    public static String modId() {
        return "unboundmaps";
    }

    public static String apiVersion() {
        return "1.0.0";
    }

    public static boolean addWaypoint(ResourceKey<Level> var0, ApiWaypoint var1) {
        return WaypointManager.apiAdd(var0, var1);
    }

    public static boolean removeWaypoint(ResourceKey<Level> var0, String var1) {
        return WaypointManager.apiRemove(var0, var1);
    }

    public static List<ApiWaypoint> getWaypoints(ResourceKey<Level> var0) {
        return WaypointManager.apiList(var0);
    }
}
