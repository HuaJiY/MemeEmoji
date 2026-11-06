package com.memeemoji;

import com.memeemoji.chat.EmojiChatRenderer;
import com.memeemoji.media.EmojiLibrary;
import com.memeemoji.media.EmojiTextures;
import com.memeemoji.media.LibraryWatcher;
import com.memeemoji.net.EmojiNetworkClient;
import com.memeemoji.gui.EmojiSettingsScreen;
import com.memeemoji.gui.GlassStyle;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
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
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                ClientCommandManager.literal("memeemoji").then(ClientCommandManager.literal("config").executes(context -> {
                    Minecraft client = Minecraft.getInstance();
                    client.tell(() -> client.setScreen(new EmojiSettingsScreen(null)));
                    return 1;
                }))));
        EmojiLibrary.reload();
        LibraryWatcher.start(() -> Minecraft.getInstance().execute(EmojiChatRenderer::invalidate));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            EmojiChatRenderer.invalidate();
            EmojiTextures.close();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            LibraryWatcher.close();
            EmojiTextures.close();
            GlassStyle.release();
        });
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public ResourceLocation getFabricId() {
                return ResourceLocation.fromNamespaceAndPath(MemeEmoji.MOD_ID, "chat_layout");
            }

            @Override
            public void onResourceManagerReload(ResourceManager manager) {
                GlassStyle.release();
                EmojiChatRenderer.invalidate();
            }
        });
    }
}
