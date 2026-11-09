package com.memeemoji.net;

import com.memeemoji.media.EmojiAsset;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.memeemoji.net.EmojiPackets.*;

public final class EmojiPacketsTest {
    public static void main(String[] args) {
        UUID ticket = UUID.randomUUID();
        EmojiAsset asset = new EmojiAsset(UUID.randomUUID(), "sample", 24, 32, true);
        Message message = new Message(ticket, "before :emoji/" + asset.id() + ": after", List.of(asset));
        check(message.equals(roundTrip(Message.CODEC, message)), "Message codec changed values");
        Chat chat = new Chat(UUID.randomUUID(), "player", message.text(), message.assets());
        check(chat.equals(roundTrip(Chat.CODEC, chat)), "Chat codec changed sender or metadata");
        Control control = new Control(ERROR, ticket, asset.id(), "transfer failed");
        check(control.equals(roundTrip(Control.CODEC, control)), "Control codec changed values");
        Start start = new Start(ticket, asset, EmojiAsset.MAX_FILE_BYTES);
        check(start.equals(roundTrip(Start.CODEC, start)), "Start codec changed size or metadata");

        byte[] boundary = new byte[PART_BYTES];
        Arrays.fill(boundary, (byte) 42);
        Chunk chunk = new Chunk(ticket, asset.id(), 0, boundary);
        Chunk decoded = roundTrip(Chunk.CODEC, chunk);
        check(decoded.ticket().equals(ticket) && decoded.id().equals(asset.id()) && decoded.offset() == 0
                && Arrays.equals(decoded.bytes(), boundary), "Chunk codec changed bytes");
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            Chunk.CODEC.encode(buffer, chunk);
            check(buffer.readableBytes() < 32_767, "C2S chunk exceeds Minecraft payload limit");
        } finally { buffer.release(); }
        rejects(() -> roundTrip(Chunk.CODEC, new Chunk(ticket, asset.id(), 0, new byte[PART_BYTES + 1])), "Oversized packet chunk accepted");

        byte[] original = new byte[PART_BYTES + 7];
        for (int i = 0; i < original.length; i++) original[i] = (byte) i;
        List<net.minecraft.network.protocol.common.custom.CustomPacketPayload> packets = new ArrayList<>();
        EmojiTransferQueue queue = new EmojiTransferQueue();
        queue.add(ticket, asset, original, () -> {}, () -> {});
        check(packets.isEmpty(), "Enqueue must not send on the calling frame");
        queue.pump(packets::add);
        check(packets.size() == 3, "Boundary split should have a start and two chunks");
        Assembly assembly = new Assembly(asset, original.length);
        check(!assembly.append((Chunk) packets.get(1)), "Partial transfer marked complete");
        check(assembly.append((Chunk) packets.get(2)), "Full transfer not marked complete");
        check(Arrays.equals(original, assembly.bytes), "Reassembled bytes differ");
        rejects(() -> assembly.append((Chunk) packets.get(2)), "Duplicate chunk accepted");
        rejects(() -> new Assembly(asset, 3).append(new Chunk(ticket, asset.id(), 1, new byte[]{1})), "Out-of-order chunk accepted");
        rejects(() -> new Assembly(asset, 3).append(new Chunk(ticket, UUID.randomUUID(), 0, new byte[]{1})), "Wrong asset chunk accepted");
        rejects(() -> new Assembly(asset, 3).append(new Chunk(ticket, asset.id(), 0, new byte[4])), "Overflow chunk accepted");
        rejects(() -> new Assembly(asset, 3).append(new Chunk(ticket, asset.id(), 0, new byte[0])), "Empty chunk accepted");
        rejects(() -> new Assembly(asset, PART_BYTES + 1).append(new Chunk(ticket, asset.id(), 0, new byte[PART_BYTES + 1])), "Oversized assembly chunk accepted");
        rejects(() -> new Assembly(asset, 0), "Empty file accepted");
        rejects(() -> new Assembly(asset, EmojiAsset.MAX_FILE_BYTES + 1), "File above 8 MB accepted");
        transferScheduling();

