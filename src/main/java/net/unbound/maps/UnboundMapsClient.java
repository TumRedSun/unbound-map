package net.unbound.maps;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut;
import net.neoforged.neoforge.client.event.ClientTickEvent.Post;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage;
import net.neoforged.neoforge.common.NeoForge;
import net.unbound.maps.keybinds.UnboundKeys;
import net.unbound.maps.mapdata.MapTileCache;
import net.unbound.maps.mapdata.MinimapTexture;
import net.unbound.maps.mapdata.WorldMapCells;
import net.unbound.maps.render.MinimapHud;
import net.unbound.maps.render.WorldBeaconRenderer;
import net.unbound.maps.render.WorldMarkerOverlay;
import net.unbound.maps.waypoints.WaypointManager;

public final class UnboundMapsClient {
    private static ResourceKey<Level> lastDimension;

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
            WaypointManager.clientTick();
        } else {
            ResourceKey<Level> var3 = var2.dimension();
            if (var3 != lastDimension) {
                lastDimension = var3;
                MapTileCache.clearAll();
                WorldMapCells.clearAll();
                MinimapTexture.invalidateAll();
            }

            MinimapTexture.clientTick(var2);
            WaypointManager.clientTick();
        }
    }

    private static void onRenderLevelStage(RenderLevelStageEvent var0) {
        if (var0.getStage() == Stage.AFTER_TRANSLUCENT_BLOCKS) {
            WorldBeaconRenderer.render(var0);
        }
    }

    private static void onLoggingOut(LoggingOut var0) {
        WaypointManager.saveNow();
    }
}
