package com.memeemoji.net;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

public final class ClientNetworkPlatform {
    public static void send(CustomPacketPayload packet) { PacketDistributor.sendToServer(packet); }
    public static boolean canSend(CustomPacketPayload.Type<?> type) {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && NetworkRegistry.hasChannel(connection, type.id());
    }
}
