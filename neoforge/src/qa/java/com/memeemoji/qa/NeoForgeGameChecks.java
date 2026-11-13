package com.memeemoji.qa;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@Mod(value = "memeemoji_qa", dist = Dist.CLIENT)
public final class NeoForgeGameChecks {
    public NeoForgeGameChecks(IEventBus bus) {
        bus.addListener((FMLClientSetupEvent event) -> event.enqueueWork(() -> new EmojiGameChecks().start()));
    }
}
