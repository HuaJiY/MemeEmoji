package com.memeemoji.platform;

import com.memeemoji.MemeEmoji;
import com.memeemoji.net.NetworkPlatform;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(MemeEmoji.MOD_ID)
public final class NeoForgeEntrypoint {
    public NeoForgeEntrypoint(IEventBus bus) {
        MemeEmoji.init();
        NetworkPlatform.init(bus);
        if (FMLEnvironment.dist == Dist.CLIENT) ClientPlatform.init(bus);
    }
}
