package net.unbound.maps.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.unbound.maps.UnboundConfig;
import net.unbound.maps.UnboundMapsMod;
import net.unbound.maps.mapdata.CaveState;
import net.unbound.maps.mapdata.MapTileCache;
import net.unbound.maps.mapdata.WorldMapCells;
import net.unbound.maps.render.DrawHelper;
import net.unbound.maps.render.EntityRadar;
import net.unbound.maps.render.MinimapHud;
import net.unbound.maps.render.WaypointRenderer;
import net.unbound.maps.util.TeleportUtil;
import net.unbound.maps.waypoints.Waypoint;
import net.unbound.maps.waypoints.WaypointManager;
import org.joml.Matrix4f;

public class WorldMapScreen extends Screen {
    private static final double MIN_BPP = 0.1;
    private static final double MAX_BPP = 4.0;
    private static final int BG_OPAQUE = 0xFF0B0D10;
    private static final int SLIDER_WIDTH = 240;
    private static final int ACTION_CREATE = 0;
    private static final int ACTION_TELEPORT = 1;
    private static final int ACTION_EDIT = 2;
    private static final int ACTION_DELETE = 3;
    private static final double HIT_RADIUS_PX = 12.0;
    private double centerX;
    private double centerZ;
    private static double blocksPerPixel = 1.0;
    private boolean initialized;
    private long lastClickTime;
    private WorldMapScreen.Menu menu;
    private boolean draggingSlider;
    private int sliderX;
    private int sliderY;

    public WorldMapScreen() {
        super(Component.translatable("gui.unboundmaps.worldmap"));
    }

    protected void init() {
        Minecraft var1 = Minecraft.getInstance();
        if (!this.initialized && var1.player != null) {
            this.centerX = var1.player.getX();
            this.centerZ = var1.player.getZ();
            this.initialized = true;
        }
    }

    /** The map is fully opaque, so nothing (HUD layers, world, blur) shows through it. */
    @Override
    public void renderBackground(GuiGraphics var1, int var2, int var3, float var4) {
    }

    public void render(GuiGraphics var1, int var2, int var3, float var4) {
        Minecraft var5 = Minecraft.getInstance();
        int var6 = this.width;
        int var7 = this.height;
        var1.fill(0, 0, var6, var7, BG_OPAQUE);
        ClientLevel var8 = var5.level;
        if (var8 != null) {
            WorldMapCells.ensureVisible(var8, this.centerX, this.centerZ, var6, var7, this.blocksPerPixel);
            double var9 = this.centerX - var6 / 2.0 * this.blocksPerPixel;
            double var11 = this.centerZ - var7 / 2.0 * this.blocksPerPixel;
            int var13 = Math.floorDiv(Mth.floor(var9), 512);
            int var14 = Math.floorDiv(Mth.floor(var9 + var6 * this.blocksPerPixel), 512);
            int var15 = Math.floorDiv(Mth.floor(var11), 512);
            int var16 = Math.floorDiv(Mth.floor(var11 + var7 * this.blocksPerPixel), 512);
            Matrix4f var17 = var1.pose().last().pose();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            float dimFactor = MinimapHud.mapDim(var5);
            RenderSystem.setShaderColor(dimFactor, dimFactor, dimFactor, 1.0F);

            for (int var18 = var15; var18 <= var16; var18++) {
                for (int var19 = var13; var19 <= var14; var19++) {
                    WorldMapCells.Cell var20 = WorldMapCells.cell(var19, var18);
                    if (var20 != null) {
                        float var21 = (float)(var19 * 512L);
                        float var22 = (float)(var18 * 512L);
                        float var23 = (float)((var21 - var9) / this.blocksPerPixel);
                        float var24 = (float)((var22 - var11) / this.blocksPerPixel);
                        float var25 = (float)(512.0 / this.blocksPerPixel);
                        float var26 = Math.max(var23, 0.0F);
                        float var27 = Math.max(var24, 0.0F);
                        float var28 = Math.min(var23 + var25, (float)var6);
                        float var29 = Math.min(var24 + var25, (float)var7);
                        if (!(var28 <= var26) && !(var29 <= var27)) {
                            float var30 = (var26 - var23) / var25;
                            float var31 = (var27 - var24) / var25;
                            float var32 = (var28 - var23) / var25;
                            float var33 = (var29 - var24) / var25;
                            DrawHelper.texturedQuad(var17, var20.location, var26, var27, var28, var29, var30, var31, var32, var33);
                        }
                    }
                }
            }

            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            if (var2 >= 0 && var3 >= 0 && var2 < var6 && var3 < var7) {
                double mouseWX = this.centerX + (var2 - var6 / 2.0) * this.blocksPerPixel;
                double mouseWZ = this.centerZ + (var3 - var7 / 2.0) * this.blocksPerPixel;
                int chunkX = Math.floorDiv(Mth.floor(mouseWX), 16);
                int chunkZ = Math.floorDiv(Mth.floor(mouseWZ), 16);
                float x0 = (float)(var6 / 2.0 + (chunkX * 16 - this.centerX) / this.blocksPerPixel);
                float y0 = (float)(var7 / 2.0 + (chunkZ * 16 - this.centerZ) / this.blocksPerPixel);
                float sizePx = (float)(16.0 / this.blocksPerPixel);
                DrawHelper.outline(var17, x0 - 1.0F, y0 - 1.0F, x0 + sizePx + 1.0F, y0 + sizePx + 1.0F, 1.0F, 1711276032);
                DrawHelper.outline(var17, x0, y0, x0 + sizePx, y0 + sizePx, 1.0F, -1);
            }

            if (var5.player != null) {
                double var35 = var6 / 2.0 + (var5.player.getX() - this.centerX) / this.blocksPerPixel;
                double var36 = var7 / 2.0 + (var5.player.getZ() - this.centerZ) / this.blocksPerPixel;
                DrawHelper.playerArrow(var17, (float)var35, (float)var36, var5.player.getYRot());
            }

            WaypointRenderer.drawWorldMapMarkers(var1, this.centerX, this.centerZ, var6 / 2.0, var7 / 2.0, this.blocksPerPixel);
            EntityRadar.drawWorldMap(var1, this.centerX, this.centerZ, var6, var7, this.blocksPerPixel, var5.player);
            RenderSystem.disableBlend();
        }

        this.renderCursorBar(var1, var2, var3);
        String var34 = I18n.get("gui.unboundmaps.hint")
            + "  ["
            + String.format(Locale.ROOT, "%.2f blocks/px", this.blocksPerPixel)
            + "]";
        var1.drawCenteredString(var5.font, var34, var6 / 2, var7 - 14, -4668212);
        this.renderHeightSlider(var1, var2, var3);
        super.render(var1, var2, var3, var4);
        if (this.menu != null) {
            this.renderMenu(var1, var2, var3);
        }
    }

