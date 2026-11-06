package com.memeemoji.net;

import com.memeemoji.EmojiTokens;
import com.memeemoji.chat.EmojiChatRenderer;
import com.memeemoji.compat.TalkBubblesCompat;
import com.memeemoji.MemeEmoji;
import com.memeemoji.media.EmojiAsset;
import com.memeemoji.media.EmojiLibrary;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;

import static com.memeemoji.net.EmojiPackets.*;

@Environment(EnvType.CLIENT)
public final class EmojiNetworkClient {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "MemeEmoji-client-transfer"); thread.setDaemon(true); return thread;
    });
    private static final Map<UUID, Outgoing> OUTGOING = new HashMap<>();
    private static final Map<UUID, Incoming> INCOMING = new HashMap<>();
    private static final Set<UUID> CHECKING = new HashSet<>();
    private static final Map<UUID, Long> RETRY_AFTER = new HashMap<>();
    private static final Map<UUID, CompletableFuture<Void>> FETCHES = new HashMap<>();
    private static final Map<UUID, Long> FETCH_DEADLINES = new HashMap<>();
    private static final Set<UUID> WAITING = new LinkedHashSet<>();
    private static long session;

    private EmojiNetworkClient() { }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientPlayNetworking.registerGlobalReceiver(Chat.TYPE, (packet, context) -> {
            if (!MemeEmoji.config().enabled) {
                context.client().gui.getChat().addMessage(Component.translatable("chat.type.text", Component.literal(packet.name()),
                        Component.literal(EmojiTokens.REFERENCE.matcher(packet.text()).replaceAll("[图片]"))));
                return;
            }
            for (EmojiAsset asset : packet.assets()) EmojiLibrary.acceptMetadata(asset);
            EmojiChatRenderer.invalidate();
            context.client().gui.getChat().addMessage(Component.translatable("chat.type.text",
                    Component.literal(packet.name()), Component.literal(packet.text())));
            TalkBubblesCompat.received(packet.sender(), packet.text());
        });
        ClientPlayNetworking.registerGlobalReceiver(Control.TYPE, (packet, context) -> control(packet));
        ClientPlayNetworking.registerGlobalReceiver(Start.TYPE, (packet, context) -> start(packet));
        ClientPlayNetworking.registerGlobalReceiver(Chunk.TYPE, (packet, context) -> chunk(packet));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long now = System.currentTimeMillis();
            for (var entry : List.copyOf(OUTGOING.entrySet())) {
                if (now > entry.getValue().deadline) cancel(entry.getKey(), "图片消息发送超时，请重试");
            }
            for (var entry : List.copyOf(FETCH_DEADLINES.entrySet()))
                if (now > entry.getValue()) downloadFailed(entry.getKey(), "图片下载超时，请稍后重试");
            pumpFetches(now);
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                ClientCommandManager.literal("memeemoji").then(ClientCommandManager.literal("reload").executes(context -> {
                    EmojiLibrary.reload().whenComplete((assets, failure) -> Minecraft.getInstance().execute(() -> {
                        EmojiChatRenderer.invalidate();
                        if (failure != null) error("重新读取表情库失败：" + reason(failure));
                        else {
                            info("已重新读取 " + assets.size() + " 张个人表情");
                            if (!EmojiLibrary.problems().isEmpty()) error(String.join("；", EmojiLibrary.problems().values()));
                        }
                    }));
                    return 1;
                }))));
    }

    public static boolean send(String text) {
        if (!MemeEmoji.config().enabled || text.startsWith("/") || !ClientPlayNetworking.canSend(Message.TYPE)) return false;
        Matcher local = EmojiTokens.LOCAL.matcher(text);
        StringBuilder expanded = new StringBuilder();
        boolean found = false;
        while (local.find()) {
            EmojiAsset asset = EmojiLibrary.findName(local.group(1));
            if (asset != null) {
                local.appendReplacement(expanded, Matcher.quoteReplacement(EmojiTokens.reference(asset.id(), false)));
                found = true;
            }
        }
        local.appendTail(expanded);
        Map<UUID, EmojiAsset> assets = new LinkedHashMap<>();
        boolean unknown = false;
        Matcher references = EmojiTokens.REFERENCE.matcher(expanded);
        while (references.find()) {
            EmojiAsset asset;
            try { asset = EmojiLibrary.find(UUID.fromString(references.group(2))); }
            catch (IllegalArgumentException e) { unknown = true; continue; }
            if (asset != null) { assets.put(asset.id(), asset); found = true; }
            else unknown = true;
        }
        if (!found) return false;
        if (unknown) { error("消息中有未载入的图片标记，请删除该标记后重试"); return true; }
        if (!OUTGOING.isEmpty()) { error("上一条图片消息仍在发送，请稍后重试"); return true; }
        if (expanded.length() > MAX_TEXT || assets.size() > MAX_ASSETS) {
            error("一条消息最多包含 16 张不同图片，文字和图片标记合计不超过 2048 字符");
            return true;
        }
        UUID ticket = UUID.randomUUID();
        List<EmojiAsset> list = List.copyOf(assets.values());
        OUTGOING.put(ticket, new Outgoing(list));
        ClientPlayNetworking.send(new Message(ticket, expanded.toString(), list));
        return true;
    }

    public static void request(UUID id) {
        if (!MemeEmoji.config().enabled || id == null || FETCHES.containsKey(id)
                || INCOMING.size() + CHECKING.size() >= 4 || RETRY_AFTER.getOrDefault(id, 0L) > System.currentTimeMillis()) return;
        fetch(id);
    }

    public static CompletableFuture<Void> fetch(UUID id) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> fetchOnClient(id, result));
        return result;
    }

    private static void fetchOnClient(UUID id, CompletableFuture<Void> result) {
        if (id == null) { result.completeExceptionally(new IOException("图片 UUID 无效")); return; }
        CompletableFuture<Void> active = FETCHES.get(id);
        if (active != null) { active.whenComplete((ignored, failure) -> complete(result, failure)); return; }
        if (RETRY_AFTER.getOrDefault(id, 0L) > System.currentTimeMillis()) {
            result.completeExceptionally(new IOException("图片下载刚刚失败，请稍后重试")); return;
        }
        FETCHES.put(id, result);
        FETCH_DEADLINES.put(id, System.currentTimeMillis() + 45_000);
        WAITING.add(id);
        pumpFetches(System.currentTimeMillis());
    }

    private static void pumpFetches(long now) {
        while (INCOMING.size() + CHECKING.size() < 4 && !WAITING.isEmpty()) {
            UUID id = WAITING.iterator().next();
            WAITING.remove(id);
            if (!FETCHES.containsKey(id)) continue;
            if (now > FETCH_DEADLINES.getOrDefault(id, now)) {
                downloadFailed(id, "图片下载超时，请稍后重试");
                continue;
            }
            checkLocalThenRequest(id);
        }
    }

    private static void checkLocalThenRequest(UUID id) {
        CHECKING.add(id);
        long generation = session;
        CompletableFuture.supplyAsync(() -> {
            if (!EmojiLibrary.hasFile(id)) return false;
            try { EmojiLibrary.read(id); return true; }
            catch (IOException e) { throw new CompletionException(e); }
        }, IO).whenComplete((exists, failure) -> Minecraft.getInstance().execute(() -> {
            if (generation != session || !FETCHES.containsKey(id)) return;
            CHECKING.remove(id);
            if (System.currentTimeMillis() > FETCH_DEADLINES.getOrDefault(id, 0L)) {
                downloadFailed(id, "图片下载超时，请稍后重试");
                return;
            }
            if (failure != null) { downloadFailed(id, "检查图片缓存失败：" + reason(failure)); return; }
            if (Boolean.TRUE.equals(exists)) {
                finishFetch(id, null);
                pumpFetches(System.currentTimeMillis());
                return;
            }
            if (!MemeEmoji.config().enabled || Minecraft.getInstance().getConnection() == null
                    || !ClientPlayNetworking.canSend(Control.TYPE)) { downloadFailed(id, "连接已断开，无法下载图片"); return; }
            EmojiAsset asset = EmojiLibrary.find(id);
            if (asset == null) { downloadFailed(id, "图片信息尚未载入，无法下载"); return; }
            UUID ticket = UUID.randomUUID();
            INCOMING.put(id, new Incoming(ticket, asset));
            ClientPlayNetworking.send(new Control(REQUEST, ticket, id, ""));
            pumpFetches(System.currentTimeMillis());
        }));
    }

    private static void complete(CompletableFuture<Void> target, Throwable failure) {
        if (failure == null) target.complete(null);
        else target.completeExceptionally(failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure);
    }

    private static void finishFetch(UUID id, Throwable failure) {
        CompletableFuture<Void> future = FETCHES.remove(id);
        FETCH_DEADLINES.remove(id);
        WAITING.remove(id);
        if (future == null) return;
        if (failure == null) future.complete(null); else future.completeExceptionally(failure);
    }

    private static void control(Control packet) {
        if (packet.action() == ERROR) {
            if (OUTGOING.remove(packet.ticket()) != null) { error(packet.error()); return; }
            Incoming incoming = INCOMING.get(packet.id());
            if (incoming != null && incoming.ticket.equals(packet.ticket())) downloadFailed(packet.id(), packet.error());
            return;
        }
        if (packet.action() == DELIVERED) { OUTGOING.remove(packet.ticket()); return; }
        if (packet.action() != UPLOAD) return;
        Outgoing outgoing = OUTGOING.get(packet.ticket());
        if (outgoing == null) return;
        if (!MemeEmoji.config().enabled) { cancel(packet.ticket(), "表情功能已关闭，消息未发送"); return; }
        EmojiAsset asset = outgoing.assets.stream().filter(a -> a.id().equals(packet.id())).findFirst().orElse(null);
        if (asset == null || !outgoing.uploading.add(asset.id())) { cancel(packet.ticket(), "服务器请求的图片不在这条消息中"); return; }
        outgoing.deadline = System.currentTimeMillis() + 45_000;
        long generation = session;
        CompletableFuture.supplyAsync(() -> {
            try {
                byte[] bytes = EmojiLibrary.read(asset.id());
                if (bytes.length < 1 || bytes.length > EmojiAsset.MAX_FILE_BYTES) throw new IOException("图片须在 8 MB 以内");
                return bytes;
            } catch (IOException e) { throw new java.util.concurrent.CompletionException(e); }
        }, IO).whenComplete((bytes, failure) -> Minecraft.getInstance().execute(() -> {
            if (generation != session || OUTGOING.get(packet.ticket()) != outgoing) return;
            if (failure != null) { cancel(packet.ticket(), "读取图片失败：" + reason(failure)); return; }
            chunks(packet.ticket(), asset, bytes, ClientPlayNetworking::send);
        }));
    }

    private static void start(Start packet) {
        Incoming incoming = INCOMING.get(packet.asset().id());
        if (incoming == null || !incoming.ticket.equals(packet.ticket())) return;
        if (!MemeEmoji.config().enabled) { downloadFailed(packet.asset().id(), "表情功能已关闭"); return; }
        try {
            if (incoming.assembly != null || !incoming.asset.equals(packet.asset())) throw new IllegalArgumentException();
            incoming.assembly = new Assembly(packet.asset(), packet.size());
        } catch (RuntimeException e) { downloadFailed(packet.asset().id(), "服务器返回的图片描述不正确"); }
    }

    private static void chunk(Chunk packet) {
        Incoming incoming = INCOMING.get(packet.id());
        if (incoming == null || !incoming.ticket.equals(packet.ticket()) || incoming.saving) return;
        if (!MemeEmoji.config().enabled) { downloadFailed(packet.id(), "表情功能已关闭"); return; }
        try {
            if (incoming.assembly == null) throw new IllegalArgumentException();
            if (!incoming.assembly.append(packet)) return;
        } catch (RuntimeException e) { downloadFailed(packet.id(), "下载的图片分片损坏"); return; }
        incoming.saving = true;
        long generation = session;
        EmojiLibrary.accept(incoming.asset, incoming.assembly.bytes).whenComplete((ignored, failure) -> Minecraft.getInstance().execute(() -> {
            if (generation != session || INCOMING.get(packet.id()) != incoming) return;
            if (failure != null) { downloadFailed(packet.id(), "保存图片失败：" + reason(failure)); return; }
            INCOMING.remove(packet.id());
            RETRY_AFTER.remove(packet.id());
            finishFetch(packet.id(), null);
        }));
    }

    private static void cancel(UUID ticket, String message) {
        Outgoing outgoing = OUTGOING.remove(ticket);
        if (outgoing == null) return;
        if (ClientPlayNetworking.canSend(Control.TYPE)) ClientPlayNetworking.send(new Control(ERROR, ticket, outgoing.assets.getFirst().id(), ""));
        error(message);
    }

    private static void downloadFailed(UUID id, String message) {
        INCOMING.remove(id);
        CHECKING.remove(id);
        WAITING.remove(id);
        RETRY_AFTER.put(id, System.currentTimeMillis() + 15_000);
        finishFetch(id, new IOException(message));
        error(message);
        pumpFetches(System.currentTimeMillis());
    }

    private static void reset() {
        session++;
        FETCHES.forEach((id, future) -> future.completeExceptionally(new IOException("服务器连接已断开")));
        FETCHES.clear(); FETCH_DEADLINES.clear(); WAITING.clear();
        OUTGOING.clear(); INCOMING.clear(); CHECKING.clear(); RETRY_AFTER.clear();
    }

    private static String reason(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure.getMessage() == null ? "文件读取失败" : failure.getMessage();
    }

    private static void error(String message) { info("错误：" + message); }
    private static void info(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() != null) client.gui.getChat().addMessage(Component.literal("MemeEmoji：" + message));
    }

    private static final class Outgoing {
        final List<EmojiAsset> assets;
        final Set<UUID> uploading = new HashSet<>();
        long deadline = System.currentTimeMillis() + 45_000;
        Outgoing(List<EmojiAsset> assets) { this.assets = assets; }
    }
    private static final class Incoming {
        final UUID ticket;
        final EmojiAsset asset;
        Assembly assembly;
        boolean saving;
        Incoming(UUID ticket, EmojiAsset asset) { this.ticket = ticket; this.asset = asset; }
    }
}
