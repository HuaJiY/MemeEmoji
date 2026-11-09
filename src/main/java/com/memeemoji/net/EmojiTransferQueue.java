package com.memeemoji.net;

import com.memeemoji.media.EmojiAsset;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Consumer;

import static com.memeemoji.net.EmojiPackets.*;

final class EmojiTransferQueue {
    static final int CHUNKS_PER_TICK = 4;
    private final ArrayDeque<Transfer> transfers = new ArrayDeque<>();

    void add(UUID ticket, EmojiAsset asset, byte[] bytes, Runnable progress, Runnable complete) {
        transfers.addLast(new Transfer(ticket, asset, bytes, progress, complete));
    }

    void pump(Consumer<CustomPacketPayload> send) {
        for (int i = 0; i < CHUNKS_PER_TICK && !transfers.isEmpty(); i++) {
            Transfer transfer = transfers.removeFirst();
            if (transfer.offset == 0) send.accept(new Start(transfer.ticket, transfer.asset, transfer.bytes.length));
            int end = Math.min(transfer.bytes.length, transfer.offset + PART_BYTES);
            send.accept(new Chunk(transfer.ticket, transfer.asset.id(), transfer.offset,
                    Arrays.copyOfRange(transfer.bytes, transfer.offset, end)));
            transfer.offset = end;
            transfer.progress.run();
            if (end == transfer.bytes.length) transfer.complete.run();
            else transfers.addLast(transfer);
        }
    }

    void remove(UUID ticket) { transfers.removeIf(t -> t.ticket.equals(ticket)); }
    void clear() { transfers.clear(); }
    boolean isEmpty() { return transfers.isEmpty(); }

    private static final class Transfer {
        final UUID ticket;
        final EmojiAsset asset;
        final byte[] bytes;
        final Runnable progress, complete;
        int offset;

        Transfer(UUID ticket, EmojiAsset asset, byte[] bytes, Runnable progress, Runnable complete) {
            this.ticket = ticket; this.asset = asset; this.bytes = bytes;
            this.progress = progress; this.complete = complete;
        }
    }
}
