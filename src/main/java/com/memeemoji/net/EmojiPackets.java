package com.memeemoji.net;

import com.memeemoji.media.EmojiAsset;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

final class EmojiPackets {
    static final int PART_BYTES = 24 * 1024;
    static final int MAX_ASSETS = 16;
    static final int MAX_TEXT = 2048;
    static final int REQUEST = 0, UPLOAD = 1, ERROR = 2, DELIVERED = 3;

    record Message(UUID ticket, String text, List<EmojiAsset> assets) implements CustomPacketPayload {
        static final Type<Message> TYPE = EmojiPackets.type("message");
        static final StreamCodec<RegistryFriendlyByteBuf, Message> CODEC = codec(
                b -> new Message(b.readUUID(), b.readUtf(MAX_TEXT), readAssets(b)),
                (b, p) -> { b.writeUUID(p.ticket); b.writeUtf(p.text, MAX_TEXT); writeAssets(b, p.assets); });
        @Override public Type<Message> type() { return TYPE; }
    }

    record Chat(UUID sender, String name, String text, List<EmojiAsset> assets) implements CustomPacketPayload {
        static final Type<Chat> TYPE = EmojiPackets.type("chat");
        static final StreamCodec<RegistryFriendlyByteBuf, Chat> CODEC = codec(
                b -> new Chat(b.readUUID(), b.readUtf(64), b.readUtf(MAX_TEXT), readAssets(b)),
                (b, p) -> { b.writeUUID(p.sender); b.writeUtf(p.name, 64); b.writeUtf(p.text, MAX_TEXT); writeAssets(b, p.assets); });
        @Override public Type<Chat> type() { return TYPE; }
    }

    record Control(int action, UUID ticket, UUID id, String error) implements CustomPacketPayload {
        static final Type<Control> TYPE = EmojiPackets.type("control");
        static final StreamCodec<RegistryFriendlyByteBuf, Control> CODEC = codec(
                b -> new Control(b.readVarInt(), b.readUUID(), b.readUUID(), b.readUtf(256)),
                (b, p) -> { b.writeVarInt(p.action); b.writeUUID(p.ticket); b.writeUUID(p.id); b.writeUtf(p.error, 256); });
        @Override public Type<Control> type() { return TYPE; }
    }

    record Start(UUID ticket, EmojiAsset asset, int size) implements CustomPacketPayload {
        static final Type<Start> TYPE = EmojiPackets.type("start");
        static final StreamCodec<RegistryFriendlyByteBuf, Start> CODEC = codec(
                b -> new Start(b.readUUID(), readAsset(b), b.readVarInt()),
                (b, p) -> { b.writeUUID(p.ticket); writeAsset(b, p.asset); b.writeVarInt(p.size); });
        @Override public Type<Start> type() { return TYPE; }
    }

    record Chunk(UUID ticket, UUID id, int offset, byte[] bytes) implements CustomPacketPayload {
        static final Type<Chunk> TYPE = EmojiPackets.type("chunk");
        static final StreamCodec<RegistryFriendlyByteBuf, Chunk> CODEC = codec(
                b -> new Chunk(b.readUUID(), b.readUUID(), b.readVarInt(), b.readByteArray(PART_BYTES)),
                (b, p) -> { b.writeUUID(p.ticket); b.writeUUID(p.id); b.writeVarInt(p.offset); b.writeByteArray(p.bytes); });
        @Override public Type<Chunk> type() { return TYPE; }
    }

    static final class Assembly {
        final EmojiAsset asset;
        final byte[] bytes;
        int received;

        Assembly(EmojiAsset asset, int size) {
            if (size < 1 || size > EmojiAsset.MAX_FILE_BYTES) throw new IllegalArgumentException("图片大小超过 8 MB");
            this.asset = asset;
            this.bytes = new byte[size];
        }

        boolean append(Chunk chunk) {
            if (!asset.id().equals(chunk.id) || chunk.offset != received || chunk.bytes.length == 0
                    || chunk.bytes.length > PART_BYTES || received + chunk.bytes.length > bytes.length) {
                throw new IllegalArgumentException("图片分片顺序或大小错误");
            }
            System.arraycopy(chunk.bytes, 0, bytes, received, chunk.bytes.length);
            received += chunk.bytes.length;
            return received == bytes.length;
        }
    }

    static void chunks(UUID ticket, EmojiAsset asset, byte[] bytes, java.util.function.Consumer<CustomPacketPayload> send) {
        send.accept(new Start(ticket, asset, bytes.length));
        for (int offset = 0; offset < bytes.length; offset += PART_BYTES) {
            send.accept(new Chunk(ticket, asset.id(), offset,
                    java.util.Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + PART_BYTES))));
        }
    }

    private static EmojiAsset readAsset(RegistryFriendlyByteBuf b) {
        return new EmojiAsset(b.readUUID(), b.readUtf(128), b.readVarInt(), b.readVarInt(), b.readBoolean());
    }

    private static void writeAsset(RegistryFriendlyByteBuf b, EmojiAsset a) {
        b.writeUUID(a.id()); b.writeUtf(a.name(), 128); b.writeVarInt(a.width()); b.writeVarInt(a.height()); b.writeBoolean(a.animated());
    }

    private static List<EmojiAsset> readAssets(RegistryFriendlyByteBuf b) {
        int size = b.readVarInt();
        if (size < 1 || size > MAX_ASSETS) throw new IllegalArgumentException("Invalid emoji count");
        List<EmojiAsset> assets = new ArrayList<>(size);
        for (int i = 0; i < size; i++) assets.add(readAsset(b));
        return List.copyOf(assets);
    }

    private static void writeAssets(RegistryFriendlyByteBuf b, List<EmojiAsset> assets) {
        b.writeVarInt(assets.size());
        assets.forEach(a -> writeAsset(b, a));
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String name) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("memeemoji", name));
    }

    private static <T> StreamCodec<RegistryFriendlyByteBuf, T> codec(Function<RegistryFriendlyByteBuf, T> read,
            BiConsumer<RegistryFriendlyByteBuf, T> write) {
        return new StreamCodec<>() {
            @Override public T decode(RegistryFriendlyByteBuf b) { return read.apply(b); }
            @Override public void encode(RegistryFriendlyByteBuf b, T value) { write.accept(b, value); }
        };
    }
}