    /** Xaero-style view height slider: leftmost = AUTO, further right = fixed slice height. */
    private void renderHeightSlider(GuiGraphics var1, int var2, int var3) {
        this.sliderX = this.width / 2 - SLIDER_WIDTH / 2;
        this.sliderY = this.height - 40;
        boolean hover = var2 >= this.sliderX
            && var2 < this.sliderX + SLIDER_WIDTH
            && var3 >= this.sliderY - 8
            && var3 < this.sliderY + 12;
        int handleW = 7;
        double frac = CaveState.sliderFraction();
        int handleX = this.sliderX + (int)((SLIDER_WIDTH - handleW) * frac);
        String label = I18n.get("gui.unboundmaps.heightLabel")
            + ": "
            + (CaveState.isAuto() ? I18n.get("gui.unboundmaps.caveAuto") : "Y=" + CaveState.fixedY());
        var1.drawCenteredString(this.font, label, this.width / 2, this.sliderY - 11, CaveState.isAuto() ? -4668212 : -1643021);
        var1.fill(this.sliderX, this.sliderY + 3, this.sliderX + SLIDER_WIDTH, this.sliderY + 6, -12959930);
        var1.fill(this.sliderX, this.sliderY + 3, this.sliderX + 14, this.sliderY + 6, -1251747841);
        var1.fill(handleX, this.sliderY, handleX + handleW, this.sliderY + 9, hover || this.draggingSlider ? -1 : -6908266);
        var1.renderOutline(handleX - 1, this.sliderY - 1, handleX + handleW + 1, this.sliderY + 10, -267382764);
    }

    private void applySlider(double var1) {
        double frac = Mth.clamp((var1 - this.sliderX) / (SLIDER_WIDTH - 7), 0.0, 1.0);
        CaveState.setFromSlider(frac);
    }

    private boolean overSlider(double var1, double var3) {
        return var1 >= this.sliderX - 4
            && var1 < this.sliderX + SLIDER_WIDTH + 4
            && var3 >= this.sliderY - 12
            && var3 < this.sliderY + 14;
    }

