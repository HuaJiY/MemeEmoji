package com.memeemoji;

import com.memeemoji.glyph.MemeEmojiGlyph;
import com.memeemoji.glyph.MemeEmojiClientAccess;
import com.memeemoji.net.EmojiSyncChunkPayload;
import com.memeemoji.net.EmojiSyncEndPayload;
import com.memeemoji.net.EmojiSyncStartPayload;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端负责两件事：启动时把本地表情文件夹变成独立纹理资源包；进服务器后改由服务端下发的内容重建。
 * 两者写的是同一个资源包目录，谁后写谁生效。
 */
public final class MemeEmojiClient implements ClientModInitializer {
    private static final Map<Integer, ChunkAssembly> ASSEMBLIES = new HashMap<>();

    private static boolean packReady;
    private static long sessionId;
    private static boolean sessionActive;
    private static int expectedTotal;

    /** 每个表情对应的 DynamicTexture 引用（防止 GC） */
    private static final List<DynamicTexture> OWNED_TEXTURES = new ArrayList<>();

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
     * 保证 generated 目录里有一份可用的资源包。只有第一次调用会真正扫描表情文件夹，
     * 之后要么沿用本地结果，要么沿用服务端下发的资源包，避免重进服务器时把服务端表情换回本地的。
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

        int maxSize = MemeEmoji.config().targetMaxSize();
        MemeEmoji.setComputedMaxSize(maxSize);

        List<EmojiTile> tiles = TileStore.load(MemeEmoji.emojiDir(), MemeEmoji.cacheDir(),
                maxSize, MemeEmoji.config().maxNameLength);

        // 计算最大表情高度
        int maxH = 9;
        for (EmojiTile tile : tiles) {
            if (tile.height() > maxH) maxH = tile.height();
        }
        MemeEmoji.setMaxEmojiHeight(maxH);

        if (!writeAndApply(tiles)) {
            return false;
        }
        packReady = true;
        MemeEmoji.LOGGER.info("MemeEmoji 已生成 {} 个表情，maxSize={}, maxHeight={}", tiles.size(), maxSize, maxH);
        return true;
    }

    // ---- 服务端同步 ----

    private static boolean writeAndApply(List<EmojiTile> tiles) {
        try {
            EmojiPack.write(MemeEmoji.generatedPackDir(), tiles);
        } catch (IOException | RuntimeException e) {
            MemeEmoji.LOGGER.warn("写入表情资源包失败，本次不加载表情", e);
            return false;
        }
        EmojiRegistry.INSTANCE.apply(tiles, MemeEmoji.config().enabled);
        registerCustomTextures(tiles);
        return true;
    }

    /**
     * 为每个表情创建 DynamicTexture 和自定义 BakedGlyph，
     * 注册到 MemeEmojiClientAccess 供 FontSetMixin 替换渲染。
     */
    private static void registerCustomTextures(List<EmojiTile> tiles) {
        // 清理旧的纹理和 glyph
        for (DynamicTexture tex : OWNED_TEXTURES) {
            tex.close();
        }
        OWNED_TEXTURES.clear();
        MemeEmojiClientAccess.clear();

        Minecraft mc = Minecraft.getInstance();
        for (int i = 0; i < tiles.size(); i++) {
            EmojiTile tile = tiles.get(i);
            int codepoint = MemeEmoji.PUA_BASE + i;

            try {
                NativeImage nativeImage = NativeImage.read(new ByteArrayInputStream(tile.png()));
                DynamicTexture dynamicTexture = new DynamicTexture(nativeImage);
                ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                        MemeEmoji.MOD_ID, "emoji_texture/" + tile.name()
                );
                mc.getTextureManager().register(location, dynamicTexture);
                OWNED_TEXTURES.add(dynamicTexture);

                MemeEmojiGlyph glyph = new MemeEmojiGlyph(location, tile.width(), tile.height());
                MemeEmojiClientAccess.put(codepoint, glyph);
            } catch (IOException e) {
                MemeEmoji.LOGGER.warn("注册表情纹理失败: {}", tile.name(), e);
            }
        }
        MemeEmoji.LOGGER.info("已注册 {} 个自定义纹理 glyph", MemeEmojiClientAccess.size());
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
        ASSEMBLIES.computeIfAbsent(payload.index(), index -> new ChunkAssembly(payload.name(), payload.partTotal(),
                        payload.width(), payload.height()))
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
            tiles.add(new EmojiTile(assembly.name, assembly.assemble(), assembly.width, assembly.height));
        }
        resetSession();

        // 计算最大表情高度
        int maxH = 9;
        for (EmojiTile tile : tiles) {
            if (tile.height() > maxH) maxH = tile.height();
        }
        MemeEmoji.setMaxEmojiHeight(maxH);

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
        private final int width;
        private final int height;
        private int received;

        ChunkAssembly(String name, int partTotal, int width, int height) {
            this.name = name;
            this.parts = partTotal > 0 ? new byte[partTotal][] : new byte[0][];
            this.width = width;
            this.height = height;
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
