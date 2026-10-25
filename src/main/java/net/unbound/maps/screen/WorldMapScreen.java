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
    // settings panel (gear button) + transient status line
    private double mousePosX = -1.0;
    private double mousePosY = -1.0;
    private boolean settingsOpen;
    private int settingsPage;
    private boolean resetArmed;
    private String statusMsg;
    private long statusUntil;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private static final int PAGE_SETTINGS = 0;
    private static final int PAGE_COUNT = 6;
    private static final int PANEL_WIDTH = 262;
    private static final int PANEL_HEADER = 16;
    private static final int PANEL_FOOTER = 16;
    private static final int[] COLOR_PRESETS = new int[]{
        (int)0xFFFD5B5C, (int)0xFFFFA726, (int)0xFFAB47BC, (int)0xFF42A5F5, (int)0xFF66BB6A, (int)0xFFFDD835
    };
    // settings row action ids
    private static final int A_AUTOCAVE = 0;
    private static final int A_NIGHTDIM = 1;
    private static final int A_TERRAIN = 2;
    private static final int A_BIOMECOL = 3;
    private static final int A_LIGHT = 4;
    private static final int A_RADAR = 5;
    private static final int A_ANCHOR = 6;
    private static final int A_BIGCENTERED = 7;
    private static final int A_DEATHPOINTS = 8;
    private static final int A_DEATHBEACON = 9;
    private static final int A_OLDDEATHS = 10;
    private static final int A_DEATHCOLOR = 11;
    private static final int A_OLDDEATHCOLOR = 12;
    private static final int A_RESET = 13;

    /** Current zoom of the world map (blocks per texture pixel), read by the cell cache for its LOD choice. */
    public static double currentBlocksPerPixel() {
        return blocksPerPixel;
    }

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
        // draw the opaque background NOW: the map tiles below are drawn through
        // the Tesselator (immediate), while fill() batches into the gui buffer -
        // flushing here fixes their draw order deterministically
        var1.flush();
        ClientLevel var8 = var5.level;
        if (var8 != null) {
            WorldMapCells.ensureVisible(var8, this.centerX, this.centerZ, var6, var7, this.blocksPerPixel);
            double var9 = this.centerX - var6 / 2.0 * this.blocksPerPixel;
            double var11 = this.centerZ - var7 / 2.0 * this.blocksPerPixel;
            int cellSize = WorldMapCells.cellBlocks();
            int var13 = Math.floorDiv(Mth.floor(var9), cellSize);
            int var14 = Math.floorDiv(Mth.floor(var9 + var6 * this.blocksPerPixel), cellSize);
            int var15 = Math.floorDiv(Mth.floor(var11), cellSize);
            int var16 = Math.floorDiv(Mth.floor(var11 + var7 * this.blocksPerPixel), cellSize);
            Matrix4f var17 = var1.pose().last().pose();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            // uniform Xaero-like night dim as a draw-time tint (cave view is exempt:
            // it is lit by block light, not by the sun); baked tiles are shared
            // between day and night, so no re-bake happens when the sky darkens
            float nightF = MapTileCache.nightFactor(MapTileCache.isCave());
            RenderSystem.setShaderColor(nightF, nightF, nightF, 1.0F);

            for (int var18 = var15; var18 <= var16; var18++) {
                for (int var19 = var13; var19 <= var14; var19++) {
                    WorldMapCells.Cell var20 = WorldMapCells.cell(var19, var18);
                    if (WorldMapCells.isDrawable(var20)) {
                        double wx = (double)var19 * (double)cellSize;
                        double wz = (double)var18 * (double)cellSize;
                        double sx = (wx - var9) / this.blocksPerPixel;
                        double sy = (wz - var11) / this.blocksPerPixel;
                        double ss = (double)cellSize / this.blocksPerPixel;
                        float var26 = Math.max((float)sx, 0.0F);
                        float var27 = Math.max((float)sy, 0.0F);
                        float var28 = Math.min((float)(sx + ss), (float)var6);
                        float var29 = Math.min((float)(sy + ss), (float)var7);
                        if (!(var28 <= var26) && !(var29 <= var27)) {
                            float var30 = (float)(((var26 - sx) / ss));
                            float var31 = (float)(((var27 - sy) / ss));
                            float var32 = (float)(((var28 - sx) / ss));
                            float var33 = (float)(((var29 - sy) / ss));
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
            var1.flush();
            if (var2 >= 0 && var3 >= 0 && !this.settingsOpen) {
                // highlight the marker under the cursor: this is the one the
                // Del key (or the right-click menu) will act on
                Waypoint hovered = this.findWaypointNear((double)var2, (double)var3);
                if (hovered != null) {
                    float hx = (float)(var6 / 2.0 + (hovered.x + 0.5 - this.centerX) / this.blocksPerPixel);
                    float hy = (float)(var7 / 2.0 + (hovered.z + 0.5 - this.centerZ) / this.blocksPerPixel);
                    DrawHelper.outline(var17, hx - 5.0F, hy - 5.0F, hx + 5.0F, hy + 5.0F, 1.0F, -1);
                }
            }

            this.renderSearchResults(var1);
            EntityRadar.drawWorldMap(var1, this.centerX, this.centerZ, var6, var7, this.blocksPerPixel, var5.player);
            RenderSystem.disableBlend();
        }

        this.renderCursorBar(var1, var2, var3);
        String zoomText = I18n.get("gui.unboundmaps.zoomLabel", String.format(Locale.ROOT, "%.2f", this.blocksPerPixel));
        var1.drawString(var5.font, zoomText, var6 - var5.font.width(zoomText) - 8, 8, -4668212, true);
        this.renderGearButton(var1, var2, var3);
        if (this.statusMsg != null && Util.getMillis() < this.statusUntil) {
            var1.drawString(var5.font, this.statusMsg, 10, 30, -1643021, true);
        }

        this.renderHeightSlider(var1, var2, var3);
        super.render(var1, var2, var3, var4);
        if (this.menu != null) {
            this.renderMenu(var1, var2, var3);
        }

        if (this.searchOpen && this.searchStatus != null) {
            var1.drawString(var5.font, this.searchStatus, 10, 30, -1643021, true);
        }

        if (this.settingsOpen) {
            this.renderSettingsPanel(var1, var2, var3);
        }
    }

    /** Xaero-style view height slider: leftmost = AUTO, further right = fixed slice height. */
    private void renderHeightSlider(GuiGraphics var1, int var2, int var3) {
        this.sliderX = 12;
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
        var1.drawString(this.font, label, this.sliderX, this.sliderY - 11, CaveState.isAuto() ? -4668212 : -1643021, true);
        var1.fill(this.sliderX, this.sliderY + 3, this.sliderX + SLIDER_WIDTH, this.sliderY + 6, -12959930);
        var1.fill(this.sliderX, this.sliderY + 3, this.sliderX + 14, this.sliderY + 6, -1251747841);
        var1.fill(handleX, this.sliderY, handleX + handleW, this.sliderY + 9, hover || this.draggingSlider ? -1 : -6908266);
        var1.renderOutline(handleX - 1, this.sliderY - 1, handleW + 2, 11, -267382764);
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

    /** Splits a (possibly multi-line) text into lines no wider than maxWidth, word-wrapped. */
    private static List<String> wrapText(String text, int maxWidth) {
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        List<String> out = new ArrayList<>();

        for (String para : text.split("\n")) {
            if (para.isBlank()) {
                out.add("");
                continue;
            }
            StringBuilder cur = new StringBuilder();
            for (String w : para.trim().split(" ")) {
                String cand = cur.length() == 0 ? w : cur + " " + w;
                if (cur.length() > 0 && font.width(cand) > maxWidth) {
                    out.add(cur.toString());
                    cur = new StringBuilder(w);
                } else {
                    cur = new StringBuilder(cand);
                }
            }
            if (cur.length() > 0) {
                out.add(cur.toString());
            }
        }

        return out.isEmpty() ? List.of(text) : out;
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
        int var18 = Math.min(var17 + 12, Math.max(40, this.width - 4));
        int var19 = 30;
        int var20 = Math.min(var19 + var16.size() * 14 + 3, Math.max(30, this.height - 4));
        int var21 = (int)Math.min(var1, (double)(this.width - var18 - 2));
        int var22 = (int)Math.min(var3, (double)(this.height - var20 - 2));
        this.menu = new WorldMapScreen.Menu(var21, var22, var18, var20, var12, var14, var13, var16, actions, var15);
    }

    private Waypoint findWaypointNear(double var1, double var3) {
        Waypoint var5 = null;
        double var6 = 144.0;

        for (Waypoint var9 : WaypointManager.currentWaypoints()) {
            if (!WaypointRenderer.visible(var9)) {
                continue;
            }
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
        // NOTE: renderOutline takes (x, y, WIDTH, HEIGHT) in 1.21.1 - passing the
        // far corner drew giant misplaced rectangles all over the map (the old
        // "crooked interface" bug)
        var1.renderOutline(var5.x, var5.y, var5.width, var5.height, -12959930);
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
        if (this.settingsOpen) {
            return true;
        }
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
        if (this.settingsOpen) {
            return true;
        }
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

    @Override
    public void mouseMoved(double var1, double var3) {
        this.mousePosX = var1;
        this.mousePosY = var3;
        super.mouseMoved(var1, var3);
    }

    public boolean mouseClicked(double var1, double var3, int var5) {
        if (this.settingsOpen) {
            if (var1 >= this.panelX && var1 < this.panelX + this.panelW && var3 >= this.panelY && var3 < this.panelY + this.panelH) {
                this.handlePanelClick(var1, var3);
            } else {
                this.settingsOpen = false;
                this.resetArmed = false;
            }

            return true;
        }

        if (var5 == 0 && !this.searchOpen && var1 >= 6.0 && var1 < 28.0 && var3 >= 6.0 && var3 < 28.0) {
            this.settingsOpen = true;
            this.settingsPage = PAGE_SETTINGS;
            return true;
        }

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
        } else if (this.settingsOpen && var1 == 256) {
            this.settingsOpen = false;
            this.resetArmed = false;
            return true;
        } else if (var1 == 261 && !this.settingsOpen) {
            // Del: delete the waypoint under the cursor - no menu needed
            Waypoint varW = this.findWaypointNear(this.mousePosX, this.mousePosY);
            if (varW != null) {
                WaypointManager.remove(varW.id);
                this.showStatus(I18n.get("gui.unboundmaps.waypointDeleted", varW.name));
                return true;
            }

            return true;
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

        // 2) stored chunks: the whole explored map from the persistent store.
        // Runs EVEN when the live scan filled its cap - otherwise common biomes
        // would only ever match near the player (loaded chunks) and the search
        // would never see the rest of the explored map.
        if (MapStore.isEnabled()) {
            ClientLevel level = var9;
            List<WorldMapScreen.BiomeSig> sigs = this.buildBiomeSignatures(var9);
            List<WorldMapScreen.BiomeSig> matchingSigs = sigs == null
                ? List.of()
                : sigs.stream().filter(s -> this.biomeIdMatches(s.id, norm, false, null)).toList();
            MapStore.forEachStoredChunk((pos, heights, colors, palette, cols) -> {
                if (var5.size() >= 2048) {
                    return;
                }
                int cx = ChunkPos.getX(pos);
                int cz = ChunkPos.getZ(pos);
                if (level.getChunk(cx, cz, ChunkStatus.FULL, false) != null) {
                    return; // already covered by the live scan
                }
                if (palette != null && palette.length > 0 && cols != null) {
                    // exact: per-column biome palette recorded since 1.6.0
                    for (int i = 0; i < 256; i++) {
                        int idx = cols[i] & 0xFF;
                        if (idx == 0xFF || idx >= palette.length) {
                            continue;
                        }
                        if (this.biomeIdMatches(palette[idx], norm, tagQuery, tagRl)) {
                            int y = heights != null && heights[i] != Integer.MIN_VALUE ? heights[i] : 64;
                            String label = this.biomeIdLabel(palette[idx]);
                            var5.add(new WorldMapScreen.SearchHit(cx * 16 + (i & 15), y, cz * 16 + (i >> 4), label, -13996851));
                            break;
                        }
                    }
                } else if (!matchingSigs.isEmpty() && colors != null && !tagQuery) {
                    // legacy chunk (explored before biome palettes were stored):
                    // match the stored surface color against biome tint signatures.
                    // Hillshade and water depth scale all channels uniformly, so the
                    // normalized color still identifies the biome tint applied at
                    // explore time. Best-effort for old data only.
                    for (int i = 0; i < 256 && var5.size() < 2048; i++) {
                        int color = colors[i];
                        if (color == MapTileCache.UNKNOWN || (color >>> 24) != 0xFF) {
                            continue;
                        }
                        for (WorldMapScreen.BiomeSig sig : matchingSigs) {
                            if (sig.matches(color)) {
                                int y = heights != null && heights[i] != Integer.MIN_VALUE ? heights[i] : 64;
                                var5.add(new WorldMapScreen.SearchHit(
                                    cx * 16 + (i & 15), y, cz * 16 + (i >> 4), this.biomeIdLabel(sig.id), -13996851
                                ));
                                break;
                            }
                        }
                    }
                }
            });
        }

        var5.sort(Comparator.comparingDouble(this::hitDist2));
        if (var5.size() > 512) {
            var5.subList(512, var5.size()).clear();
        }
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

    /**
     * Color signatures for the LEGACY biome search (chunks explored before biome
     * palettes were stored). The stored color of a column is
     * {@code baseMapColor * biomeTint * hillshade}: hillshade scales all channels
     * uniformly, so the normalized RGB identifies the biome that tinted it.
     * Each biome gets four candidates: grass tint on the grass-block map color,
     * grass tint on the plant map color, foliage tint on the foliage map color
     * and the biome water color.
     */
    private record BiomeSig(String id, float r, float g, float b) {
        static final float TOLERANCE = 0.06F;

        /** Normalized RGB (sum = 1) of an ARGB-ish tint color. */
        private static float[] norm(int r, int g, int b) {
            float sum = r + g + b;
            return sum <= 0.0F ? new float[]{0.0F, 0.0F, 0.0F} : new float[]{r / sum, g / sum, b / sum};
        }

        static BiomeSig of(String id, int baseRgb, int tintRgb) {
            float[] n = norm(
                (baseRgb >> 16 & 0xFF) * (tintRgb >> 16 & 0xFF),
                (baseRgb >> 8 & 0xFF) * (tintRgb >> 8 & 0xFF),
                (baseRgb & 0xFF) * (tintRgb & 0xFF)
            );
            return new BiomeSig(id, n[0], n[1], n[2]);
        }

        static BiomeSig plain(String id, int rgb) {
            float[] n = norm(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF);
            return new BiomeSig(id, n[0], n[1], n[2]);
        }

        /** L1 match against a stored ABGR column color (uniform brightness is ignored). */
        boolean matches(int storedAbgr) {
            float[] n = norm(storedAbgr & 0xFF, storedAbgr >>> 8 & 0xFF, storedAbgr >>> 16 & 0xFF);
            return Math.abs(n[0] - this.r) + Math.abs(n[1] - this.g) + Math.abs(n[2] - this.b) < TOLERANCE;
        }
    }

    /** All biome color signatures of the current dimension's biome registry (null on failure). */
    private List<WorldMapScreen.BiomeSig> buildBiomeSignatures(ClientLevel var0) {
        try {
            net.minecraft.core.Registry<Biome> var1 = var0.registryAccess().registryOrThrow(Registries.BIOME);
            int grassBase = net.minecraft.world.level.material.MapColor.GRASS.col & 0xFFFFFF;
            int plantBase = net.minecraft.world.level.material.MapColor.PLANT.col & 0xFFFFFF;
            List<WorldMapScreen.BiomeSig> var2 = new ArrayList<>();
            for (Holder.Reference<Biome> var3 : var1.holders().toList()) {
                String var4 = var1.getKey(var3.value()).toString();
                Biome var5 = var3.value();
                int var6;
                int var7;
                try {
                    var6 = var5.getGrassColor(0.0, 0.0);
                    var7 = var5.getFoliageColor();
                } catch (Exception var8) {
                    continue;
                }
                var2.add(WorldMapScreen.BiomeSig.of(var4, grassBase, var6));
                var2.add(WorldMapScreen.BiomeSig.of(var4, plantBase, var6));
                var2.add(WorldMapScreen.BiomeSig.of(var4, plantBase, var7));
                var2.add(WorldMapScreen.BiomeSig.plain(var4, var5.getWaterColor()));
            }
            return var2;
        } catch (Exception var9) {
            return null;
        }
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

    /** Transient status line under the gear button (waypoint deleted, map reset, ...). */
    private void showStatus(String var1) {
        this.statusMsg = var1;
        this.statusUntil = Util.getMillis() + 3000L;
    }

    /** Procedurally drawn gear button in the top-left corner (no unicode glyph dependency). */
    private void renderGearButton(GuiGraphics var1, int var2, int var3) {
        if (this.searchOpen) {
            return;
        }
        boolean hover = var2 >= 6 && var2 < 28 && var3 >= 6 && var3 < 28;
        var1.flush();
        Matrix4f pose = var1.pose().last().pose();
        int var4 = hover ? -1 : 0xFFC8CDD4;
        if (hover) {
            var1.fill(6, 6, 28, 28, 0x50FFFFFF);
        }

        DrawHelper.disc(pose, 17.0F, 17.0F, 4.6F, 16, var4);

        for (int var5 = 0; var5 < 8; var5++) {
            double var6 = var5 * (Math.PI / 4.0) + Math.PI / 8.0;
            int var7 = 17 + (int)Math.round(Math.cos(var6) * 6.0);
            int var8 = 17 + (int)Math.round(Math.sin(var6) * 6.0);
            var1.fill(var7 - 1, var8 - 1, var7 + 2, var8 + 2, var4);
        }

        DrawHelper.disc(pose, 17.0F, 17.0F, 1.9F, 12, BG_OPAQUE);
        var1.flush();
        if (hover) {
            String var9 = I18n.get("gui.unboundmaps.gearTooltip");
            var1.drawString(this.font, var9, var2 + 10, var3 - 8, -1, true);
        }
    }

    private List<WorldMapScreen.SettingsRow> buildSettingsRows() {
        List<WorldMapScreen.SettingsRow> var0 = new ArrayList<>();
        var0.add(new WorldMapScreen.SettingsRow(
            I18n.get("gui.unboundmaps.set.autoCave"), this.onOff(UnboundConfig.AUTO_CAVE_MODE.get()), this.boolColor(UnboundConfig.AUTO_CAVE_MODE.get()), -1, A_AUTOCAVE
        ));
        int var1 = UnboundConfig.NIGHT_DIM_STRENGTH.get();
        var0.add(new WorldMapScreen.SettingsRow(I18n.get("gui.unboundmaps.set.nightDim"), String.valueOf(var1), -2039584, -1, A_NIGHTDIM));
        var0.add(new WorldMapScreen.SettingsRow(
            I18n.get("gui.unboundmaps.set.terrain"), this.onOff(UnboundConfig.TERRAIN_SHADING.get()), this.boolColor(UnboundConfig.TERRAIN_SHADING.get()), -1, A_TERRAIN
        ));
        var0.add(new WorldMapScreen.SettingsRow(
            I18n.get("gui.unboundmaps.set.biomeColors"), this.onOff(UnboundConfig.BIOME_SHADING.get()), this.boolColor(UnboundConfig.BIOME_SHADING.get()), -1, A_BIOMECOL
        ));
        var0.add(new WorldMapScreen.SettingsRow(
            I18n.get("gui.unboundmaps.set.light"), this.onOff(UnboundConfig.LIGHT_SHADING.get()), this.boolColor(UnboundConfig.LIGHT_SHADING.get()), -1, A_LIGHT
        ));
        var0.add(new WorldMapScreen.SettingsRow(
            I18n.get("gui.unboundmaps.set.radar"), this.onOff(UnboundConfig.ENTITY_RADAR.get()), this.boolColor(UnboundConfig.ENTITY_RADAR.get()), -1, A_RADAR
        ));
        var0.add(new WorldMapScreen.SettingsRow(
            I18n.get("gui.unboundmaps.set.minimapPos"),
            I18n.get("gui.unboundmaps.anchor." + UnboundConfig.MINIMAP_ANCHOR.get().name()),
            -2039584,
            -1,
            A_ANCHOR
        ));
        var0.add(new WorldMapScreen.SettingsRow(
            I18n.get("gui.unboundmaps.set.bigCentered"),
            this.onOff(UnboundConfig.MINIMAP_BIG_CENTERED.get()),
            this.boolColor(UnboundConfig.MINIMAP_BIG_CENTERED.get()),
            -1,
            A_BIGCENTERED
        ));
        var0.add(new WorldMapScreen.SettingsRow(
            I18n.get("gui.unboundmaps.set.deathPoints"), this.onOff(UnboundConfig.DEATH_POINTS.get()), this.boolColor(UnboundConfig.DEATH_POINTS.get()), -1, A_DEATHPOINTS
        ));
        var0.add(new WorldMapScreen.SettingsRow(
            I18n.get("gui.unboundmaps.set.deathBeacon"),
            this.onOff(UnboundConfig.DEATH_BEACON.get()),
            this.boolColor(UnboundConfig.DEATH_BEACON.get()),
            -1,
            A_DEATHBEACON
        ));
        var0.add(new WorldMapScreen.SettingsRow(
            I18n.get("gui.unboundmaps.set.oldDeaths"),
            this.onOff(UnboundConfig.SHOW_OLD_DEATH_POINTS.get()),
            this.boolColor(UnboundConfig.SHOW_OLD_DEATH_POINTS.get()),
            -1,
            A_OLDDEATHS
        ));
        var0.add(new WorldMapScreen.SettingsRow(
            I18n.get("gui.unboundmaps.set.deathColor"), "", -1, UnboundConfig.DEATH_COLOR.get() | 0xFF000000, A_DEATHCOLOR
        ));
        var0.add(new WorldMapScreen.SettingsRow(
            I18n.get("gui.unboundmaps.set.oldDeathColor"), "", -1, UnboundConfig.DEATH_OLD_COLOR.get() | 0xFF000000, A_OLDDEATHCOLOR
        ));
        var0.add(new WorldMapScreen.SettingsRow(
            this.resetArmed ? I18n.get("gui.unboundmaps.set.resetConfirm") : I18n.get("gui.unboundmaps.set.resetMap"),
            "",
            this.resetArmed ? -1749940 : -1,
            -1,
            A_RESET
        ));
        return var0;
    }

    private String onOff(boolean var1) {
        return I18n.get(var1 ? "gui.unboundmaps.on" : "gui.unboundmaps.off");
    }

    private int boolColor(boolean var1) {
        return var1 ? -8355712 : -6316128;
    }

    /** Click on a settings row: toggle / cycle its value. */
    private void activateRow(int var0) {
        switch (var0) {
            case A_AUTOCAVE:
                UnboundConfig.AUTO_CAVE_MODE.set(!UnboundConfig.AUTO_CAVE_MODE.get());
                MapTileCache.onCaveViewChanged();
                break;
            case A_NIGHTDIM:
                UnboundConfig.NIGHT_DIM_STRENGTH.set((UnboundConfig.NIGHT_DIM_STRENGTH.get() + 1) % 4);
                break;
            case A_TERRAIN:
                UnboundConfig.TERRAIN_SHADING.set(!UnboundConfig.TERRAIN_SHADING.get());
                this.clearBakedTiles();
                break;
            case A_BIOMECOL:
                UnboundConfig.BIOME_SHADING.set(!UnboundConfig.BIOME_SHADING.get());
                this.clearBakedTiles();
                break;
            case A_LIGHT:
                UnboundConfig.LIGHT_SHADING.set(!UnboundConfig.LIGHT_SHADING.get());
                this.clearBakedTiles();
                break;
            case A_RADAR:
                UnboundConfig.ENTITY_RADAR.set(!UnboundConfig.ENTITY_RADAR.get());
                break;
            case A_ANCHOR:
                UnboundConfig.Anchor[] var1 = UnboundConfig.Anchor.values();
                UnboundConfig.MINIMAP_ANCHOR.set(var1[(UnboundConfig.MINIMAP_ANCHOR.get().ordinal() + 1) % var1.length]);
                break;
            case A_BIGCENTERED:
                UnboundConfig.MINIMAP_BIG_CENTERED.set(!UnboundConfig.MINIMAP_BIG_CENTERED.get());
                break;
            case A_DEATHPOINTS:
                UnboundConfig.DEATH_POINTS.set(!UnboundConfig.DEATH_POINTS.get());
                break;
            case A_DEATHBEACON:
                UnboundConfig.DEATH_BEACON.set(!UnboundConfig.DEATH_BEACON.get());
                break;
            case A_OLDDEATHS:
                UnboundConfig.SHOW_OLD_DEATH_POINTS.set(!UnboundConfig.SHOW_OLD_DEATH_POINTS.get());
                break;
            case A_DEATHCOLOR:
                UnboundConfig.DEATH_COLOR.set(this.nextPreset(UnboundConfig.DEATH_COLOR.get()));
                break;
            case A_OLDDEATHCOLOR:
                UnboundConfig.DEATH_OLD_COLOR.set(this.nextPreset(UnboundConfig.DEATH_OLD_COLOR.get()));
                break;
            case A_RESET:
                if (this.resetArmed) {
                    this.performMapReset();
                    this.resetArmed = false;
                } else {
                    this.resetArmed = true;
                }
                break;
        }
    }

    private int nextPreset(int var0) {
        for (int var1 = 0; var1 < COLOR_PRESETS.length; var1++) {
            if ((COLOR_PRESETS[var1] | 0xFF000000) == (var0 | 0xFF000000)) {
                return COLOR_PRESETS[(var1 + 1) % COLOR_PRESETS.length] | 0xFF000000;
            }
        }

        return COLOR_PRESETS[0] | 0xFF000000;
    }

    /** Shading options are baked into the tiles, so the caches must be rebuilt after a change. */
    private void clearBakedTiles() {
        MapTileCache.clearAll();
        WorldMapCells.clearAll();
    }

    /** Wipes the explored map of the current dimension (waypoints are kept). */
    private void performMapReset() {
        boolean var0 = MapStore.resetCurrentDimension();
        this.clearBakedTiles();
        net.unbound.maps.mapdata.MinimapTexture.invalidateAll();
        this.showStatus(I18n.get(var0 ? "gui.unboundmaps.mapResetDone" : "gui.unboundmaps.mapResetFail"));
    }

    private void renderSettingsPanel(GuiGraphics var1, int var2, int var3) {
        int var4 = 8;
        int var5 = 28;
        int var6 = PANEL_WIDTH;
        String var7;
        List<String> var8 = List.of();
        List<WorldMapScreen.SettingsRow> var9 = List.of();
        if (this.settingsPage == PAGE_SETTINGS) {
            var7 = I18n.get("gui.unboundmaps.settings");
            var9 = this.buildSettingsRows();
        } else {
            var7 = I18n.get("gui.unboundmaps.guideTitle" + this.settingsPage);
            var8 = wrapText(I18n.get("gui.unboundmaps.guide" + this.settingsPage), var6 - 14);
        }

        int var10 = this.settingsPage == PAGE_SETTINGS ? var9.size() * 12 + 2 : var8.size() * 10 + 4;
        int var11 = Math.min(PANEL_HEADER + var10 + PANEL_FOOTER, this.height - var5 - 4);
        int varMaxLines = (var11 - PANEL_HEADER - PANEL_FOOTER) / 10;
        this.panelX = var4;
        this.panelY = var5;
        this.panelW = var6;
        this.panelH = var11;
        var1.fill(var4, var5, var4 + var6, var5 + var11, 0xF00E1014);
        var1.renderOutline(var4, var5, var6, var11, -12500670);
        var1.drawString(this.font, var7, var4 + 6, var5 + 4, -1, true);
        var1.fill(var4 + 3, var5 + PANEL_HEADER - 2, var4 + var6 - 3, var5 + PANEL_HEADER - 1, -12500670);
        int var12 = var5 + PANEL_HEADER + 1;
        if (this.settingsPage == PAGE_SETTINGS) {
            for (WorldMapScreen.SettingsRow var14 : var9) {
                boolean var15 = var2 >= var4 + 2 && var2 < var4 + var6 - 2 && var3 >= var12 && var3 < var12 + 12;
                if (var15) {
                    var1.fill(var4 + 2, var12, var4 + var6 - 2, var12 + 12, 0x30FFFFFF);
                }

                var1.drawString(this.font, var14.label(), var4 + 6, var12 + 2, var14.action() == A_RESET && this.resetArmed ? -1749940 : -1, false);
                if (var14.swatch() != -1) {
                    int var16 = var4 + var6 - 18;
                    var1.fill(var16, var12 + 2, var16 + 10, var12 + 10, -16645502);
                    var1.fill(var16 + 2, var12 + 4, var16 + 8, var12 + 8, var14.swatch());
                    var1.renderOutline(var16, var12 + 2, 10, 8, -10914234);
                } else {
                    var1.drawString(this.font, var14.value(), var4 + var6 - 6 - this.font.width(var14.value()), var12 + 2, var14.valueColor(), false);
                }

                var12 += 12;
            }
        } else {
            int varLineIdx = 0;
            for (String var14b : var8) {
                if (varLineIdx >= varMaxLines) {
                    break;
                }
                if (!var14b.isEmpty()) {
                    var1.drawString(this.font, var14b, var4 + 7, var12, -2299710, false);
                }

                var12 += 10;
                varLineIdx++;
            }
        }

        int var13 = var5 + var11 - PANEL_FOOTER;
        var1.fill(var4 + 3, var13 - 1, var4 + var6 - 3, var13, -12500670);
        boolean var20 = var2 >= var4 + 4 && var2 < var4 + 18 && var3 >= var13 + 2 && var3 < var13 + 13;
        boolean var21 = var2 >= var4 + var6 - 18 && var2 < var4 + var6 - 4 && var3 >= var13 + 2 && var3 < var13 + 13;
        var1.fill(var4 + 4, var13 + 2, var4 + 18, var13 + 13, var20 ? 0x50FFFFFF : 0x28FFFFFF);
        var1.fill(var4 + var6 - 18, var13 + 2, var4 + var6 - 4, var13 + 13, var21 ? 0x50FFFFFF : 0x28FFFFFF);
        var1.drawCenteredString(this.font, "<", var4 + 11, var13 + 4, -1);
        var1.drawCenteredString(this.font, ">", var4 + var6 - 11, var13 + 4, -1);
        var1.drawCenteredString(this.font, I18n.get("gui.unboundmaps.page", this.settingsPage + 1, PAGE_COUNT), var4 + var6 / 2, var13 + 4, -6316128);
    }

    private void handlePanelClick(double var1, double var3) {
        int var4 = this.panelX;
        int var5 = this.panelY;
        int var6 = this.panelW;
        int var7 = this.panelH;
        int var8 = var5 + var7 - PANEL_FOOTER;
        if (var3 >= var8 + 2 && var3 < var8 + 13) {
            if (var1 >= var4 + 4 && var1 < var4 + 18) {
                this.settingsPage = (this.settingsPage + PAGE_COUNT - 1) % PAGE_COUNT;
                return;
            }

            if (var1 >= var4 + var6 - 18 && var1 < var4 + var6 - 4) {
                this.settingsPage = (this.settingsPage + 1) % PAGE_COUNT;
                return;
            }
        }

        if (this.settingsPage != PAGE_SETTINGS) {
            return;
        }
        int var9 = var5 + PANEL_HEADER + 1;
        if (var3 < var9) {
            return;
        }
        int var10 = (int)((var3 - var9) / 12.0);
        if (var10 >= 0) {
            List<WorldMapScreen.SettingsRow> var11 = this.buildSettingsRows();
            if (var10 < var11.size()) {
                this.activateRow(var11.get(var10).action());
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

    /** One clickable row of the settings panel: label + right-aligned value or color swatch. */
    private record SettingsRow(String label, String value, int valueColor, int swatch, int action) {
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
