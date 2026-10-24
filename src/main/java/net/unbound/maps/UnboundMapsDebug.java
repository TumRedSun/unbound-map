package net.unbound.maps;

public final class UnboundMapsDebug {
    private static final long INTERVAL_MS = 10000L;
    private static long lastReport;

    private UnboundMapsDebug() {
    }

    public static void note(Exception var0) {
        long var1 = System.currentTimeMillis();
        if (var1 - lastReport >= INTERVAL_MS) {
            lastReport = var1;
            UnboundMapsMod.LOGGER.warn("Unbound Maps: repeated exception suppressed for 10s", var0);
        }
    }
}
