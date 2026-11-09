package com.memeemoji.net;

import com.google.gson.Gson;
import com.memeemoji.EmojiTokens;
import com.memeemoji.MemeEmoji;
import com.memeemoji.media.EmojiAsset;
import com.memeemoji.media.EmojiDecoder;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.memeemoji.net.EmojiPackets.*;

public final class EmojiNetwork {
    private static final Gson GSON = new Gson();
    private static final long TIMEOUT = 45_000;
    private static final Map<MinecraftServer, World> WORLDS = new HashMap<>();

    private EmojiNetwork() { }

    public static void initCommon() {
        PayloadTypeRegistry.playC2S().register(Message.TYPE, Message.CODEC);
        PayloadTypeRegistry.playC2S().register(Control.TYPE, Control.CODEC);
        PayloadTypeRegistry.playC2S().register(Start.TYPE, Start.CODEC);
        PayloadTypeRegistry.playC2S().register(Chunk.TYPE, Chunk.CODEC);
        PayloadTypeRegistry.playS2C().register(Chat.TYPE, Chat.CODEC);
        PayloadTypeRegistry.playS2C().register(Control.TYPE, Control.CODEC);
        PayloadTypeRegistry.playS2C().register(Start.TYPE, Start.CODEC);
        PayloadTypeRegistry.playS2C().register(Chunk.TYPE, Chunk.CODEC);
    }

