package net.unbound.maps.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.unbound.maps.UnboundConfig;
import net.unbound.maps.UnboundMapsMod;
import net.unbound.maps.mapdata.CaveState;
import net.unbound.maps.mapdata.MapStore;
import net.unbound.maps.mapdata.MapTileCache;
import net.unbound.maps.mapdata.WorldMapCells;
import net.unbound.maps.render.DrawHelper;
import net.unbound.maps.render.LabelPlacer;
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
    private EditBox searchBox;
    private boolean searchOpen;
    private List<WorldMapScreen.SearchHit> searchResults = List.of();
    private int searchIndex;
    private String searchStatus;
    private String lastBlockQuery;
    private String lastBiomeQuery;

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
        LabelPlacer.begin();
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
            this.renderSearchResults(var1);
            EntityRadar.drawWorldMap(var1, this.centerX, this.centerZ, var6, var7, this.blocksPerPixel, var5.player);
            RenderSystem.disableBlend();
        }

        this.renderCursorBar(var1, var2, var3);
        String hint = I18n.get("gui.unboundmaps.hint");
        String zoomSuffix = "  [" + String.format(Locale.ROOT, "%.2f blocks/px", this.blocksPerPixel) + "]";
        List<String> lines = wrapSegments(hint, " | ", var6 - 20);
        int firstY = var7 - 12 - (lines.size() - 1) * 11 - 1;
        for (int li = 0; li < lines.size(); li++) {
            String line = li == lines.size() - 1 ? lines.get(li) + zoomSuffix : lines.get(li);
            if (li == lines.size() - 1) {
                while (var5.font.width(line) > var6 - 20 && zoomSuffix.length() > 0) {
                    zoomSuffix = zoomSuffix.substring(1);
                    line = lines.get(li) + zoomSuffix;
                }
            }
            var1.drawCenteredString(var5.font, line, var6 / 2, firstY + li * 11, -4668212);
        }

        this.renderHeightSlider(var1, var2, var3);
        super.render(var1, var2, var3, var4);
        if (this.menu != null) {
            this.renderMenu(var1, var2, var3);
        }

        if (this.searchOpen && this.searchStatus != null) {
            var1.drawString(var5.font, this.searchStatus, 10, 30, -1643021, true);
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

    /** Splits a hint string into centered lines no wider than maxWidth, keeping the separators. */
    private static List<String> wrapSegments(String text, String sep, int maxWidth) {
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        String[] parts = text.split(java.util.regex.Pattern.quote(sep));
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            String candidate = current.length() == 0 ? part : current + sep + part;
            if (current.length() > 0 && font.width(candidate) > maxWidth) {
                lines.add(current.toString());
                current = new StringBuilder(part);
            } else {
                current = new StringBuilder(candidate);
            }
        }

        if (current.length() > 0) {
            lines.add(current.toString());
        }

        return lines.isEmpty() ? List.of(text) : lines;
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
            // block/biome names only exist for LOADED chunks; stored chunks would
            // report "void air" / wrong biome, so they are simply omitted
            if (var4.level.getChunk(Math.floorDiv(var7, 16), Math.floorDiv(var9, 16), ChunkStatus.FULL, false) != null) {
                BlockPos var13 = new BlockPos(var7, Math.max(var8, var4.level.getMinBuildHeight()), var9);
                BlockState var14 = var4.level.getBlockState(var13);
                blockName = var14.getBlock().getName().getString();
                biomeName = var4.level
                    .getBiome(var13)
                    .unwrapKey()
                    .map(var0x -> Component.translatable(var0x.location().toLanguageKey("biome")).getString())
                    .orElse(null);
            }
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
        if (this.searchOpen) {
            if (var5 == 0 && this.searchBox != null && var1 >= 6.0 && var1 < 272.0 && var3 >= 6.0 && var3 < 28.0) {
                return super.mouseClicked(var1, var3, var5);
            }

            if (var5 == 1) {
                this.closeSearch();
                return true;
            }
        }

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
        if (this.searchOpen) {
            if (var1 == 256) {
                this.closeSearch();
                return true;
            }

            if (var1 == 257 || var1 == 335) {
                this.doSearch(true);
                return true;
            }

            return this.searchBox.keyPressed(var1, var2, var3) || super.keyPressed(var1, var2, var3) || true;
        } else if (var1 == 70 && Screen.hasControlDown()) {
            this.openSearch();
            return true;
        } else if (var1 == 77) {
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

    /** Ctrl+F: opens the waypoint/block search bar in the top-left corner. */
    private void openSearch() {
        if (!this.searchOpen) {
            if (this.searchBox == null) {
                this.searchBox = new EditBox(this.font, 8, 8, 260, 18, Component.translatable("gui.unboundmaps.search"));
                this.searchBox.setMaxLength(128);
                this.searchBox.setHint(Component.translatable("gui.unboundmaps.searchHint"));
                this.searchBox.setResponder(var0 -> this.liveSearch());
            }

            this.addRenderableWidget(this.searchBox);
            this.searchBox.setValue("");
            this.searchResults = List.of();
            this.searchStatus = null;
            this.searchOpen = true;
            this.setInitialFocus(this.searchBox);
        }
    }

    private void closeSearch() {
        if (this.searchOpen) {
            this.removeWidget(this.searchBox);
            this.searchOpen = false;
            this.searchResults = List.of();
            this.searchStatus = null;
        }
    }

    /** Live update while typing: instant for waypoints, blocks and biomes wait for Enter (their scan is heavier). */
    private void liveSearch() {
        if (!this.searchOpen) {
            return;
        }
        String var1 = this.searchBox.getValue().trim();
        if (var1.isEmpty()) {
            this.searchResults = List.of();
            this.searchStatus = null;
            return;
        }

        String var2 = var1.toLowerCase(Locale.ROOT);
        if (var2.startsWith("block/") || var2.startsWith("\u0431\u043b\u043e\u043a/") || var2.startsWith("biome/") || var2.startsWith("\u0431\u0438\u043e\u043c/")) {
            this.searchStatus = I18n.get("gui.unboundmaps.searchEnter");
        } else {
            String var3;
            if (var2.startsWith("way/")) {
                var3 = var1.substring(4).trim();
            } else if (var2.startsWith("wp/")) {
                var3 = var1.substring(3).trim();
            } else if (var2.startsWith("waypoint/")) {
                var3 = var1.substring(9).trim();
            } else if (var2.startsWith("\u043c\u0435\u0442\u043a\u0430/")) {
                var3 = var1.substring(6).trim();
            } else {
                var3 = var1;
            }

            this.searchWaypoints(var3, false);
        }
    }

    private void doSearch(boolean var1) {
        if (this.searchOpen) {
            String var2 = this.searchBox.getValue().trim();
            if (!var2.isEmpty()) {
                String var3 = var2.toLowerCase(Locale.ROOT);
                String var4;
                if (var3.startsWith("block/")) {
                    var4 = var2.substring(6).trim();
                } else if (var3.startsWith("\u0431\u043b\u043e\u043a/")) {
                    var4 = var2.substring(5).trim();
                } else if (var3.startsWith("biome/")) {
                    var4 = var2.substring(6).trim();
                } else if (var3.startsWith("\u0431\u0438\u043e\u043c/")) {
                    var4 = var2.substring(5).trim();
                } else if (var3.startsWith("way/")) {
                    var4 = var2.substring(4).trim();
                } else if (var3.startsWith("wp/")) {
                    var4 = var2.substring(3).trim();
                } else if (var3.startsWith("waypoint/")) {
                    var4 = var2.substring(9).trim();
                } else if (var3.startsWith("\u043c\u0435\u0442\u043a\u0430/")) {
                    var4 = var2.substring(6).trim();
                } else {
                    var4 = var2;
                }

                if (var3.startsWith("block/") || var3.startsWith("\u0431\u043b\u043e\u043a/")) {
                    this.searchBlocks(var4, var1);
                } else if (var3.startsWith("biome/") || var3.startsWith("\u0431\u0438\u043e\u043c/")) {
                    this.searchBiomes(var4, var1);
                } else {
                    this.searchWaypoints(var4, var1);
                }
            }
        }
    }

    private void searchWaypoints(String var1, boolean var2) {
        List<WorldMapScreen.SearchHit> var3 = new ArrayList<>();
        String var4 = var1.toLowerCase(Locale.ROOT);

        for (Waypoint var6 : WaypointManager.currentWaypoints()) {
            if (var4.isEmpty() || var6.name.toLowerCase(Locale.ROOT).contains(var4)) {
                var3.add(new WorldMapScreen.SearchHit(var6.x, var6.y, var6.z, var6.name, var6.colorARGB | 0xFF000000));
            }
        }

        var3.sort(Comparator.comparingDouble(this::hitDist2));
        this.searchResults = var3;
        if (var3.isEmpty()) {
            this.searchIndex = 0;
            this.searchStatus = var1.isEmpty() ? null : I18n.get("gui.unboundmaps.searchNoResults");
        } else {
            if (var2) {
                this.searchIndex = (this.searchIndex + 1) % var3.size();
            } else {
                this.searchIndex = 0;
            }

            WorldMapScreen.SearchHit var7 = var3.get(this.searchIndex);
            this.searchStatus = I18n.get("gui.unboundmaps.searchFound", var3.size())
                + " \u2014 "
                + var7.label()
                + " ("
                + (this.searchIndex + 1)
                + "/"
                + var3.size()
                + ")";
            this.centerOn(var7.x(), var7.z());
        }
    }

    private void searchBlocks(String var1, boolean var2) {
        if (var1.isEmpty()) {
            return;
        }
        if (var2 && var1.equals(this.lastBlockQuery) && !this.searchResults.isEmpty()) {
            this.searchIndex = (this.searchIndex + 1) % this.searchResults.size();
            WorldMapScreen.SearchHit var3 = this.searchResults.get(this.searchIndex);
            this.searchStatus = I18n.get("gui.unboundmaps.searchFound", this.searchResults.size())
                + " \u2014 "
                + var3.label()
                + " ("
                + (this.searchIndex + 1)
                + "/"
                + this.searchResults.size()
                + ")";
            this.centerOn(var3.x(), var3.z());
            return;
        }

        ClientLevel var9 = this.minecraft.level;
        if (var9 == null) {
            return;
        }
        this.lastBlockQuery = var1;
        String var4 = var1.toLowerCase(Locale.ROOT);
        List<WorldMapScreen.SearchHit> var5 = new ArrayList<>();
        Minecraft var13 = this.minecraft;
        int var6 = 8;
        try {
            var6 = var13.options.renderDistance().get();
        } catch (Exception var14a) {
        }

        int var7 = var13.player != null ? Mth.floor(var13.player.getX()) >> 4 : Mth.floor(this.centerX) >> 4;
        int var8 = var13.player != null ? Mth.floor(var13.player.getZ()) >> 4 : Mth.floor(this.centerZ) >> 4;
        int var10 = var9.getMinBuildHeight();
        outer:

        for (int ring = 0; ring <= var6; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
                        ChunkAccess var11 = var9.getChunk(var7 + dx, var8 + dz, ChunkStatus.FULL, false);
                        if (var11 instanceof LevelChunk chunk) {
                            LevelChunkSection[] var12 = chunk.getSections();
                            int var14 = chunk.getPos().x << 4;
                            int var15 = chunk.getPos().z << 4;

                            for (int var16 = 0; var16 < var12.length; var16++) {
                                LevelChunkSection var17 = var12[var16];
                                if (var17 != null && !var17.hasOnlyAir() && var17.maybeHas(var0 -> this.blockMatches(var0.getBlock(), var4))) {
                                    int var18 = var10 + (var16 << 4);

                                    for (int var19 = 15; var19 >= 0; var19--) {
                                        for (int var20 = 0; var20 < 16; var20++) {
                                            for (int var21 = 0; var21 < 16; var21++) {
                                                BlockState var22 = var17.getBlockState(var21, var19, var20);
                                                if (this.blockMatches(var22.getBlock(), var4)) {
                                                    var5.add(new WorldMapScreen.SearchHit(
                                                        var14 + var21,
                                                        var18 + var19,
                                                        var15 + var20,
                                                        var22.getBlock().getName().getString(),
                                                        -11534136
                                                    ));
                                                    if (var5.size() >= 512) {
                                                        break outer;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        var5.sort(Comparator.comparingDouble(this::hitDist2));
        this.searchResults = var5;
        if (var5.isEmpty()) {
            this.searchIndex = 0;
            this.searchStatus = I18n.get("gui.unboundmaps.searchNoResults");
        } else {
            this.searchIndex = 0;
            WorldMapScreen.SearchHit var20 = var5.get(0);
            this.searchStatus = I18n.get("gui.unboundmaps.searchFound", var5.size())
                + (var5.size() >= 512 ? "+" : "")
                + " \u2014 "
                + var20.label()
                + " ["
                + var20.x()
                + ", "
                + var20.y()
                + ", "
                + var20.z()
                + "]";
            this.centerOn(var20.x(), var20.z());
        }
    }

    /**
     * Biome search over the WHOLE map: live chunks are scanned through their
     * biome containers, every other explored chunk comes from the persistent
     * store (per-column surface biome palette). The query matches the full or
     * partial localized name (current language), the registry path (english
     * words, spaces instead of underscores) or a tag with the # prefix.
     */
    private void searchBiomes(String var1, boolean var2) {
        if (var1.isEmpty()) {
            return;
        }
        if (var2 && var1.equals(this.lastBiomeQuery) && !this.searchResults.isEmpty()) {
            this.searchIndex = (this.searchIndex + 1) % this.searchResults.size();
            WorldMapScreen.SearchHit var3 = this.searchResults.get(this.searchIndex);
            this.searchStatus = I18n.get("gui.unboundmaps.searchFound", this.searchResults.size())
                + " \u2014 "
                + var3.label()
                + " ("
                + (this.searchIndex + 1)
                + "/"
                + this.searchResults.size()
                + ")";
            this.centerOn(var3.x(), var3.z());
            return;
        }

        ClientLevel var9 = this.minecraft.level;
        if (var9 == null) {
            return;
        }
        this.lastBiomeQuery = var1;
        boolean tagQuery = var1.startsWith("#");
        ResourceLocation tagRlTmp = null;
        if (tagQuery) {
            String t = var1.substring(1).trim().replace(' ', '_');
            tagRlTmp = ResourceLocation.tryParse(t.contains(":") ? t : "minecraft:" + t);
            if (tagRlTmp == null) {
                this.searchResults = List.of();
                this.searchIndex = 0;
                this.searchStatus = I18n.get("gui.unboundmaps.searchNoResults");
                return;
            }
        }
        final ResourceLocation tagRl = tagRlTmp;

        String norm = var1.toLowerCase(Locale.ROOT).replace('_', ' ');
        List<WorldMapScreen.SearchHit> var5 = new ArrayList<>();
        Minecraft var13 = this.minecraft;
        int var6 = 8;
        try {
            var6 = var13.options.renderDistance().get();
        } catch (Exception var14a) {
        }

        int var7 = var13.player != null ? Mth.floor(var13.player.getX()) >> 4 : Mth.floor(this.centerX) >> 4;
        int var8 = var13.player != null ? Mth.floor(var13.player.getZ()) >> 4 : Mth.floor(this.centerZ) >> 4;

        // 1) loaded chunks: walk the 4x4x4 biome cells of every section
        outer:

        for (int ring = 0; ring <= var6; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
                        ChunkAccess var11 = var9.getChunk(var7 + dx, var8 + dz, ChunkStatus.FULL, false);
                        if (var11 instanceof LevelChunk chunk) {
                            LevelChunkSection[] var12 = chunk.getSections();
                            int var14 = chunk.getPos().x << 4;
                            int var15 = chunk.getPos().z << 4;

                            for (int var16 = 0; var16 < var12.length && var5.size() < 512; var16++) {
                                LevelChunkSection var17 = var12[var16];
                                if (var17 != null && !var17.hasOnlyAir()) {
                                    int var18 = var9.getMinBuildHeight() + (var16 << 4);

                                    for (int var19 = 0; var19 < 4 && var5.size() < 512; var19++) {
                                        for (int var20 = 0; var20 < 4 && var5.size() < 512; var20++) {
                                            for (int var21 = 0; var21 < 4 && var5.size() < 512; var21++) {
                                                Holder<Biome> var22 = var17.getBiomes().get(var21, var20, var19);
                                                if (this.biomeHolderMatches(var22, norm, tagQuery, tagRl)) {
                                                    int var23 = var14 + (var21 << 2) + 2;
                                                    int var24 = var18 + (var20 << 2) + 2;
                                                    int var25 = var15 + (var19 << 2) + 2;
                                                    var5.add(new WorldMapScreen.SearchHit(var23, var24, var25, this.biomeLabel(var22), -13996851));
                                                    var19 = 4;
                                                    var20 = 4;
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (var5.size() >= 512) {
                                break outer;
                            }
                        }
                    }
                }
            }
        }

        // 2) stored chunks: the whole explored map from the persistent store
        if (var5.size() < 512 && MapStore.isEnabled()) {
            ClientLevel level = var9;
            MapStore.forEachStoredChunk((pos, palette, cols) -> {
                if (var5.size() >= 512) {
                    return;
                }
                int cx = ChunkPos.getX(pos);
                int cz = ChunkPos.getZ(pos);
                if (level.getChunk(cx, cz, ChunkStatus.FULL, false) != null) {
                    return; // already covered by the live scan
                }
                for (int i = 0; i < 256; i++) {
                    int idx = cols[i] & 0xFF;
                    if (idx == 0xFF || idx >= palette.length) {
                        continue;
                    }
                    if (this.biomeIdMatches(palette[idx], norm, tagQuery, tagRl)) {
                        int[] heights = MapStore.storedHeights(level.dimension(), pos);
                        int y = heights != null && heights[i] != Integer.MIN_VALUE ? heights[i] - 1 : 64;
                        String label = this.biomeIdLabel(palette[idx]);
                        var5.add(new WorldMapScreen.SearchHit(cx * 16 + (i & 15), y, cz * 16 + (i >> 4), label, -13996851));
                        break;
                    }
                }
            });
        }

        var5.sort(Comparator.comparingDouble(this::hitDist2));
        this.searchResults = var5;
        if (var5.isEmpty()) {
            this.searchIndex = 0;
            this.searchStatus = I18n.get("gui.unboundmaps.searchNoResults");
        } else {
            this.searchIndex = 0;
            WorldMapScreen.SearchHit var20 = var5.get(0);
            this.searchStatus = I18n.get("gui.unboundmaps.searchFound", var5.size())
                + (var5.size() >= 512 ? "+" : "")
                + " \u2014 "
                + var20.label()
                + " ["
                + var20.x()
                + ", "
                + var20.y()
                + ", "
                + var20.z()
                + "]";
            this.centerOn(var20.x(), var20.z());
        }
    }

    /** Localized biome name of a holder (works for live and resolved stored biomes). */
    private String biomeHolderLabel(Holder<Biome> var0) {
        return var0.unwrapKey()
            .map(var1 -> Component.translatable(var1.location().toLanguageKey("biome")).getString())
            .orElse("?");
    }

    private String biomeLabel(Holder<Biome> var0) {
        return this.biomeHolderLabel(var0);
    }

    private String biomeIdLabel(String var0) {
        ResourceLocation var1 = ResourceLocation.tryParse(var0);
        if (var1 == null) {
            return var0;
        }
        return Component.translatable(var1.toLanguageKey("biome")).getString();
    }

    /** Matches a biome holder: #tag, registry path (english) or localized name (current language). */
    private boolean biomeHolderMatches(Holder<Biome> var0, String var1, boolean var2, ResourceLocation var3) {
        if (var0 == null) {
            return false;
        }
        try {
            if (var2) {
                return var0.is(TagKey.create(Registries.BIOME, var3));
            }

            ResourceLocation var4 = var0.unwrapKey().map(var5 -> var5.location()).orElse(null);
            if (var4 != null && var4.getPath().toLowerCase(Locale.ROOT).replace('_', ' ').contains(var1)) {
                return true;
            }

            return this.biomeHolderLabel(var0).toLowerCase(Locale.ROOT).contains(var1);
        } catch (Exception var6) {
            return false;
        }
    }

    /** Matches a stored biome id (namespace:path) against the same query rules. */
    private boolean biomeIdMatches(String var0, String var1, boolean var2, ResourceLocation var3) {
        ResourceLocation var4 = ResourceLocation.tryParse(var0);
        if (var4 == null) {
            return false;
        }
        try {
            ClientLevel var9 = this.minecraft.level;
            if (var9 != null) {
                net.minecraft.core.Registry<Biome> var10 = var9.registryAccess().registryOrThrow(Registries.BIOME);
                Optional<Holder.Reference<Biome>> var5 = var10.getHolder(ResourceKey.create(Registries.BIOME, var4));
                if (var5.isPresent()) {
                    return this.biomeHolderMatches(var5.get(), var1, var2, var3);
                }
            }
        } catch (Exception var6) {
        }

        return !var2 && var4.getPath().toLowerCase(Locale.ROOT).replace('_', ' ').contains(var1);
    }

    /** Matches a block against the query: #tag, registry id (mod:block), translation key or localized name. */
    private boolean blockMatches(net.minecraft.world.level.block.Block var1, String var2) {
        try {
            if (var2.startsWith("#")) {
                String var3t = var2.substring(1).trim().replace(' ', '_');
                ResourceLocation var3r = ResourceLocation.tryParse(var3t.contains(":") ? var3t : "minecraft:" + var3t);
                if (var3r == null) {
                    return false;
                }
                return BuiltInRegistries.BLOCK.wrapAsHolder(var1).is(TagKey.create(Registries.BLOCK, var3r));
            }

            if (BuiltInRegistries.BLOCK.getKey(var1).toString().contains(var2)) {
                return true;
            }

            String var3 = var1.getDescriptionId();
            if (var3.toLowerCase(Locale.ROOT).contains(var2)) {
                return true;
            }

            return var1.getName().getString().toLowerCase(Locale.ROOT).contains(var2);
        } catch (Exception var4) {
            return false;
        }
    }

    private double hitDist2(WorldMapScreen.SearchHit var1) {
        double var2 = this.minecraft.player != null ? this.minecraft.player.getX() - var1.x() : 0.0;
        double var4 = this.minecraft.player != null ? this.minecraft.player.getZ() - var1.z() : 0.0;
        return var2 * var2 + var4 * var4;
    }

    private void centerOn(int var1, int var2) {
        this.centerX = var1 + 0.5;
        this.centerZ = var2 + 0.5;
    }

    private void renderSearchResults(GuiGraphics var1) {
        if (!this.searchResults.isEmpty()) {
            Matrix4f var2 = var1.pose().last().pose();
            float var3 = (float)(Math.sin((double)Util.getMillis() / 180.0) * 0.5 + 0.5);
            int var4 = 0;

            for (int var5 = 0; var5 < this.searchResults.size() && var4 < 48; var5++) {
                WorldMapScreen.SearchHit var6 = this.searchResults.get(var5);
                float var7 = (float)(this.width / 2.0 + (var6.x() + 0.5 - this.centerX) / this.blocksPerPixel);
                float var8 = (float)(this.height / 2.0 + (var6.z() + 0.5 - this.centerZ) / this.blocksPerPixel);
                if (var7 >= -12.0F && var8 >= -12.0F && var7 <= (float)this.width + 12.0F && var8 <= (float)this.height + 12.0F) {
                    var4++;
                    float var9 = 5.0F + var3 * 2.0F + (var5 == this.searchIndex ? 3.0F : 0.0F);
                    DrawHelper.ring(var2, var7, var8, var9, var9 + 2.0F, 24, var5 == this.searchIndex ? -1 : var6.color());
                    if (var5 == this.searchIndex) {
                        String var10 = var6.label() + " [" + var6.x() + ", " + var6.y() + ", " + var6.z() + "]";
                        LabelPlacer.placeForce((float)var7 - this.font.width(var10) / 2.0F, (float)var8 - 14.0F, (float)this.font.width(var10), 10.0F);
                        var1.drawString(
                            this.font,
                            var10,
                            (int)var7 - this.font.width(var10) / 2,
                            (int)var8 - 14,
                            -1,
                            true
                        );
                    }
                }
            }
        }
    }

    public void onClose() {
        WaypointManager.saveNow();
        super.onClose();
    }

    public boolean isPauseScreen() {
        return false;
    }

    private record SearchHit(int x, int y, int z, String label, int color) {
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