    private void renderCursorBar(GuiGraphics var1, int var2, int var3) {
        Minecraft var4 = Minecraft.getInstance();
        int var5 = var4.player != null ? var4.player.getBlockY() : 64;
        boolean var6 = var2 >= 0 && var3 >= 0 && var4.level != null;
        int var7 = 0;
        int var8 = var5;
        int var9 = 0;
        String blockName = null;
        String biomeName = null;
        if (var6) {
            double var10 = this.centerX + (var2 - this.width / 2.0) * this.blocksPerPixel;
            double var12 = this.centerZ + (var3 - this.height / 2.0) * this.blocksPerPixel;
            var7 = Mth.floor(var10);
            var9 = Mth.floor(var12);
            var8 = MapTileCache.columnY(var4.level, var7, var9, var5, MapTileCache.isCave());
            BlockPos var13 = new BlockPos(var7, Math.max(var8, var4.level.getMinBuildHeight()), var9);
            BlockState var14 = var4.level.getBlockState(var13);
            blockName = var14.getBlock().getName().getString();
            biomeName = var4.level
                .getBiome(var13)
                .unwrapKey()
                .map(var0x -> Component.translatable(var0x.location().toLanguageKey("biome")).getString())
                .orElse(null);
        }

        String var14b = "X: "
            + var7
            + "  Y: "
            + var8
            + "  Z: "
            + var9
            + "  |  "
            + I18n.get("gui.unboundmaps.chunk")
            + " "
            + (var7 >> 4)
            + ", "
            + (var9 >> 4);
        if (biomeName != null && !biomeName.isBlank()) {
            var14b = var14b + "  |  " + biomeName;
        }

        if (blockName != null && !blockName.isBlank()) {
            var14b = var14b + "  |  " + blockName;
        }

        if (MapTileCache.isCave()) {
            var14b = var14b
                + "  §6["
                + I18n.get("gui.unboundmaps.caveLabel")
                + (CaveState.isAuto() ? ": " + I18n.get("gui.unboundmaps.caveAuto") : "")
                + "]§r";
        }

        int var11 = var4.font.width(var14b) + 12;
        var1.fill(this.width / 2 - var11 / 2, 4, this.width / 2 + var11 / 2, 18, -1945104364);
        var1.drawCenteredString(var4.font, var14b, this.width / 2, 8, -1643021);
    }

    private void openMenu(double var1, double var3) {
        Minecraft var5 = Minecraft.getInstance();
        ClientLevel var6 = var5.level;
        int var7 = var5.player != null ? var5.player.getBlockY() : 64;
        double var8 = this.centerX + (var1 - this.width / 2.0) * this.blocksPerPixel;
        double var10 = this.centerZ + (var3 - this.height / 2.0) * this.blocksPerPixel;
        int var12 = Mth.floor(var8);
        int var13 = Mth.floor(var10);
        int var14 = var6 != null ? MapTileCache.columnY(var6, var12, var13, var7, MapTileCache.isCave()) : var7;
        Waypoint var15 = this.findWaypointNear(var1, var3);
        List<String> var16 = new ArrayList<>();
        List<Integer> actions = new ArrayList<>();
        if (var15 != null) {
            var16.add(I18n.get("gui.unboundmaps.menuEdit") + " " + var15.name);
            actions.add(ACTION_EDIT);
            var16.add(I18n.get("gui.unboundmaps.menuDelete") + " " + var15.name);
            actions.add(ACTION_DELETE);
        }

        var16.add(I18n.get("gui.unboundmaps.menuWaypoint"));
        actions.add(ACTION_CREATE);
        var16.add(I18n.get("gui.unboundmaps.menuTeleport"));
        actions.add(ACTION_TELEPORT);
        int var17 = Math.max(
            Math.max(
                var5.font.width("X: " + var12 + "  Y: " + var14 + "  Z: " + var13),
                var5.font.width(I18n.get("gui.unboundmaps.chunk") + ": " + (var12 >> 4) + ", " + (var13 >> 4))
            ),
            var16.stream().mapToInt(var5.font::width).max().orElse(0)
        );
        int var18 = var17 + 12;
        int var19 = 30;
        int var20 = var19 + var16.size() * 14 + 3;
        int var21 = (int)Math.min(var1, (double)(this.width - var18 - 2));
        int var22 = (int)Math.min(var3, (double)(this.height - var20 - 2));
        this.menu = new WorldMapScreen.Menu(var21, var22, var18, var20, var12, var14, var13, var16, actions, var15);
    }

