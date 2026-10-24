package net.unbound.maps.util;

public final class TeleportUtil {
    private TeleportUtil() {
    }

    public static String format(String var0, int var1, int var2, int var3) {
        if (var0 == null) {
            return null;
        } else {
            String var4 = var0.trim();
            if (var4.isEmpty()) {
                return null;
            } else {
                var4 = var4.replace("{x}", Integer.toString(var1)).replace("{y}", Integer.toString(var2)).replace("{z}", Integer.toString(var3));

                while (var4.startsWith("/")) {
                    var4 = var4.substring(1);
                }

                return var4;
            }
        }
    }
}
