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
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * 客户端负责三件事：启动时把本地表情文件夹变成独立纹理资源包；进服务器后改由服务端下发的内容重建；
 * 运行中通过 WatchService 监听表情文件夹和配置文件的变更，自动热加载。
 */
public final class MemeEmojiClient implements ClientModInitializer {
    private static final Map<Integer, ChunkAssembly> ASSEMBLIES = new HashMap<>();

    private static boolean packReady;
    private static long sessionId;
    private static boolean sessionActive;
    private static int expectedTotal;

    /** 每个表情对应的 DynamicTexture 引用（防止 GC） */
    private static final List<DynamicTexture> OWNED_TEXTURES = new ArrayList<>();
    /** 待注册纹理的表情列表，在 Minecraft 完全初始化后延迟注册 */
    private static List<EmojiTile> pendingTiles;

    /** 热加载去抖，最近一次文件变更时间戳（毫秒） */
    private static final AtomicLong LAST_CHANGE = new AtomicLong(0);
    private static volatile boolean watcherStarted;

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(EmojiSyncStartPayload.TYPE,
                (payload, context) -> context.client().execute(() -> onStart(payload)));
        ClientPlayNetworking.registerGlobalReceiver(EmojiSyncChunkPayload.TYPE,
                (payload, context) -> context.client().execute(() -> onChunk(payload)));
        ClientPlayNetworking.registerGlobalReceiver(EmojiSyncEndPayload.TYPE,
                (payload, context) -> context.client().execute(() -> onEnd(payload)));
        Minecraft.getInstance().execute(() -> {
            ensurePackOnDisk();
            registerPendingTextures();
            startWatcher();
        });
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
        return generatePack();
    }

    /**
     * 强制重新生成资源包，用于热加载。
     */
    public static synchronized void reload() {
        MemeEmoji.LOGGER.info("MemeEmoji 热加载……");
        packReady = false;
        // 清空 tile cache 确保重新读取
        TileStore.clearCache();
        // 重新读取配置
        MemeEmoji.invalidateConfig();
        generatePack();
        registerPendingTextures();
        Minecraft.getInstance().reloadResourcePacks();
        MemeEmoji.LOGGER.info("MemeEmoji 热加载完成");
    }

    private static boolean generatePack() {
        if (!MemeEmoji.config().enabled) {
            packReady = false;
            return false;
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
        // 纹理注册延迟到 Minecraft 初始化完成后执行
        pendingTiles = tiles;
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

    private static void registerPendingTextures() {
        if (pendingTiles != null && !pendingTiles.isEmpty()) {
            registerCustomTextures(pendingTiles);
            pendingTiles = null;
        }
    }

    // ---- WatchService 热加载 ----

    private static void startWatcher() {
        if (watcherStarted) return;
        watcherStarted = true;
        Thread watcher = Thread.ofPlatform()
                .name("MemeEmoji-Watcher")
                .daemon(true)
                .start(() -> {
                    try (WatchService ws = FileSystems.getDefault().newWatchService()) {
                        // 注册 emoji 文件夹
                        Path emojiDir = MemeEmoji.emojiDir();
                        Files.createDirectories(emojiDir);
                        emojiDir.register(ws, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);

                        // 注册 config 文件夹
                        Path configDir = MemeEmoji.configDir();
                        Files.createDirectories(configDir);
                        configDir.register(ws, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);

                        while (!Thread.interrupted()) {
                            WatchKey key;
                            try {
                                key = ws.take();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                            boolean relevant = false;
                            for (WatchEvent<?> event : key.pollEvents()) {
                                Path changed = (Path) event.context();
                                String name = changed.toString().toLowerCase(java.util.Locale.ROOT);
                                // emoji 文件夹里的图片文件
                                if (event.kind() == ENTRY_CREATE || event.kind() == ENTRY_MODIFY || event.kind() == ENTRY_DELETE) {
                                    if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                                            || name.endsWith(".gif") || name.endsWith(".webp")
                                            || name.equals("config.json")) {
                                        relevant = true;
                                    }
                                }
                            }
                            key.reset();

                            if (relevant) {
                                LAST_CHANGE.set(System.currentTimeMillis());
                            }
                        }
                    } catch (IOException e) {
                        MemeEmoji.LOGGER.warn("MemeEmoji 文件监听启动失败，热加载不可用", e);
                    }
                });

        // 去抖线程：500ms 无新变更后触发 reload
        Thread debouncer = Thread.ofPlatform()
                .name("MemeEmoji-Debouncer")
                .daemon(true)
                .start(() -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        long last = LAST_CHANGE.get();
                        if (last > 0 && System.currentTimeMillis() - last > 500) {
                            LAST_CHANGE.set(0);
                            // 在主线程执行 reload
                            try {
                                Minecraft.getInstance().execute(MemeEmojiClient::reload);
                            } catch (Exception e) {
                                MemeEmoji.LOGGER.warn("MemeEmoji 热加载执行异常", e);
                            }
                        }
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                });
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
        registerCustomTextures(tiles);
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