    private Waypoint findWaypointNear(double var1, double var3) {
        Waypoint var5 = null;
        double var6 = 144.0;

        for (Waypoint var9 : WaypointManager.currentWaypoints()) {
            double var10 = this.width / 2.0 + (var9.x + 0.5 - this.centerX) / this.blocksPerPixel - var1;
            double var12 = this.height / 2.0 + (var9.z + 0.5 - this.centerZ) / this.blocksPerPixel - var3;
            double var14 = var10 * var10 + var12 * var12;
            if (var14 <= var6) {
                var6 = var14;
                var5 = var9;
            }
        }

        return var5;
    }

    private void renderMenu(GuiGraphics var1, int var2, int var3) {
        Minecraft var4 = Minecraft.getInstance();
        WorldMapScreen.Menu var5 = this.menu;
        var1.fill(var5.x, var5.y, var5.x + var5.width, var5.y + var5.height, -267119590);
        var1.renderOutline(var5.x, var5.y, var5.x + var5.width, var5.y + var5.height, -12959930);
        int var6 = var5.y + 3;
        var1.drawString(var4.font, "X: " + var5.blockX + "  Y: " + var5.blockY + "  Z: " + var5.blockZ, var5.x + 6, var6, -1643021, false);
        var6 += 10;
        var1.drawString(
            var4.font,
            I18n.get("gui.unboundmaps.chunk") + ": " + (var5.blockX >> 4) + ", " + (var5.blockZ >> 4),
            var5.x + 6,
            var6,
            -4668212,
            false
        );
        var6 += 13;
        var1.fill(var5.x + 3, var6, var5.x + var5.width - 3, var6 + 1, -12959930);
        var6 += 4;

        for (int var7 = 0; var7 < var5.entries.size(); var7++) {
            boolean var8 = var3 >= var6 && var3 < var6 + 14 && var2 >= var5.x && var2 < var5.x + var5.width;
            if (var8) {
                var1.fill(var5.x + 1, var6, var5.x + var5.width - 1, var6 + 14, -13749184);
            }

            boolean var9 = var5.target != null && var7 == 1;
            var1.drawString(var4.font, var5.entries.get(var7), var5.x + 6, var6 + 3, var8 ? (var9 ? -30070 : -1) : (var9 ? -1734006 : -3550502), false);
            var6 += 14;
        }
    }

    private void handleMenuClick(double var1) {
        WorldMapScreen.Menu var3 = this.menu;
        this.menu = null;
        int var4 = var3.y + 30;
        int var5 = (int)((var1 - var4) / 14.0);
        if (var5 >= 0 && var5 < var3.actions.size()) {
            int var6 = var3.actions.get(var5);
            switch (var6) {
                case ACTION_TELEPORT:
                    this.teleportTo(var3.blockX, var3.blockY, var3.blockZ);
                    break;
                case ACTION_EDIT:
                    if (var3.target != null) {
                        this.minecraft.setScreen(new WaypointEditScreen(this, var3.target));
                    }
                    break;
                case ACTION_DELETE:
                    if (var3.target != null) {
                        WaypointManager.remove(var3.target.id);
                    }
                    break;
                default:
                    this.openWaypointEditor(var3.blockX, var3.blockY, var3.blockZ);
            }
        }
    }

    private void openWaypointEditor(int var1, int var2, int var3) {
        Minecraft var4 = Minecraft.getInstance();
        Waypoint var5 = new Waypoint(UUID.randomUUID().toString(), I18n.get("gui.unboundmaps.defaultName"), var1, var2, var3, -1, true);
        var4.setScreen(new WaypointEditScreen(this, var5));
    }

    private void teleportTo(int var1, int var2, int var3) {
        Minecraft var4 = Minecraft.getInstance();
        if (var4.player != null && var4.player.connection != null) {
            String var5 = TeleportUtil.format(UnboundConfig.TELEPORT_COMMAND.get(), var1, var2, var3);
            if (var5 != null) {
                var4.player.connection.sendCommand(var5);
                var4.gui.getChat().addMessage(Component.translatable("gui.unboundmaps.teleportSent", var5));
                UnboundMapsMod.LOGGER.info("Teleport command sent: /{}", var5);
            }
        }
    }

