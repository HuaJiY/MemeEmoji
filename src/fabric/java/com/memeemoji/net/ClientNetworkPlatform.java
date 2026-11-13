package com.memeemoji.net;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import static com.memeemoji.net.EmojiPackets.*;

public final class ClientNetworkPlatform {
    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(Chat.TYPE, (packet, context) -> EmojiNetworkClient.receiveChat(packet));
        ClientPlayNetworking.registerGlobalReceiver(Control.TYPE, (packet, context) -> EmojiNetworkClient.control(packet));
        ClientPlayNetworking.registerGlobalReceiver(Start.TYPE, (packet, context) -> EmojiNetworkClient.start(packet));
        ClientPlayNetworking.registerGlobalReceiver(Chunk.TYPE, (packet, context) -> EmojiNetworkClient.chunk(packet));
    }
    public static void send(CustomPacketPayload packet) { ClientPlayNetworking.send(packet); }
    public static boolean canSend(CustomPacketPayload.Type<?> type) { return ClientPlayNetworking.canSend(type); }
}
