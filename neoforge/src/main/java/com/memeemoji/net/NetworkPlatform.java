package com.memeemoji.net;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import static com.memeemoji.net.EmojiPackets.*;

public final class NetworkPlatform {
    public static void init(IEventBus bus) {
        bus.addListener(NetworkPlatform::register);
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> EmojiNetwork.serverStarted(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> EmojiNetwork.serverStopped(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> EmojiNetwork.tick(event.getServer()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> EmojiNetwork.joined((ServerPlayer) event.getEntity()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> EmojiNetwork.disconnected((ServerPlayer) event.getEntity()));
    }

    private static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional().executesOn(HandlerThread.MAIN);
        registrar.playToServer(Message.TYPE, Message.CODEC, (packet, context) -> EmojiNetwork.receiveMessage((ServerPlayer) context.player(), packet));
        registrar.playToClient(Chat.TYPE, Chat.CODEC, (packet, context) -> EmojiNetworkClient.receiveChat(packet));
        registrar.playBidirectional(Control.TYPE, Control.CODEC, (packet, context) -> {
            if (context.flow().isServerbound()) EmojiNetwork.receiveControl((ServerPlayer) context.player(), packet);
            else EmojiNetworkClient.control(packet);
        });
        registrar.playBidirectional(Start.TYPE, Start.CODEC, (packet, context) -> {
            if (context.flow().isServerbound()) EmojiNetwork.receiveStart((ServerPlayer) context.player(), packet);
            else EmojiNetworkClient.start(packet);
        });
        registrar.playBidirectional(Chunk.TYPE, Chunk.CODEC, (packet, context) -> {
            if (context.flow().isServerbound()) EmojiNetwork.receiveChunk((ServerPlayer) context.player(), packet);
            else EmojiNetworkClient.chunk(packet);
        });
    }

    public static void send(ServerPlayer player, CustomPacketPayload packet) { PacketDistributor.sendToPlayer(player, packet); }
    public static boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) { return NetworkRegistry.hasChannel(player.connection, type.id()); }
}
