package com.memeemoji.platform;

import com.memeemoji.MemeEmoji;
import com.memeemoji.net.NetworkPlatform;
import net.fabricmc.api.ModInitializer;

public final class FabricEntrypoint implements ModInitializer {
    @Override public void onInitialize() {
        MemeEmoji.init();
        NetworkPlatform.init();
    }
}
