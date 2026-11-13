package com.memeemoji.net;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import static com.memeemoji.net.EmojiPackets.*;

public final class NetworkPlatform {
    public static void init() {
        PayloadTypeRegistry.playC2S().register(Message.TYPE, Message.CODEC);
        PayloadTypeRegistry.playC2S().register(Control.TYPE, Control.CODEC);
        PayloadTypeRegistry.playC2S().register(Start.TYPE, Start.CODEC);
        PayloadTypeRegistry.playC2S().register(Chunk.TYPE, Chunk.CODEC);
        PayloadTypeRegistry.playS2C().register(Chat.TYPE, Chat.CODEC);
        PayloadTypeRegistry.playS2C().register(Control.TYPE, Control.CODEC);
        PayloadTypeRegistry.playS2C().register(Start.TYPE, Start.CODEC);
        PayloadTypeRegistry.playS2C().register(Chunk.TYPE, Chunk.CODEC);
        ServerLifecycleEvents.SERVER_STARTED.register(EmojiNetwork::serverStarted);
        ServerLifecycleEvents.SERVER_STOPPED.register(EmojiNetwork::serverStopped);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> EmojiNetwork.joined(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> EmojiNetwork.disconnected(handler.player));
        ServerTickEvents.END_SERVER_TICK.register(EmojiNetwork::tick);
        ServerPlayNetworking.registerGlobalReceiver(Message.TYPE, (packet, context) -> EmojiNetwork.receiveMessage(context.player(), packet));
        ServerPlayNetworking.registerGlobalReceiver(Control.TYPE, (packet, context) -> EmojiNetwork.receiveControl(context.player(), packet));
        ServerPlayNetworking.registerGlobalReceiver(Start.TYPE, (packet, context) -> EmojiNetwork.receiveStart(context.player(), packet));
        ServerPlayNetworking.registerGlobalReceiver(Chunk.TYPE, (packet, context) -> EmojiNetwork.receiveChunk(context.player(), packet));
    }
    public static void send(ServerPlayer player, CustomPacketPayload packet) { ServerPlayNetworking.send(player, packet); }
    public static boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) { return ServerPlayNetworking.canSend(player, type); }
}
