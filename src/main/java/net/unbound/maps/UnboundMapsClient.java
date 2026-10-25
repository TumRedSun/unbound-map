package net.unbound.maps;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut;
import net.neoforged.neoforge.client.event.ClientTickEvent.Post;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage;
import net.neoforged.neoforge.common.NeoForge;
import net.unbound.maps.keybinds.UnboundKeys;
import net.unbound.maps.mapdata.MapStore;
import net.unbound.maps.mapdata.MapTileCache;
import net.unbound.maps.mapdata.MinimapTexture;
import net.unbound.maps.mapdata.WorldMapCells;
import net.unbound.maps.render.MinimapHud;
import net.unbound.maps.render.WorldBeaconRenderer;
import net.unbound.maps.render.WorldMarkerOverlay;
import net.unbound.maps.waypoints.WaypointManager;

public final class UnboundMapsClient {
    private static ResourceKey<Level> lastDimension;
    private static long manualCaveUntil;
    private static boolean lastUnderground;
    private static boolean appliedUnderground;
    private static int undergroundStableTicks;

    private UnboundMapsClient() {
    }

    public static void bootstrap(IEventBus var0) {
        var0.addListener(UnboundMapsClient::onRegisterKeys);
        var0.addListener(UnboundMapsClient::onRegisterGuiLayers);
        NeoForge.EVENT_BUS.addListener(UnboundMapsClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(UnboundMapsClient::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(UnboundMapsClient::onLoggingOut);
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent var0) {
        UnboundKeys.register(var0);
    }

    private static void onRegisterGuiLayers(RegisterGuiLayersEvent var0) {
        var0.registerAboveAll(UnboundMapsMod.id("minimap_hud"), MinimapHud.LAYER);
        var0.registerAboveAll(UnboundMapsMod.id("marker_overlay"), WorldMarkerOverlay.LAYER);
    }

    private static void onClientTick(Post var0) {
        UnboundKeys.handleTickInput();
        Minecraft var1 = Minecraft.getInstance();
        ClientLevel var2 = var1.level;
        if (var2 == null) {
            lastDimension = null;
            MapStore.saveDirty();
            WaypointManager.clientTick();
        } else {
            ResourceKey<Level> var3 = var2.dimension();
            if (var3 != lastDimension) {
                lastDimension = var3;
                MapStore.setDimension(var3);
                MapTileCache.clearAll();
                WorldMapCells.clearAll();
                MinimapTexture.invalidateAll();
            }

            MinimapTexture.clientTick(var2);
            updateAutoCave(var2, var1.player);
            if (var2.getGameTime() % 600L == 0L) {
                MapStore.saveDirty();
            }

            WaypointManager.clientTick();
        }
    }

    /** Manual C presses suppress the auto switcher for a while. */
    public static void noteManualCaveToggle() {
        manualCaveUntil = System.currentTimeMillis() + 5000L;
    }

    /**
     * Xaero-style automatic surface/cave switching: cave mode engages when the eyes
     * drop below the solid terrain surface (OCEAN_FLOOR heightmap excludes water and
     * leaves), so swimming ON a river keeps the top view while diving or mining
     * switches to the slice view. A short hysteresis avoids flicker at the boundary.
     * The minimap and the world map each keep their own slice mode and both
     * follow the detected underground state.
     */
    private static void updateAutoCave(ClientLevel var0, LocalPlayer var1) {
        if (var1 == null) {
            return;
        }
        boolean var2;
        try {
            var2 = UnboundConfig.AUTO_CAVE_MODE.get();
        } catch (NullPointerException | IllegalStateException var3) {
            return;
        }

        if (!var2 || System.currentTimeMillis() < manualCaveUntil) {
            return;
        }

        int var4 = Mth.floor(var1.getEyeY());
        int var5 = var0.getHeight(Types.OCEAN_FLOOR, var1.getBlockX(), var1.getBlockZ());
        boolean var6 = var4 < var5;
        if (var6 == appliedUnderground) {
            undergroundStableTicks = 0;
            lastUnderground = var6;
        } else if (var6 != lastUnderground) {
            lastUnderground = var6;
            undergroundStableTicks = 1;
        } else if (++undergroundStableTicks >= 15) {
            undergroundStableTicks = 0;
            appliedUnderground = var6;
            MapTileCache.setMinimapSlice(var6);
            MapTileCache.setWorldMapSlice(var6);
        }
    }

    /**
     * Called when the world map opens: immediately snaps the world map view
     * to the player's current surface/underground state, without waiting
     * for the hysteresis timer.
     */
    public static void syncWorldMapCaveToPlayer() {
        Minecraft var0 = Minecraft.getInstance();
        if (var0.player == null || var0.level == null) {
            return;
        }

        try {
            if (!UnboundConfig.AUTO_CAVE_MODE.get()) {
                return;
            }
        } catch (NullPointerException | IllegalStateException var1) {
            return;
        }

        if (System.currentTimeMillis() < manualCaveUntil) {
            return;
        }

        int var2 = Mth.floor(var0.player.getEyeY());
        int var3 = var0.level.getHeight(Types.OCEAN_FLOOR, var0.player.getBlockX(), var0.player.getBlockZ());
        boolean var4 = var2 < var3;
        appliedUnderground = var4;
        lastUnderground = var4;
        undergroundStableTicks = 0;
        MapTileCache.setWorldMapSlice(var4);
    }

    private static void onRenderLevelStage(RenderLevelStageEvent var0) {
        if (var0.getStage() == Stage.AFTER_TRANSLUCENT_BLOCKS) {
            WorldBeaconRenderer.render(var0);
        }
    }

    private static void onLoggingOut(LoggingOut var0) {
        MapStore.saveDirty();
        WaypointManager.saveNow();
    }
}
