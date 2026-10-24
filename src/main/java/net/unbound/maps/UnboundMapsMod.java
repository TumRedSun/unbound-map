package net.unbound.maps;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod("unboundmaps")
public final class UnboundMapsMod {
    public static final String MOD_ID = "unboundmaps";
    public static final String MOD_NAME = "Unbound Maps";
    public static final String VERSION = "1.1.0";
    public static final Logger LOGGER = LogUtils.getLogger();

    public UnboundMapsMod(IEventBus var1, ModContainer var2) {
        var2.registerConfig(Type.CLIENT, UnboundConfig.SPEC);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            UnboundMapsClient.bootstrap(var1);
        }
    }

    public static ResourceLocation id(String var0) {
        return ResourceLocation.fromNamespaceAndPath("unboundmaps", var0);
    }
}
