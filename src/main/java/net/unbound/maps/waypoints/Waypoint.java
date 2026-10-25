package net.unbound.maps.waypoints;

import com.google.gson.JsonObject;

public final class Waypoint {
    /** A regular player-created waypoint. */
    public static final int TYPE_NORMAL = 0;
    /** The newest death point (this is where the player died last time). */
    public static final int TYPE_DEATH = 1;
    /** A previous death point (kept for history, can be hidden in the config). */
    public static final int TYPE_OLD_DEATH = 2;

    public final String id;
    public String name;
    public int x;
    public int y;
    public int z;
    public int colorARGB;
    public boolean beacon;
    public int type;

    public Waypoint(String var1, String var2, int var3, int var4, int var5, int var6, boolean var7) {
        this(var1, var2, var3, var4, var5, var6, var7, TYPE_NORMAL);
    }

    public Waypoint(String var1, String var2, int var3, int var4, int var5, int var6, boolean var7, int var8) {
        this.id = var1;
        this.name = var2;
        this.x = var3;
        this.y = var4;
        this.z = var5;
        this.colorARGB = var6;
        this.beacon = var7;
        this.type = var8;
    }

    /** true for both death marker kinds (current and old). */
    public boolean isDeath() {
        return this.type == TYPE_DEATH || this.type == TYPE_OLD_DEATH;
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
        if (this.type != TYPE_NORMAL) {
            var1.addProperty("type", this.type);
        }

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
            var0.has("beacon") && var0.get("beacon").getAsBoolean(),
            var0.has("type") ? var0.get("type").getAsInt() : TYPE_NORMAL
        );
    }
}