    public boolean mouseScrolled(double var1, double var3, double var5, double var7) {
        if (this.overSlider(var1, var3)) {
            CaveState.adjustHeight(var7 > 0.0 ? 8 : -8);
            return true;
        } else {
            double var9 = var7 > 0.0 ? 0.8 : 1.25;
            double var11 = Mth.clamp(this.blocksPerPixel * var9, MIN_BPP, MAX_BPP);
            if (var11 != this.blocksPerPixel) {
                double var13 = this.centerX + (var1 - this.width / 2.0) * this.blocksPerPixel;
                double var15 = this.centerZ + (var3 - this.height / 2.0) * this.blocksPerPixel;
                this.blocksPerPixel = var11;
                this.centerX = var13 - (var1 - this.width / 2.0) * this.blocksPerPixel;
                this.centerZ = var15 - (var3 - this.height / 2.0) * this.blocksPerPixel;
            }

            return true;
        }
    }

    public boolean mouseDragged(double var1, double var3, int var5, double var6, double var8) {
        if (this.draggingSlider) {
            this.applySlider(var1);
            return true;
        } else if (var5 == 0 && this.menu == null) {
            this.centerX = this.centerX - var6 * this.blocksPerPixel;
            this.centerZ = this.centerZ - var8 * this.blocksPerPixel;
            return true;
        } else {
            return super.mouseDragged(var1, var3, var5, var6, var8);
        }
    }

    public boolean mouseClicked(double var1, double var3, int var5) {
        if (var5 == 0 && this.menu == null && this.overSlider(var1, var3)) {
            this.draggingSlider = true;
            this.applySlider(var1);
            return true;
        } else if (this.menu == null) {
            if (var5 == 1) {
                this.openMenu(var1, var3);
                return true;
            } else {
                long var16 = Util.getMillis();
                if (var5 == 0 && var16 - this.lastClickTime < 350L) {
                    double var8 = this.centerX + (var1 - this.width / 2.0) * this.blocksPerPixel;
                    double var10 = this.centerZ + (var3 - this.height / 2.0) * this.blocksPerPixel;
                    int var12 = Mth.floor(var8);
                    int var13 = Mth.floor(var10);
                    Minecraft var14 = Minecraft.getInstance();
                    int var15 = var14.player != null ? var14.player.getBlockY() : 64;
                    if (var14.level != null) {
                        var15 = MapTileCache.columnY(var14.level, var12, var13, var15, MapTileCache.isCave());
                    }

                    this.openWaypointEditor(var12, var15, var13);
                    return true;
                } else {
                    this.lastClickTime = var16;
                    return super.mouseClicked(var1, var3, var5);
                }
            }
        } else {
            WorldMapScreen.Menu var6 = this.menu;
            boolean var7 = var1 >= var6.x && var1 < var6.x + var6.width && var3 >= var6.y && var3 < var6.y + var6.height;
            if (var7 && var5 == 0) {
                this.handleMenuClick(var3);
            } else if (var5 == 1) {
                this.openMenu(var1, var3);
            } else {
                this.menu = null;
            }

            return true;
        }
    }

    @Override
    public boolean mouseReleased(double var1, double var3, int var5) {
        if (var5 == 0 && this.draggingSlider) {
            this.draggingSlider = false;
            return true;
        }
        return super.mouseReleased(var1, var3, var5);
    }

    public boolean keyPressed(int var1, int var2, int var3) {
        if (var1 == 77) {
            this.onClose();
            return true;
        } else if (var1 == 66) {
            this.minecraft.setScreen(new WaypointListScreen(this));
            return true;
        } else if (var1 == 67) {
            CaveState.toggleFixed();
            return true;
        } else if (var1 == 263) {
            CaveState.adjustHeight(-8);
            return true;
        } else if (var1 == 262) {
            CaveState.adjustHeight(8);
            return true;
        } else {
            return super.keyPressed(var1, var2, var3);
        }
    }

    public void onClose() {
        WaypointManager.saveNow();
        super.onClose();
    }

    public boolean isPauseScreen() {
        return false;
    }

    private static final class Menu {
        final int x;
        final int y;
        final int width;
        final int height;
        final int blockX;
        final int blockY;
        final int blockZ;
        final List<String> entries;
        final List<Integer> actions;
        final Waypoint target;

        Menu(int var1, int var2, int var3, int var4, int var5, int var6, int var7, List<String> var8, List<Integer> var9, Waypoint var10) {
            this.x = var1;
            this.y = var2;
            this.width = var3;
            this.height = var4;
            this.blockX = var5;
            this.blockY = var6;
            this.blockZ = var7;
            this.entries = var8;
            this.actions = var9;
            this.target = var10;
        }
    }
}