    public static void initServer() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> WORLDS.put(server, new World(server)));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            World world = WORLDS.remove(server);
            if (world != null) world.io.shutdownNow();
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            World world = WORLDS.get(server);
            if (world != null) world.connections.put(handler.player.getUUID(), new Connection(handler.player));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            World world = WORLDS.get(server);
            if (world != null) world.connections.remove(handler.player.getUUID());
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            World world = WORLDS.get(server);
            if (world == null) return;
            for (Connection c : world.connections.values()) {
                c.transfers.pump(packet -> ServerPlayNetworking.send(c.player, packet));
                if (c.pending != null && System.currentTimeMillis() > c.pending.deadline) {
                    fail(c, c.pending.message.ticket(), c.pending.message.assets().getFirst().id(), "图片发送超时，请重试");
                }
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(Message.TYPE, (packet, context) -> receiveMessage(context.player(), packet));
        ServerPlayNetworking.registerGlobalReceiver(Start.TYPE, (packet, context) -> receiveStart(context.player(), packet));
        ServerPlayNetworking.registerGlobalReceiver(Chunk.TYPE, (packet, context) -> receiveChunk(context.player(), packet));
        ServerPlayNetworking.registerGlobalReceiver(Control.TYPE, (packet, context) -> {
            if (packet.action() == REQUEST) download(context.player(), packet);
            else if (packet.action() == ERROR) {
                World world = WORLDS.get(context.player().server);
                Connection c = connection(world, context.player());
                if (c != null && c.pending != null && c.pending.message.ticket().equals(packet.ticket())) c.pending = null;
                if (c != null) { c.transfers.remove(packet.ticket()); c.downloads.remove(packet.ticket()); }
            }
        });
    }

    private static void receiveMessage(ServerPlayer player, Message message) {
        World world = WORLDS.get(player.server);
        Connection c = connection(world, player);
        if (c == null) return;
        if (!MemeEmoji.config().enabled) { error(player, message.ticket(), message.assets().getFirst().id(), "服务器已关闭表情功能"); return; }
        if (c.pending != null) {
            error(player, message.ticket(), message.assets().getFirst().id(), "上一条图片消息仍在发送");
            return;
        }
        try {
            Set<UUID> references = new LinkedHashSet<>();
            var matcher = EmojiTokens.REFERENCE.matcher(message.text());
            while (matcher.find()) references.add(UUID.fromString(matcher.group(2)));
            Map<UUID, EmojiAsset> assets = new LinkedHashMap<>();
            for (EmojiAsset asset : message.assets()) {
                if (assets.put(asset.id(), asset) != null) throw new IllegalArgumentException();
            }
            if (!references.equals(assets.keySet()) || references.isEmpty() || message.text().isBlank()
                    || message.text().codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            error(player, message.ticket(), message.assets().getFirst().id(), "图片消息格式错误");
            return;
        }
        Pending pending = new Pending(message);
        c.pending = pending;
        world.work(() -> {
            Map<UUID, Cached> found = new HashMap<>();
            for (EmojiAsset asset : message.assets()) {
                Cached cached = world.load(asset.id());
                if (cached != null) {
                    if (!cached.asset.equals(asset)) throw new IOException("表情标识与已缓存图片冲突");
                    found.put(asset.id(), cached);
                }
            }
            return found;
        }).whenComplete((found, failure) -> player.server.execute(() -> {
            if (!current(world, c, pending)) return;
            if (failure != null) { fail(c, message.ticket(), message.assets().getFirst().id(), "读取服务器图片缓存失败"); return; }
            message.assets().stream().filter(a -> !found.containsKey(a.id())).forEach(pending.missing::add);
            next(world, c, pending);
        }));
    }

    private static void next(World world, Connection c, Pending pending) {
        if (!MemeEmoji.config().enabled) {
            fail(c, pending.message.ticket(), pending.message.assets().getFirst().id(), "服务器已关闭表情功能");
            return;
        }
        pending.assembly = null;
        pending.processing = false;
        pending.expected = pending.missing.poll();
        pending.deadline = System.currentTimeMillis() + TIMEOUT;
        if (pending.expected != null) {
            ServerPlayNetworking.send(c.player, new Control(UPLOAD, pending.message.ticket(), pending.expected.id(), ""));
            return;
        }
        String name = c.player.getGameProfile().getName();
        Chat chat = new Chat(c.player.getUUID(), name, pending.message.text(), pending.message.assets());
        String fallback = EmojiTokens.REFERENCE.matcher(pending.message.text()).replaceAll("[图片]");
        for (ServerPlayer receiver : world.server.getPlayerList().getPlayers()) {
            if (ServerPlayNetworking.canSend(receiver, Chat.TYPE)) ServerPlayNetworking.send(receiver, chat);
            else receiver.sendSystemMessage(Component.translatable("chat.type.text", Component.literal(name), Component.literal(fallback)));
        }
        ServerPlayNetworking.send(c.player, new Control(DELIVERED, pending.message.ticket(), pending.message.assets().getFirst().id(), ""));
        c.pending = null;
    }

    private static void receiveStart(ServerPlayer player, Start start) {
        World world = WORLDS.get(player.server);
        Connection c = connection(world, player);
        if (c == null || c.pending == null || !c.pending.message.ticket().equals(start.ticket())) return;
        if (!MemeEmoji.config().enabled) { fail(c, start.ticket(), start.asset().id(), "服务器已关闭表情功能"); return; }
        Pending p = c.pending;
        try {
            if (p.processing || p.assembly != null || !start.asset().equals(p.expected)) throw new IllegalArgumentException();
            p.assembly = new Assembly(start.asset(), start.size());
        } catch (RuntimeException e) { fail(c, start.ticket(), start.asset().id(), "图片上传信息不正确或超过 8 MB"); }
    }

    private static void receiveChunk(ServerPlayer player, Chunk chunk) {
        World world = WORLDS.get(player.server);
        Connection c = connection(world, player);
        if (c == null || c.pending == null || !c.pending.message.ticket().equals(chunk.ticket())) return;
        if (!MemeEmoji.config().enabled) { fail(c, chunk.ticket(), chunk.id(), "服务器已关闭表情功能"); return; }
        Pending p = c.pending;
        try {
            if (p.processing || p.assembly == null) throw new IllegalArgumentException();
            boolean complete = p.assembly.append(chunk);
            p.deadline = System.currentTimeMillis() + TIMEOUT;
            if (!complete) return;
        } catch (RuntimeException e) { fail(c, chunk.ticket(), chunk.id(), "图片分片损坏，请重试"); return; }
        p.processing = true;
        Assembly assembly = p.assembly;
        world.work(() -> {
            var info = EmojiDecoder.inspect(assembly.bytes);
            if (info.width() != assembly.asset.width() || info.height() != assembly.asset.height()
                    || info.animated() != assembly.asset.animated()) throw new IOException("图片实际内容与描述不一致");
            return world.save(player.getUUID(), assembly.asset, assembly.bytes);
        }).whenComplete((cached, failure) -> player.server.execute(() -> {
            if (!current(world, c, p)) return;
            if (failure != null) { fail(c, chunk.ticket(), chunk.id(), "图片格式、尺寸或标识校验失败"); return; }
            next(world, c, p);
        }));
    }

    private static void download(ServerPlayer player, Control request) {
        World world = WORLDS.get(player.server);
        Connection c = connection(world, player);
        if (c == null) return;
        if (!MemeEmoji.config().enabled) { error(player, request.ticket(), request.id(), "服务器已关闭表情功能"); return; }
        if (c.downloads.contains(request.ticket())) return;
        if (c.downloads.size() >= 4) { error(player, request.ticket(), request.id(), "正在下载的图片太多，请稍后重试"); return; }
        c.downloads.add(request.ticket());
        world.work(() -> world.download(request.id())).whenComplete((data, failure) -> player.server.execute(() -> {
            if (connection(world, player) != c || !c.downloads.contains(request.ticket())) return;
            if (failure != null) {
                c.downloads.remove(request.ticket());
                error(player, request.ticket(), request.id(), "服务器中找不到这张图片，请让发送者重发"); return;
            }
            c.transfers.add(request.ticket(), data.asset, data.bytes, () -> {}, () -> c.downloads.remove(request.ticket()));
        }));
    }

    private static Connection connection(World world, ServerPlayer player) {
        if (world == null || WORLDS.get(player.server) != world) return null;
        Connection c = world.connections.get(player.getUUID());
        return c != null && c.player == player ? c : null;
    }

    private static boolean current(World world, Connection c, Pending p) {
        return connection(world, c.player) == c && c.pending == p;
    }

    private static void fail(Connection c, UUID ticket, UUID id, String text) {
        c.pending = null;
        error(c.player, ticket, id, text);
    }

    private static void error(ServerPlayer player, UUID ticket, UUID id, String text) {
        ServerPlayNetworking.send(player, new Control(ERROR, ticket, id, text));
    }

    private record Cached(UUID owner, EmojiAsset asset) { }
    private record Download(EmojiAsset asset, byte[] bytes) { }
    private static final class Connection {
        final ServerPlayer player;
        Pending pending;
        final Set<UUID> downloads = new LinkedHashSet<>();
        final EmojiTransferQueue transfers = new EmojiTransferQueue();
        Connection(ServerPlayer player) { this.player = player; }
    }
    private static final class Pending {
        final Message message;
        final Queue<EmojiAsset> missing = new ArrayDeque<>();
        long deadline = System.currentTimeMillis() + TIMEOUT;
        EmojiAsset expected;
        Assembly assembly;
        boolean processing;
        Pending(Message message) { this.message = message; }
    }

    private static final class World {
        final MinecraftServer server;
        final Path path;
        final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "MemeEmoji-server-cache"); thread.setDaemon(true); return thread;
        });
        final Map<UUID, Connection> connections = new HashMap<>();
        final Map<UUID, Cached> metadata = new LinkedHashMap<>(16, .75f, true);
        final Map<UUID, Download> hot = new LinkedHashMap<>(16, .75f, true);
        long hotBytes;
        World(MinecraftServer server) { this.server = server; path = server.getWorldPath(LevelResource.ROOT).resolve("memeemoji/assets"); }

        <T> CompletableFuture<T> work(IOWork<T> action) {
            return CompletableFuture.supplyAsync(() -> {
                try { return action.run(); }
                catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
            }, io);
        }

        Cached load(UUID id) throws IOException {
            Cached known = metadata.get(id);
            if (known != null) return known;
            Path metadata = path.resolve(id + ".json");
            if (!Files.exists(metadata)) return null;
            Cached cached = GSON.fromJson(Files.readString(metadata, StandardCharsets.UTF_8), Cached.class);
            if (cached == null || cached.owner == null || cached.asset == null || !cached.asset.id().equals(id)) throw new IOException("Invalid metadata");
            if (!Files.isRegularFile(path.resolve(id + ".bin"))) return null;
            remember(cached);
            return cached;
        }

        private void remember(Cached cached) {
            metadata.put(cached.asset.id(), cached);
            if (metadata.size() > 1024) metadata.remove(metadata.keySet().iterator().next());
        }

        Download download(UUID id) throws IOException {
            Download known = hot.get(id);
            if (known != null) return known;
            Cached cached = load(id);
            if (cached == null) throw new IOException("图片不存在");
            byte[] bytes;
            try (var in = Files.newInputStream(path.resolve(id + ".bin"))) {
                bytes = in.readNBytes(EmojiAsset.MAX_FILE_BYTES + 1);
            }
            if (bytes.length < 1 || bytes.length > EmojiAsset.MAX_FILE_BYTES) throw new IOException("Invalid cache size");
            Download data = new Download(cached.asset, bytes);
            remember(data);
            return data;
        }

        private void remember(Download data) {
            Download old = hot.put(data.asset.id(), data);
            hotBytes += data.bytes.length - (old == null ? 0 : old.bytes.length);
            while (hotBytes > 32L * 1024 * 1024 || hot.size() > 64) {
                UUID first = hot.keySet().iterator().next();
                hotBytes -= hot.remove(first).bytes.length;
            }
        }

        Cached save(UUID owner, EmojiAsset asset, byte[] bytes) throws IOException {
            Cached existing = load(asset.id());
            if (existing != null) {
                if (!existing.asset.equals(asset)) throw new IOException("UUID ownership conflict");
                return existing;
            }
            Path priorMetadata = path.resolve(asset.id() + ".json");
            if (Files.exists(priorMetadata)) {
                Cached prior = GSON.fromJson(Files.readString(priorMetadata, StandardCharsets.UTF_8), Cached.class);
                if (prior == null || !owner.equals(prior.owner) || !asset.equals(prior.asset)) throw new IOException("UUID ownership conflict");
            }
            Files.createDirectories(path);
            Cached value = new Cached(owner, asset);
            Path data = path.resolve(asset.id() + ".bin");
            Path metadata = path.resolve(asset.id() + ".json");
            Path dataTemp = path.resolve(asset.id() + ".bin.tmp");
            Path metaTemp = path.resolve(asset.id() + ".json.tmp");
            try {
                Files.write(dataTemp, bytes);
                Files.writeString(metaTemp, GSON.toJson(value), StandardCharsets.UTF_8);
                Files.move(dataTemp, data, StandardCopyOption.REPLACE_EXISTING);
                Files.move(metaTemp, metadata, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(dataTemp);
                Files.deleteIfExists(metaTemp);
            }
            remember(value);
            remember(new Download(asset, bytes));
            return value;
        }
    }
    @FunctionalInterface private interface IOWork<T> { T run() throws Exception; }
}
