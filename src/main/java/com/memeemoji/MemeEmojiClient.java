package com.memeemoji;

import com.memeemoji.net.EmojiSyncChunkPayload;
import com.memeemoji.net.EmojiSyncEndPayload;
import com.memeemoji.net.EmojiSyncStartPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端负责两件事：启动时把本地表情文件夹变成图集；进服务器后改由服务端下发的内容重建图集。
 * 两者写的是同一个资源包目录，谁后写谁生效。
 */
public final class MemeEmojiClient implements ClientModInitializer {
    private static final Map<Integer, ChunkAssembly> ASSEMBLIES = new HashMap<>();

    private static boolean packReady;
    private static long sessionId;
    private static boolean sessionActive;
    private static int expectedTotal;

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(EmojiSyncStartPayload.TYPE,
                (payload, context) -> context.client().execute(() -> onStart(payload)));
        ClientPlayNetworking.registerGlobalReceiver(EmojiSyncChunkPayload.TYPE,
                (payload, context) -> context.client().execute(() -> onChunk(payload)));
        ClientPlayNetworking.registerGlobalReceiver(EmojiSyncEndPayload.TYPE,
                (payload, context) -> context.client().execute(() -> onEnd(payload)));
        ensurePackOnDisk();
    }

    /**
     * 保证 generated 目录里有一份可用的图集。只有第一次调用会真正扫描表情文件夹，
     * 之后要么沿用本地结果，要么沿用服务端下发的图集，避免重进服务器时把服务端表情换回本地的。
     *
     * @return 资源包目录是否可用；不可用时调用方不应把它挂进资源包列表
     */
    public static synchronized boolean ensurePackOnDisk() {
        if (!MemeEmoji.config().enabled) {
            return false;
        }
        if (packReady) {
            return true;
        }
        WebpSupport.ensureRegistered();
        List<EmojiTile> tiles = TileStore.load(MemeEmoji.emojiDir(), MemeEmoji.cacheDir(),
                MemeEmoji.cell(), MemeEmoji.config().maxNameLength);
        if (!writeAndApply(tiles)) {
            return false;
        }
        packReady = true;
        MemeEmoji.LOGGER.info("MemeEmoji 已生成 {} 个表情的图集", tiles.size());
        return true;
    }

    private static boolean writeAndApply(List<EmojiTile> tiles) {
        try {
            EmojiPack.write(MemeEmoji.generatedPackDir(), tiles);
        } catch (IOException | RuntimeException e) {
            MemeEmoji.LOGGER.warn("写入表情图集失败，本次不加载表情", e);
            return false;
        }
        EmojiRegistry.INSTANCE.apply(tiles, MemeEmoji.config().enabled);
        return true;
    }

    private static void onStart(EmojiSyncStartPayload payload) {
        sessionId = payload.sessionId();
        sessionActive = true;
        expectedTotal = payload.total();
        ASSEMBLIES.clear();
    }

    private static void onChunk(EmojiSyncChunkPayload payload) {
        if (!sessionActive || payload.sessionId() != sessionId) {
            return;
        }
        ASSEMBLIES.computeIfAbsent(payload.index(), index -> new ChunkAssembly(payload.name(), payload.partTotal()))
                .add(payload);
    }

    private static void onEnd(EmojiSyncEndPayload payload) {
        if (!sessionActive || payload.sessionId() != sessionId) {
            resetSession();
            return;
        }
        if (ASSEMBLIES.size() != expectedTotal) {
            MemeEmoji.LOGGER.warn("表情同步不完整：应有 {} 张，实收 {} 张，已放弃本次同步", expectedTotal, ASSEMBLIES.size());
            resetSession();
            return;
        }
        List<EmojiTile> tiles = new ArrayList<>(expectedTotal);
        for (int index = 0; index < expectedTotal; index++) {
            ChunkAssembly assembly = ASSEMBLIES.get(index);
            if (assembly == null || !assembly.complete()) {
                MemeEmoji.LOGGER.warn("表情同步缺少第 {} 张，已放弃本次同步", index);
                resetSession();
                return;
            }
            tiles.add(new EmojiTile(assembly.name, assembly.assemble()));
        }
        resetSession();
        if (!writeAndApply(tiles)) {
            return;
        }
        packReady = true;
        MemeEmoji.LOGGER.info("已应用服务端下发的 {} 个表情", tiles.size());
        Minecraft.getInstance().reloadResourcePacks();
    }

    private static void resetSession() {
        sessionActive = false;
        expectedTotal = 0;
        ASSEMBLIES.clear();
    }

    private static final class ChunkAssembly {
        private final String name;
        private final byte[][] parts;
        private int received;

        ChunkAssembly(String name, int partTotal) {
            this.name = name;
            this.parts = partTotal > 0 ? new byte[partTotal][] : new byte[0][];
        }

        void add(EmojiSyncChunkPayload payload) {
            int part = payload.partIndex();
            if (part < 0 || part >= parts.length || parts[part] != null) {
                return;
            }
            parts[part] = payload.png();
            received++;
        }

        boolean complete() {
            return parts.length > 0 && received == parts.length;
        }

        byte[] assemble() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (byte[] part : parts) {
                out.writeBytes(part);
            }
            return out.toByteArray();
        }
    }
}