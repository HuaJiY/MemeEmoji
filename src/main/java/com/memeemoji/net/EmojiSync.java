package com.memeemoji.net;

import com.memeemoji.EmojiTile;
import com.memeemoji.MemeEmoji;
import com.memeemoji.TileStore;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 服务端侧的表情下发。客户端只装 mod，表情图片和码位顺序全部以服务端为准，进入世界时整体同步一次。
 */
public final class EmojiSync {
    private static final AtomicLong SESSION = new AtomicLong();

    private static volatile List<EmojiTile> serverTiles;

    private EmojiSync() {
    }

    /** 两端都要注册，客户端需要按这些类型解码。 */
    public static void initCommon() {
        PayloadTypeRegistry.playS2C().register(EmojiSyncStartPayload.TYPE, EmojiSyncStartPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(EmojiSyncChunkPayload.TYPE, EmojiSyncChunkPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(EmojiSyncEndPayload.TYPE, EmojiSyncEndPayload.STREAM_CODEC);
    }

    public static void initServer() {
        ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS.register((player, joined) -> {
            if (player.server.isSingleplayer() || !MemeEmoji.config().sendToClients) {
                return;
            }
            sendTo(player);
        });
    }

    public static List<EmojiTile> serverTiles() {
        List<EmojiTile> tiles = serverTiles;
        if (tiles == null) {
            tiles = rescanServerTiles();
        }
        return tiles;
    }

    public static synchronized List<EmojiTile> rescanServerTiles() {
        int maxSize = MemeEmoji.config().targetMaxSize();
        List<EmojiTile> tiles = TileStore.load(MemeEmoji.emojiDir(), MemeEmoji.cacheDir(),
                maxSize, MemeEmoji.config().maxNameLength);
        serverTiles = tiles;
        return tiles;
    }

    public static void sendTo(ServerPlayer player) {
        send(serverTiles(), payload -> ServerPlayNetworking.send(player, payload));
    }

    public static void sendToAll(MinecraftServer server) {
        List<EmojiTile> tiles = serverTiles();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            send(tiles, payload -> ServerPlayNetworking.send(player, payload));
        }
    }

    private static void send(List<EmojiTile> tiles, Consumer<CustomPacketPayload> sink) {
        long sessionId = SESSION.incrementAndGet();
        sink.accept(new EmojiSyncStartPayload(sessionId, tiles.size()));
        for (int i = 0; i < tiles.size(); i++) {
            EmojiTile tile = tiles.get(i);
            List<byte[]> parts = split(tile.png());
            for (int part = 0; part < parts.size(); part++) {
                sink.accept(new EmojiSyncChunkPayload(sessionId, i, tile.name(), part, parts.size(), parts.get(part),
                        tile.width(), tile.height()));
            }
        }
        sink.accept(new EmojiSyncEndPayload(sessionId));
    }

    private static List<byte[]> split(byte[] data) {
        if (data.length <= EmojiSyncChunkPayload.MAX_PART_BYTES) {
            return List.of(data);
        }
        List<byte[]> parts = new ArrayList<>(data.length / EmojiSyncChunkPayload.MAX_PART_BYTES + 1);
        for (int offset = 0; offset < data.length; offset += EmojiSyncChunkPayload.MAX_PART_BYTES) {
            int length = Math.min(EmojiSyncChunkPayload.MAX_PART_BYTES, data.length - offset);
            byte[] part = new byte[length];
            System.arraycopy(data, offset, part, 0, length);
            parts.add(part);
        }
        return parts;
    }
}