        List<EmojiAsset> maximum = new ArrayList<>();
        for (int i = 0; i < MAX_ASSETS; i++) maximum.add(new EmojiAsset(UUID.randomUUID(), "item" + i, 16, 16, false));
        check(roundTrip(Message.CODEC, new Message(ticket, "test", maximum)).assets().size() == MAX_ASSETS, "16-image message rejected");
        maximum.add(asset);
        rejects(() -> roundTrip(Message.CODEC, new Message(ticket, "test", maximum)), "More than 16 images accepted");
        rejects(() -> roundTrip(Message.CODEC, new Message(ticket, "test", List.of())), "Asset-free custom message accepted");
        rejects(() -> roundTrip(Message.CODEC, new Message(ticket, "a".repeat(MAX_TEXT + 1), List.of(asset))), "Oversized text accepted");
        rejects(() -> new EmojiAsset(UUID.randomUUID(), "large", EmojiAsset.MAX_DIMENSION + 1, 1, false), "Oversized dimensions accepted");
        System.out.println("EmojiPacketsTest passed");
    }

    private static void transferScheduling() {
        EmojiTransferQueue queue = new EmojiTransferQueue();
        byte[] bytes = new byte[EmojiAsset.MAX_FILE_BYTES];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        var assemblies = new java.util.HashMap<UUID, Assembly>();
        var tickets = new ArrayList<UUID>();
        int[] completed = {0}, progress = {0};
        for (int i = 0; i < 4; i++) {
            UUID ticket = UUID.randomUUID(); tickets.add(ticket);
            EmojiAsset asset = new EmojiAsset(UUID.randomUUID(), "large" + i, 100, 100, false);
            queue.add(ticket, asset, bytes, () -> progress[0]++, () -> completed[0]++);
        }
        int ticks = 0;
        while (!queue.isEmpty()) {
            List<net.minecraft.network.protocol.common.custom.CustomPacketPayload> sent = new ArrayList<>();
            queue.pump(sent::add);
            check(sent.stream().filter(Chunk.class::isInstance).count() <= EmojiTransferQueue.CHUNKS_PER_TICK,
                    "Transfer exceeded tick budget");
            for (var packet : sent) {
                if (packet instanceof Start start) assemblies.put(start.ticket(), new Assembly(start.asset(), start.size()));
                else if (packet instanceof Chunk chunk) assemblies.get(chunk.ticket()).append(chunk);
            }
            if (++ticks == 1) check(assemblies.size() == 4, "Concurrent transfers must take turns without starving");
            check(ticks < 400, "Transfer queue did not drain");
        }
        check(ticks == 342 && completed[0] == 4 && progress[0] == 1368, "Four 8 MB transfers have bounded work and completion");
        for (Assembly assembly : assemblies.values()) check(Arrays.equals(assembly.bytes, bytes), "Scheduled bytes changed");
        UUID cancelled = UUID.randomUUID();
        queue.add(cancelled, new EmojiAsset(UUID.randomUUID(), "cancel", 1, 1, false), bytes, () -> {}, () -> { throw new AssertionError("Cancelled transfer completed"); });
        queue.remove(cancelled);
        queue.pump(packet -> { throw new AssertionError("Cancelled transfer sent data"); });
        queue.add(cancelled, new EmojiAsset(UUID.randomUUID(), "reset", 1, 1, false), bytes, () -> {}, () -> {});
        queue.clear(); check(queue.isEmpty(), "Disconnect must release queued files");
        System.out.println("Transfer scheduling: 4 x 8 MB, 342 ticks, <=96 KB/tick, round-robin, exact bytes and cancellation passed");
    }

    private static RegistryFriendlyByteBuf buffer() { return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY); }

    private static <T> T roundTrip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            codec.encode(buffer, value);
            T decoded = codec.decode(buffer);
            check(!buffer.isReadable(), "Codec left trailing bytes");
            return decoded;
        } finally { buffer.release(); }
    }

    private static void rejects(Runnable action, String message) {
        try { action.run(); }
        catch (RuntimeException expected) { return; }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
