package com.memeemoji.media;

import com.memeemoji.net.EmojiNetworkClient;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.NoSuchFileException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class EmojiTextures {
    private static final Logger LOGGER = LoggerFactory.getLogger("MemeEmoji/Textures");
    private static final long MAX_CACHE_PIXELS = 32L * 1024 * 1024;
    private static final int MAX_ENTRIES = 256;
    private static final Map<UUID, Entry> CACHE = new LinkedHashMap<>(16, .75f, true);
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "MemeEmoji-Decoder");
        thread.setDaemon(true);
        return thread;
    });
    private static int generation;

    static {
        EmojiLibrary.onAvailable(id -> Minecraft.getInstance().execute(() -> invalidate(id)));
    }

    private EmojiTextures() {}

    public static void render(GuiGraphics graphics, EmojiAsset asset, int x, int y, int width, int height, int alpha) {
        if (width < 1 || height < 1 || alpha <= 0) return;
        Entry entry = CACHE.get(asset.id());
        if (entry == null) {
            entry = new Entry();
            CACHE.put(asset.id(), entry);
            load(asset, entry, generation);
            trim(asset.id());
        }
        if (entry.decoded == null) {
            if (entry.missing) EmojiNetworkClient.request(asset.id());
            placeholder(graphics, x, y, width, height, alpha, entry.failure != null);
            return;
        }
        EmojiDecoder.Decoded decoded = entry.decoded;
        int frame = decoded.frameAt((System.nanoTime() - entry.started) / 1_000_000);
        if (entry.texture == null) {
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, decoded.width(), decoded.height(), false);
            entry.texture = new DynamicTexture(image);
            entry.location = ResourceLocation.fromNamespaceAndPath("memeemoji", "emoji/" + asset.id());
            Minecraft.getInstance().getTextureManager().register(entry.location, entry.texture);
        }
        if (entry.frame != frame) {
            graphics.flush();
            NativeImage image = entry.texture.getPixels();
            int[] pixels = decoded.frames().get(frame).argb();
            for (int yy = 0; yy < decoded.height(); yy++) {
                for (int xx = 0; xx < decoded.width(); xx++) {
                    int argb = pixels[yy * decoded.width() + xx];
                    image.setPixelRGBA(xx, yy, argb & 0xff00ff00 | (argb & 255) << 16 | argb >>> 16 & 255);
                }
            }
            entry.texture.upload();
            // NativeImage.upload resets filtering to nearest-neighbor on every frame.
            entry.texture.setFilter(true, false);
            entry.frame = frame;
        }
        double scale = Math.min((double) width / decoded.width(), (double) height / decoded.height());
        int drawWidth = Math.max(1, (int) Math.round(decoded.width() * scale));
        int drawHeight = Math.max(1, (int) Math.round(decoded.height() * scale));
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.setColor(1, 1, 1, Math.min(255, alpha) / 255f);
        graphics.blit(entry.location, x + (width - drawWidth) / 2, y + (height - drawHeight) / 2,
                drawWidth, drawHeight, 0, 0, decoded.width(), decoded.height(), decoded.width(), decoded.height());
        graphics.setColor(1, 1, 1, 1);
        RenderSystem.disableBlend();
    }

    public static String failure(UUID id) {
        Entry entry = CACHE.get(id);
        return entry == null ? null : entry.failure;
    }

    private static void load(EmojiAsset asset, Entry entry, int expectedGeneration) {
        DECODER.execute(() -> {
            if (entry.discarded) return;
            EmojiDecoder.Decoded decoded = null;
            String failure = null;
            boolean missing = false;
            try {
                decoded = EmojiDecoder.decode(EmojiLibrary.read(asset.id()));
                if (decoded.width() != asset.width() || decoded.height() != asset.height()
                        || (decoded.frames().size() > 1) != asset.animated()) throw new java.io.IOException("表情文件与描述不匹配");
            } catch (NoSuchFileException e) { missing = true; }
            catch (Exception e) { failure = e.getMessage() == null ? "图片解码失败" : e.getMessage(); }
            EmojiDecoder.Decoded result = decoded;
            String error = failure;
            boolean request = missing;
            Minecraft.getInstance().execute(() -> {
                if (generation != expectedGeneration || CACHE.get(asset.id()) != entry) return;
                if (request) {
                    entry.missing = true;
                    EmojiNetworkClient.request(asset.id());
                } else if (error != null) {
                    entry.failure = error;
                    LOGGER.warn("表情 {} 无法显示：{}", asset.name(), error);
                } else {
                    entry.decoded = result;
                    entry.started = System.nanoTime();
                    trim(asset.id());
                }
            });
        });
    }

    private static void trim(UUID keep) {
        long pixels = CACHE.values().stream().filter(e -> e.decoded != null).mapToLong(e -> e.decoded.pixels()).sum();
        Iterator<Map.Entry<UUID, Entry>> iterator = CACHE.entrySet().iterator();
        while (iterator.hasNext() && (CACHE.size() > MAX_ENTRIES || pixels > MAX_CACHE_PIXELS)) {
            Map.Entry<UUID, Entry> item = iterator.next();
            if (item.getKey().equals(keep)) continue;
            Entry entry = item.getValue();
            if (entry.decoded != null) pixels -= entry.decoded.pixels();
            release(entry);
            iterator.remove();
        }
    }

    public static void invalidate(UUID id) {
        Entry entry = CACHE.remove(id);
        if (entry != null) release(entry);
    }

    public static void close() {
        generation++;
        CACHE.values().forEach(EmojiTextures::release);
        CACHE.clear();
    }

    private static void release(Entry entry) {
        entry.discarded = true;
        if (entry.location != null) Minecraft.getInstance().getTextureManager().release(entry.location);
        entry.decoded = null;
        entry.texture = null;
    }

    private static void placeholder(GuiGraphics graphics, int x, int y, int w, int h, int alpha, boolean failed) {
        int a = Math.min(255, alpha) << 24;
        graphics.fill(x, y, x + w, y + h, Math.min(255, alpha) / 6 << 24 | 0x6d7480);
        int color = a | (failed ? 0xe47d86 : 0x8b96a5);
        int cx = x + w / 2, cy = y + h / 2;
        graphics.fill(cx - 3, cy - 1, cx + 3, cy + 1, color);
        if (failed) graphics.fill(cx - 1, cy - 3, cx + 1, cy + 3, color);
    }

    private static final class Entry {
        EmojiDecoder.Decoded decoded;
        DynamicTexture texture;
        ResourceLocation location;
        String failure;
        boolean missing;
        volatile boolean discarded;
        int frame = -1;
        long started;
    }
}
