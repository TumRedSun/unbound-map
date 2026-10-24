package net.unbound.maps.util;

public final class ScreenProjector {
    private ScreenProjector() {
    }

    public static ScreenProjector.Result project(
        double var0, double var2, double var4, double var6, double var8, double var10, float var12, float var13, double var14, int var16, int var17
    ) {
        double var18 = var0 - var6;
        double var20 = var2 - var8;
        double var22 = var4 - var10;
        double var24 = Math.sqrt(var18 * var18 + var20 * var20 + var22 * var22);
        double var26 = Math.toRadians(var12);
        double var28 = Math.toRadians(var13);
        double var30 = Math.sin(var26);
        double var32 = Math.cos(var26);
        double var34 = Math.sin(var28);
        double var36 = Math.cos(var28);
        double var38 = -var30 * var36;
        double var40 = -var34;
        double var42 = var32 * var36;
        double var44 = -var32;
        double var46 = 0.0;
        double var48 = -var30;
        double var50 = -var30 * var34;
        double var54 = var32 * var34;
        double var56 = var18 * var38 + var20 * var40 + var22 * var42;
        double var58 = var18 * var44 + var20 * var46 + var22 * var48;
        double var60 = var18 * var50 + var20 * var36 + var22 * var54;
        double var62 = var16 / 2.0;
        double var64 = var17 / 2.0;
        if (!(var56 > 0.05)) {
            double var79 = Math.max(var62, var64) * 8.0 / Math.max(1.0, Math.hypot(var58, var60));
            float var80 = (float)MthdClamp(var62 - var58 * var79, 0.0, var16);
            float var69 = (float)MthdClamp(var64 + var60 * var79, 0.0, var17);
            return new ScreenProjector.Result(var80, var69, false, var24);
        } else {
            double var66 = Math.tan(Math.toRadians(var14) / 2.0);
            double var68 = (double)var16 / var17;
            double var70 = var62 + var58 / (var56 * var66 * var68) * var62;
            double var72 = var64 - var60 / (var56 * var66) * var64;
            boolean var74 = var70 >= 0.0 && var70 < var16 && var72 >= 0.0 && var72 < var17;
            if (var74) {
                return new ScreenProjector.Result((float)var70, (float)var72, true, var24);
            } else {
                double var75 = var70 - var62;
                double var77 = var72 - var64;
                return new ScreenProjector.Result(
                    (float)(var62 + clampDirection(var75, var77, var62, var64)[0]),
                    (float)(var64 + clampDirection(var75, var77, var62, var64)[1]),
                    false,
                    var24
                );
            }
        }
    }

    private static double[] clampDirection(double var0, double var2, double var4, double var6) {
        double var8 = Math.abs(var0) < 1.0E-6 ? 1.0E-6 : var0;
        double var10 = Math.abs(var2) < 1.0E-6 ? 1.0E-6 : var2;
        double var12 = (var4 - 1.0) / Math.abs(var8);
        double var14 = (var6 - 1.0) / Math.abs(var10);
        double var16 = Math.min(var12, var14);
        return new double[]{var8 * var16, var10 * var16};
    }

    private static double MthdClamp(double var0, double var2, double var4) {
        return var0 < var2 ? var2 : Math.min(var0, var4);
    }

    public static final class Result {
        public final float x;
        public final float y;
        public final boolean onScreen;
        public final double distance;

        Result(float var1, float var2, boolean var3, double var4) {
            this.x = var1;
            this.y = var2;
            this.onScreen = var3;
            this.distance = var4;
        }
    }
}
