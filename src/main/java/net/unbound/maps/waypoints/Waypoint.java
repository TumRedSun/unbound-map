package net.unbound.maps.waypoints;

import com.google.gson.JsonObject;

public final class Waypoint {
    public final String id;
    public String name;
    public int x;
    public int y;
    public int z;
    public int colorARGB;
    public boolean beacon;

    public Waypoint(String var1, String var2, int var3, int var4, int var5, int var6, boolean var7) {
        this.id = var1;
        this.name = var2;
        this.x = var3;
        this.y = var4;
        this.z = var5;
        this.colorARGB = var6;
        this.beacon = var7;
    }

    public JsonObject toJson() {
        JsonObject var1 = new JsonObject();
        var1.addProperty("id", this.id);
        var1.addProperty("name", this.name);
        var1.addProperty("x", this.x);
        var1.addProperty("y", this.y);
        var1.addProperty("z", this.z);
        var1.addProperty("color", this.colorARGB);
        var1.addProperty("beacon", this.beacon);
        return var1;
    }

    public static Waypoint fromJson(JsonObject var0) {
        return new Waypoint(
            var0.get("id").getAsString(),
            var0.get("name").getAsString(),
            var0.get("x").getAsInt(),
            var0.get("y").getAsInt(),
            var0.get("z").getAsInt(),
            var0.has("color") ? var0.get("color").getAsInt() : -1,
            var0.has("beacon") && var0.get("beacon").getAsBoolean()
        );
    }
}
