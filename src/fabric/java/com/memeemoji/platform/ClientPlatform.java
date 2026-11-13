package com.memeemoji.platform;

import com.memeemoji.MemeEmojiClient;
import com.memeemoji.compat.TalkBubblesCompat;
import com.memeemoji.gui.EmojiSettingsScreen;
import com.memeemoji.net.ClientNetworkPlatform;
import com.memeemoji.net.EmojiNetworkClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import java.util.function.Consumer;

public final class ClientPlatform implements ClientModInitializer {
    @Override public void onInitializeClient() {
        MemeEmojiClient.init();
        ClientNetworkPlatform.init();
        EmojiNetworkClient.onChat(TalkBubblesCompat::received);
        onTick(EmojiNetworkClient::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> EmojiNetworkClient.reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> MemeEmojiClient.disconnect());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> MemeEmojiClient.close());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                ClientCommandManager.literal("memeemoji")
                        .then(ClientCommandManager.literal("reload").executes(context -> EmojiNetworkClient.reloadLibrary()))
                        .then(ClientCommandManager.literal("config").executes(context -> {
                            Minecraft client = Minecraft.getInstance();
                            client.tell(() -> client.setScreen(new EmojiSettingsScreen(null)));
                            return 1;
                        }))));
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override public ResourceLocation getFabricId() {
                return ResourceLocation.fromNamespaceAndPath("memeemoji", "chat_layout");
            }
            @Override public void onResourceManagerReload(ResourceManager manager) { MemeEmojiClient.reloadResources(); }
        });
    }

    public static void onTick(Consumer<Minecraft> callback) { ClientTickEvents.END_CLIENT_TICK.register(callback::accept); }
}
