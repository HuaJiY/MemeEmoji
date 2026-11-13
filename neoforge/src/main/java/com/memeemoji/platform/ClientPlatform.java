package com.memeemoji.platform;

import com.memeemoji.MemeEmojiClient;
import com.memeemoji.gui.EmojiSettingsScreen;
import com.memeemoji.net.EmojiNetworkClient;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import java.util.function.Consumer;

public final class ClientPlatform {
    public static void init(IEventBus bus) {
        bus.addListener((FMLClientSetupEvent event) -> event.enqueueWork(MemeEmojiClient::init));
        bus.addListener((RegisterClientReloadListenersEvent event) -> event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> MemeEmojiClient.reloadResources()));
        onTick(EmojiNetworkClient::tick);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> EmojiNetworkClient.reset());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> MemeEmojiClient.disconnect());
        NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent event) -> MemeEmojiClient.close());
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) -> event.getDispatcher().register(
                Commands.literal("memeemoji")
                        .then(Commands.literal("reload").executes(context -> EmojiNetworkClient.reloadLibrary()))
                        .then(Commands.literal("config").executes(context -> {
                            Minecraft client = Minecraft.getInstance();
                            client.tell(() -> client.setScreen(new EmojiSettingsScreen(null)));
                            return 1;
                        }))));
    }

    public static void onTick(Consumer<Minecraft> callback) {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> callback.accept(Minecraft.getInstance()));
    }
}
