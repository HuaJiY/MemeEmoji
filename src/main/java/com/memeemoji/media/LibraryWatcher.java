package com.memeemoji.media;

import com.memeemoji.MemeEmoji;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LibraryWatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger("MemeEmoji/Watcher");
    private static LibraryWatcher active;
    private final Path emojiDirectory;
    private final Path configFile;
    private final Runnable reload;
    private final Runnable invalidateConfig;
    private final Map<WatchKey, Path> directories = new HashMap<>();
    private final AtomicBoolean imageChanged = new AtomicBoolean();
    private final AtomicBoolean configChanged = new AtomicBoolean();
    private final ScheduledExecutorService debouncer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "MemeEmoji-Debouncer");
        thread.setDaemon(true);
        return thread;
    });
    private WatchService service;
    private ScheduledFuture<?> pending;
    private volatile boolean closed;
    private String configContents;

    LibraryWatcher(Path emojiDirectory, Path configFile, Runnable reload, Runnable invalidateConfig) {
        this.emojiDirectory = emojiDirectory.toAbsolutePath().normalize();
        this.configFile = configFile.toAbsolutePath().normalize();
        this.reload = reload;
        this.invalidateConfig = invalidateConfig;
    }

    public static void start() { start(() -> {}); }

    public static synchronized void start(Runnable onReload) {
        if (active != null) return;
        LibraryWatcher watcher = new LibraryWatcher(EmojiLibrary.directory().resolve("emoji"),
                EmojiLibrary.directory().resolve("config.json"),
                () -> EmojiLibrary.reload().whenComplete((assets, error) -> {
                    if (error != null) LOGGER.warn("自动刷新表情库失败：{}", error.getMessage());
                    else onReload.run();
                }), MemeEmoji::invalidateConfig);
        try {
            watcher.open();
            active = watcher;
        } catch (IOException e) {
            watcher.stop();
            LOGGER.warn("表情文件监听无法启动，可使用手动刷新：{}", e.getMessage());
        }
    }

    public static synchronized void close() {
        if (active != null) {
            active.stop();
            active = null;
        }
    }

    void open() throws IOException {
        Files.createDirectories(emojiDirectory);
        Files.createDirectories(configFile.getParent());
        configContents = readConfig();
        service = emojiDirectory.getFileSystem().newWatchService();
        register(configFile.getParent());
        registerTree(emojiDirectory);
        Thread thread = new Thread(this::watch, "MemeEmoji-Watcher");
        thread.setDaemon(true);
        thread.start();
    }

    private void register(Path path) throws IOException {
        WatchKey key = path.register(service, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
        directories.put(key, path);
    }

    private void registerTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isDirectory).toList()) register(path);
        }
    }

    private void watch() {
        try {
            while (!closed) {
                WatchKey key = service.take();
                Path directory = directories.get(key);
                if (directory == null) { key.reset(); continue; }
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        imageChanged.set(true);
                        configChanged.set(true);
                        if (Files.isDirectory(emojiDirectory)) registerTree(emojiDirectory);
                    } else {
                        Path path = directory.resolve((Path) event.context());
                        if (path.equals(configFile)) configChanged.set(true);
                        else if (path.startsWith(emojiDirectory)) {
                            imageChanged.set(true);
                            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(path)) {
                                try { registerTree(path); }
                                catch (IOException e) { LOGGER.warn("无法监听新表情目录 {}：{}", path, e.getMessage()); }
                            }
                        }
                    }
                }
                if (!key.reset()) directories.remove(key);
                if (imageChanged.get() || configChanged.get()) schedule();
            }
        } catch (ClosedWatchServiceException ignored) {
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (IOException e) { if (!closed) LOGGER.warn("表情目录监听停止：{}", e.getMessage()); }
    }

    private synchronized void schedule() {
        if (closed) return;
        if (pending != null) pending.cancel(false);
        pending = debouncer.schedule(this::changed, 500, TimeUnit.MILLISECONDS);
    }

    private void changed() {
        boolean refresh = imageChanged.getAndSet(false);
        if (configChanged.getAndSet(false)) {
            try {
                String contents = readConfig();
                if (!Objects.equals(configContents, contents)) {
                    configContents = contents;
                    invalidateConfig.run();
                    refresh = true;
                }
            } catch (IOException e) { LOGGER.warn("配置文件暂时无法读取：{}", e.getMessage()); }
        }
        if (refresh && !closed) reload.run();
    }

    private String readConfig() throws IOException {
        return Files.isRegularFile(configFile) ? Files.readString(configFile, StandardCharsets.UTF_8) : null;
    }

    synchronized void stop() {
        closed = true;
        if (pending != null) pending.cancel(false);
        debouncer.shutdownNow();
        if (service != null) try { service.close(); } catch (IOException e) { LOGGER.debug("关闭文件监听失败", e); }
    }
}
