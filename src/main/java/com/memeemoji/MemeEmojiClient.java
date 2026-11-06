package com.memeemoji;

import com.memeemoji.chat.EmojiChatRenderer;
import com.memeemoji.media.EmojiLibrary;
import com.memeemoji.media.EmojiTextures;
import com.memeemoji.media.LibraryWatcher;
import com.memeemoji.net.EmojiNetworkClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.client.Minecraft;

public final class MemeEmojiClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EmojiNetworkClient.init();
        EmojiLibrary.reload();
        LibraryWatcher.start(() -> Minecraft.getInstance().execute(EmojiChatRenderer::invalidate));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            EmojiChatRenderer.invalidate();
            EmojiTextures.close();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            LibraryWatcher.close();
            EmojiTextures.close();
        });
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public ResourceLocation getFabricId() {
                return ResourceLocation.fromNamespaceAndPath(MemeEmoji.MOD_ID, "chat_layout");
            }

            @Override
            public void onResourceManagerReload(ResourceManager manager) {
                EmojiChatRenderer.invalidate();
            }
        });
    }
}
