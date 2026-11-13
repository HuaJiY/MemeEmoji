package com.memeemoji.media;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.memeemoji.MemeEmoji;
import com.memeemoji.EmojiNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class EmojiLibrary {
    private static final Logger LOGGER = LoggerFactory.getLogger("MemeEmoji/Library");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<String> EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp");
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "MemeEmoji-Library");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<UUID, EmojiAsset> REMOTE = new ConcurrentHashMap<>();
    private static volatile Snapshot snapshot = new Snapshot(List.of(), Map.of(), Map.of(), Map.of(), Map.of());
    private static volatile Map<String, String> problems = Map.of();
    private static volatile Consumer<UUID> availableListener = id -> {};
    private static boolean receivedLoaded;

    private EmojiLibrary() {}

    public static Path directory() { return MemeEmoji.configDir(); }
    private static Path personal() { return directory().resolve("emoji"); }
    private static Path received() { return directory().resolve("received"); }
    public static List<EmojiAsset> local() { return snapshot.local; }
    public static Map<String, String> problems() { return problems; }
    public static void onAvailable(Consumer<UUID> listener) { availableListener = listener; }

    public static EmojiAsset find(UUID id) {
        Source source = snapshot.ids.get(id);
        return source == null ? REMOTE.get(id) : source.asset;
    }

    public static EmojiAsset findName(String name) { return snapshot.names.get(name.toLowerCase(Locale.ROOT)); }

    public static EmojiAsset personalCopy(UUID id) {
        Snapshot current = snapshot;
        Source source = current.ids.get(current.collected.getOrDefault(id, id));
        return source == null ? null : source.displayAsset();
    }

    public static CompletableFuture<EmojiAsset> collect(UUID id) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                EmojiAsset existing = personalCopy(id);
                if (existing != null) return existing;
                EmojiAsset asset = find(id);
                if (asset == null) throw new IOException("图片信息已失效");
                byte[] bytes = read(id);
                EmojiDecoder.inspect(bytes);
                String extension = EmojiDecoder.extension(bytes);
                if (!EXTENSIONS.contains(extension)) throw new IOException("不支持的图片格式");
                String name = EmojiNames.defaultName(asset.name(), MemeEmoji.config().maxNameLength);
                Set<String> reserved = new HashSet<>(snapshot.names.keySet());
                Path target = availableName(name + "." + extension, reserved);
                writeAtomic(target, bytes);
                scan();
                EmojiAsset saved = snapshot.paths.get(relative(target)).asset;
                Map<UUID, UUID> collected = new LinkedHashMap<>(snapshot.collected);
                collected.put(id, saved.id());
                snapshot = new Snapshot(snapshot.local, snapshot.ids, snapshot.names, snapshot.paths, Map.copyOf(collected));
                writeIndex();
                return saved;
            } catch (IOException e) { throw new CompletionException(e); }
        }, IO);
    }

    public static void initialize() { reload(); }

    public record DeleteResult(Set<UUID> deleted, Map<String, String> failures) {}

    public static CompletableFuture<EmojiAsset> rename(UUID id, String name) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                validateName(name);
                Snapshot current = snapshot;
                Source source = current.ids.get(id);
                if (source == null) throw new IOException("表情已不在个人库中");
                read(id);
                EmojiAsset duplicate = current.names.get(EmojiNames.key(name));
                if (duplicate != null && !duplicate.id().equals(id)) throw new IOException("名称已被其他表情使用");
                if (source.displayAsset().name().equals(name)) return source.displayAsset();
                Map<String, Source> paths = new LinkedHashMap<>(current.paths);
                Source renamed = new Source(source.path, source.size, source.modified, source.asset, name);
                paths.put(source.path, renamed);
                // Shared metadata stays immutable for a UUID; names belong only to the personal index.
                writeAtomic(directory().resolve("library-index.json"),
                        GSON.toJson(new Index(1, new ArrayList<>(paths.values()), current.collected)).getBytes(StandardCharsets.UTF_8));
                publish(paths, current.collected);
                return renamed.displayAsset();
            } catch (IOException e) { throw new CompletionException(e); }
        }, IO);
    }

    public static CompletableFuture<DeleteResult> delete(Set<UUID> ids) {
        Set<UUID> requested = Set.copyOf(ids);
        return CompletableFuture.supplyAsync(() -> {
            Set<UUID> deleted = new HashSet<>();
            Map<String, String> failures = new LinkedHashMap<>();
            for (UUID id : requested) {
                Source source = snapshot.ids.get(id);
                if (source == null) continue;
                try {
                    // Chat references outlive personal-library files, including while offline.
                    byte[] bytes = read(id);
                    Files.createDirectories(received());
                    writeAtomic(received().resolve(id.toString()), bytes);
                    writeAtomic(received().resolve(id + ".json"), GSON.toJson(source.asset).getBytes(StandardCharsets.UTF_8));
                    acceptMetadata(source.asset);
                    Files.delete(personal().resolve(source.path));
                    deleted.add(id);
                } catch (IOException e) {
                    failures.put(source.displayAsset().name(), e.getMessage());
                }
            }
            try { scan(); }
            catch (IOException e) { failures.put("刷新表情库", e.getMessage()); }
            return new DeleteResult(Set.copyOf(deleted), Map.copyOf(failures));
        }, IO);
    }

    public static CompletableFuture<List<EmojiAsset>> reload() {
        return CompletableFuture.supplyAsync(() -> {
            try { return scan(); }
            catch (IOException e) { throw new CompletionException(e); }
        }, IO);
    }

    public static CompletableFuture<List<EmojiAsset>> importFiles(List<Path> files) {
        List<Path> inputs = List.copyOf(files);
        return CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(personal());
                List<Path> copied = new ArrayList<>();
                List<String> rejected = new ArrayList<>();
                Set<String> reserved = new HashSet<>(snapshot.names.keySet());
                try (var stream = Files.walk(personal())) {
                    stream.filter(Files::isRegularFile).filter(EmojiLibrary::isImage)
                            .forEach(path -> reserved.add(baseName(path.getFileName().toString()).toLowerCase(Locale.ROOT)));
                }
                for (Path input : inputs) {
                    try {
                        if (!isImage(input)) throw new IOException("不支持的图片格式");
                        String filename = input.getFileName().toString();
                        String name = EmojiNames.defaultName(baseName(filename), MemeEmoji.config().maxNameLength);
                        byte[] bytes = readLimited(input);
                        EmojiDecoder.inspect(bytes);
                        Path target = availableName(name + filename.substring(filename.lastIndexOf('.')), reserved);
                        Files.write(target, bytes);
                        reserved.add(baseName(target.getFileName().toString()).toLowerCase(Locale.ROOT));
                        copied.add(target);
                    } catch (IOException | IllegalArgumentException e) {
                        rejected.add(input.getFileName() + "：" + e.getMessage());
                    }
                }
                scan();
                if (!rejected.isEmpty()) throw new IOException("已导入 " + copied.size() + " 张；" + String.join("；", rejected));
                return copied.stream().map(path -> snapshot.paths.get(relative(path))).filter(java.util.Objects::nonNull)
                        .map(Source::asset).toList();
            } catch (IOException e) { throw new CompletionException(e); }
        }, IO);
    }

    public static Path file(UUID id) {
        Source source = snapshot.ids.get(id);
        return source == null ? received().resolve(id.toString()) : personal().resolve(source.path);
    }

    public static boolean hasFile(UUID id) { return Files.isRegularFile(file(id)); }

    public static byte[] read(UUID id) throws IOException {
        Source source = snapshot.ids.get(id);
        Path path = file(id);
        if (source != null && (Files.size(path) != source.size || Files.getLastModifiedTime(path).toMillis() != source.modified))
            throw new IOException("表情文件已更改，请刷新表情库");
        return readLimited(path);
    }

    public static void acceptMetadata(EmojiAsset asset) {
        REMOTE.putIfAbsent(asset.id(), asset);
    }

    public static CompletableFuture<Void> accept(EmojiAsset asset, byte[] input) {
        if (input.length > EmojiAsset.MAX_FILE_BYTES) return CompletableFuture.failedFuture(new IOException("图片须在 8 MB 以内"));
        byte[] bytes = input.clone();
        return CompletableFuture.runAsync(() -> {
            try {
                EmojiAsset known = find(asset.id());
                if (known != null && !known.equals(asset)) throw new IOException("表情 UUID 已对应其他描述");
                EmojiDecoder.Info info = EmojiDecoder.inspect(bytes);
                if (info.width() != asset.width() || info.height() != asset.height() || info.animated() != asset.animated())
                    throw new IOException("表情文件与描述不匹配");
                Files.createDirectories(received());
                Path target = received().resolve(asset.id().toString());
                if (!snapshot.ids.containsKey(asset.id()) && !Files.isRegularFile(target)) writeAtomic(target, bytes);
                writeAtomic(received().resolve(asset.id() + ".json"), GSON.toJson(asset).getBytes(StandardCharsets.UTF_8));
                acceptMetadata(asset);
                availableListener.accept(asset.id());
            } catch (IOException e) { throw new CompletionException(e); }
        }, IO);
    }

    private static List<EmojiAsset> scan() throws IOException {
        Files.createDirectories(personal());
        Files.createDirectories(received());
        Snapshot previous = snapshot;
        Map<String, Source> old = new HashMap<>(previous.paths);
        Map<UUID, UUID> collected = new LinkedHashMap<>(previous.collected);
        Path indexPath = directory().resolve("library-index.json");
        if (old.isEmpty() && Files.isRegularFile(indexPath)) {
            try {
                String json = Files.readString(indexPath, StandardCharsets.UTF_8);
                Index index = GSON.fromJson(json, Index.class);
                if (index != null && index.version == 1 && index.sources != null) {
                    for (Source source : index.sources) if (source != null && source.path != null && source.asset != null) old.put(source.path, source);
                    if (index.collected != null) collected.putAll(index.collected);
                }
            } catch (RuntimeException | IOException e) { LOGGER.warn("个人表情索引无法读取，将重新扫描：{}", e.getMessage()); }
        }
        Map<UUID, Source> ids = new LinkedHashMap<>();
        Map<String, Source> paths = new LinkedHashMap<>();
        Map<String, EmojiAsset> names = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        try (var walk = Files.walk(personal())) {
            for (Path path : walk.filter(Files::isRegularFile).filter(EmojiLibrary::isImage)
                    .sorted(Comparator.comparing(EmojiLibrary::relative)).toList()) {
                String relative = relative(path);
                try {
                    long size = Files.size(path), modified = Files.getLastModifiedTime(path).toMillis();
                    Source source = old.get(relative);
                    if (source == null || source.size != size || source.modified != modified) {
                        String name = source == null ? baseName(path.getFileName().toString()) : source.displayAsset().name();
                        validateName(name);
                        EmojiDecoder.Info info = EmojiDecoder.inspect(readLimited(path));
                        source = new Source(relative, size, modified, new EmojiAsset(UUID.randomUUID(), name, info.width(), info.height(), info.animated()), null);
                    }
                    String name = source.displayAsset().name();
                    validateName(name);
                    String key = EmojiNames.key(name);
                    if (names.containsKey(key)) throw new IOException("名称重复，请在表情库中改名或重命名此图片");
                    ids.put(source.asset.id(), source);
                    paths.put(relative, source);
                    names.put(key, source.displayAsset());
                } catch (IOException | RuntimeException e) {
                    errors.put(relative, e.getMessage() == null ? "图片读取失败" : e.getMessage());
                    LOGGER.warn("无法载入表情 {}：{}", relative, e.getMessage());
                }
            }
        }
        List<EmojiAsset> local = ids.values().stream().map(Source::displayAsset).toList();
        collected.values().removeIf(id -> !ids.containsKey(id));
        snapshot = new Snapshot(local, Map.copyOf(ids), Map.copyOf(names), Map.copyOf(paths), Map.copyOf(collected));
        problems = Map.copyOf(errors);
        for (UUID id : previous.ids.keySet()) {
            if (!ids.containsKey(id)) availableListener.accept(id);
        }
        writeIndex();
        if (!receivedLoaded) try (var stream = Files.list(received())) {
            for (Path path : stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    EmojiAsset asset = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), EmojiAsset.class);
                    if (asset != null && path.getFileName().toString().equals(asset.id() + ".json")) acceptMetadata(asset);
                } catch (RuntimeException | IOException e) { LOGGER.warn("缓存表情描述无法读取 {}：{}", path.getFileName(), e.getMessage()); }
            }
            receivedLoaded = true;
        }
        return local;
    }

    private static Path availableName(String fileName, Set<String> reserved) throws IOException {
        Path target = personal().resolve(fileName);
        int dot = fileName.lastIndexOf('.');
        String stem = fileName.substring(0, dot), extension = fileName.substring(dot);
        int limit = MemeEmoji.config().maxNameLength;
        for (int i = 2; Files.exists(target) || reserved.contains(baseName(target.getFileName().toString()).toLowerCase(Locale.ROOT)); i++) {
            String suffix = "_" + i;
            if (suffix.length() >= limit) suffix = Integer.toString(i, 36);
            if (suffix.length() > limit) throw new IOException("名称长度上限过小，无法为同名图片分配名称");
            target = personal().resolve(EmojiNames.truncate(stem, limit - suffix.length()) + suffix + extension);
        }
        validateName(baseName(target.getFileName().toString()));
        return target;
    }

    private static void publish(Map<String, Source> paths, Map<UUID, UUID> collected) {
        Map<UUID, Source> ids = new LinkedHashMap<>();
        Map<String, EmojiAsset> names = new LinkedHashMap<>();
        paths.values().forEach(source -> {
            ids.put(source.asset.id(), source);
            names.put(EmojiNames.key(source.displayAsset().name()), source.displayAsset());
        });
        snapshot = new Snapshot(paths.values().stream().sorted(Comparator.comparing(Source::path)).map(Source::displayAsset).toList(),
                Map.copyOf(ids), Map.copyOf(names), Map.copyOf(paths), collected);
    }

    private static void writeIndex() throws IOException {
        writeAtomic(directory().resolve("library-index.json"),
                GSON.toJson(new Index(1, new ArrayList<>(snapshot.paths.values()), snapshot.collected)).getBytes(StandardCharsets.UTF_8));
    }

    static Map<UUID, UUID> readCollected(String json) {
        Index index = GSON.fromJson(json, Index.class);
        return index == null || index.collected == null ? Map.of() : index.collected;
    }

    private static void validateName(String name) throws IOException {
        int limit = MemeEmoji.config().maxNameLength;
        if (!EmojiNames.isUsable(name, limit))
            throw new IOException("名称不能含空格、冒号，且须在 " + limit + " 字以内");
        if (com.memeemoji.EmojiTokens.REFERENCE.matcher(":" + name + ":").matches())
            throw new IOException("此名称是内部图片标记，请使用其他名称");
    }

    private static byte[] readLimited(Path path) throws IOException {
        long size = Files.size(path);
        if (size < 1 || size > EmojiAsset.MAX_FILE_BYTES) throw new IOException("图片须在 8 MB 以内");
        try (var in = Files.newInputStream(path)) {
            byte[] bytes = in.readNBytes(EmojiAsset.MAX_FILE_BYTES + 1);
            if (bytes.length > EmojiAsset.MAX_FILE_BYTES) throw new IOException("图片须在 8 MB 以内");
            return bytes;
        }
    }

    private static void writeAtomic(Path path, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), ".memeemoji-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static boolean isImage(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String baseName(String name) { return name.substring(0, name.lastIndexOf('.')); }
    private static String relative(Path path) { return personal().relativize(path).toString().replace('\\', '/'); }
    private record Source(String path, long size, long modified, EmojiAsset asset, String name) {
        EmojiAsset displayAsset() {
            return name == null ? asset : new EmojiAsset(asset.id(), name, asset.width(), asset.height(), asset.animated());
        }
    }
    private record Index(int version, List<Source> sources, Map<UUID, UUID> collected) {}
    private record Snapshot(List<EmojiAsset> local, Map<UUID, Source> ids, Map<String, EmojiAsset> names,
                            Map<String, Source> paths, Map<UUID, UUID> collected) {}
}
