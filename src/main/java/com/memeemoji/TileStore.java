package com.memeemoji;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 扫描一个表情文件夹，返回裁好的表情图。
 *
 * <p>源图往往是几 MB 的大图，每次启动都重解码要十几秒，所以处理结果按
 * (名字 + 相对路径 + 大小 + 修改时间) 做指纹缓存到磁盘，指纹没变就直接读缓存。
 */
public final class TileStore {
    private static final int CACHE_VERSION = 2; // 从 1 升到 2：独立纹理格式
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private TileStore() {
    }

    public static List<EmojiTile> load(Path emojiDir, Path cacheDir, int maxSize, int maxNameLength) {
        List<Source> sources;
        try {
            Files.createDirectories(emojiDir);
            sources = collect(emojiDir);
        } catch (IOException e) {
            MemeEmoji.LOGGER.warn("扫描 {} 失败", emojiDir, e);
            return List.of();
        }

        List<Source> usable = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int rejected = 0;
        for (Source source : sources) {
            if (usable.size() >= MemeEmoji.MAX_EMOJI) {
                break;
            }
            String name = ImageTiles.baseName(source.file().getFileName().toString());
            if (!EmojiNames.isUsable(name, maxNameLength) || !seen.add(EmojiNames.key(name))) {
                rejected++;
                continue;
            }
            usable.add(source);
        }
        int dropped = sources.size() - usable.size() - rejected;
        if (dropped > 0) {
            MemeEmoji.LOGGER.warn("表情数量超过上限 {}，多出的 {} 张已忽略", MemeEmoji.MAX_EMOJI, dropped);
        }
        if (rejected > 0) {
            MemeEmoji.LOGGER.info("跳过了 {} 个名字重复或不合法（含空格、冒号、超长）的图片", rejected);
        }

        List<EmojiTile> cached = readCache(cacheDir, usable, maxSize);
        if (cached != null) {
            MemeEmoji.LOGGER.info("从缓存载入 {} 个表情", cached.size());
            return cached;
        }

        List<EmojiTile> tiles = new ArrayList<>(usable.size());
        List<CacheEntry> entries = new ArrayList<>(usable.size());
        int failed = 0;
        for (Source source : usable) {
            try {
                java.awt.image.BufferedImage img = ImageTiles.read(source.file());
                ImageTiles.FitResult result = ImageTiles.fitToMax(img, maxSize);
                byte[] png = ImageTiles.toPng(result.image());
                tiles.add(new EmojiTile(source.name(), png, result.width(), result.height()));
                entries.add(new CacheEntry(source.name(), source.rel(), source.size(), source.mtime(),
                        result.width(), result.height()));
            } catch (Throwable t) {
                failed++;
                MemeEmoji.LOGGER.warn("解码 {} 失败，已跳过：{}", source.rel(), t.toString());
            }
        }
        if (failed > 0) {
            MemeEmoji.LOGGER.warn("共 {} 个表情图片解码失败", failed);
        }
        writeCache(cacheDir, maxSize, entries, tiles);
        return tiles;
    }

    private static List<Source> collect(Path emojiDir) throws IOException {
        List<Source> sources = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(emojiDir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> ImageTiles.isImageFile(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> emojiDir.relativize(path).toString()))
                    .toList();
            for (Path file : files) {
                String rel = emojiDir.relativize(file).toString().replace('\\', '/');
                sources.add(new Source(file, rel, ImageTiles.baseName(file.getFileName().toString()),
                        Files.size(file), Files.getLastModifiedTime(file).toMillis()));
            }
        }
        return sources;
    }

    private static List<EmojiTile> readCache(Path cacheDir, List<Source> sources, int maxSize) {
        Path indexFile = cacheDir.resolve("index.json");
        if (!Files.isRegularFile(indexFile)) {
            return null;
        }
        CacheIndex index;
        try {
            index = GSON.fromJson(Files.readString(indexFile, StandardCharsets.UTF_8), CacheIndex.class);
        } catch (IOException | JsonSyntaxException e) {
            return null;
        }
        if (index == null || index.version != CACHE_VERSION || index.maxSize != maxSize || index.entries == null
                || index.entries.size() != sources.size()) {
            return null;
        }
        List<EmojiTile> tiles = new ArrayList<>(sources.size());
        for (int i = 0; i < sources.size(); i++) {
            CacheEntry entry = index.entries.get(i);
            Source source = sources.get(i);
            if (!entry.name.equals(source.name()) || !entry.path.equals(source.rel())
                    || entry.size != source.size() || entry.mtime != source.mtime()) {
                return null;
            }
            try {
                tiles.add(new EmojiTile(entry.name, Files.readAllBytes(cacheDir.resolve("tiles").resolve(i + ".png")),
                        entry.width, entry.height));
            } catch (IOException e) {
                return null;
            }
        }
        return tiles;
    }

    private static void writeCache(Path cacheDir, int maxSize, List<CacheEntry> entries, List<EmojiTile> tiles) {
        try {
            Path tilesDir = cacheDir.resolve("tiles");
            deleteTree(tilesDir);
            Files.createDirectories(tilesDir);
            for (int i = 0; i < tiles.size(); i++) {
                Files.write(tilesDir.resolve(i + ".png"), tiles.get(i).png());
            }
            CacheIndex index = new CacheIndex();
            index.version = CACHE_VERSION;
            index.maxSize = maxSize;
            index.entries = entries;
            Files.createDirectories(cacheDir);
            Files.writeString(cacheDir.resolve("index.json"), GSON.toJson(index), StandardCharsets.UTF_8);
        } catch (IOException e) {
            MemeEmoji.LOGGER.warn("写入表情缓存失败，下次启动会重新解码", e);
        }
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record Source(Path file, String rel, String name, long size, long mtime) {
    }

    @SuppressWarnings("unused")
    static final class CacheIndex {
        int version;
        int maxSize;
        List<CacheEntry> entries;
    }

    @SuppressWarnings("unused")
    static final class CacheEntry {
        String name;
        String path;
        long size;
        long mtime;
        int width;
        int height;

        CacheEntry() {
        }

        CacheEntry(String name, String path, long size, long mtime, int width, int height) {
            this.name = name;
            this.path = path;
            this.size = size;
            this.mtime = mtime;
            this.width = width;
            this.height = height;
        }
    }
}
