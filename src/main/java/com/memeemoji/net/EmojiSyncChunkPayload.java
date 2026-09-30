package com.memeemoji.net;

import com.memeemoji.MemeEmoji;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 一张表情图。码位不传，客户端按序号推出 PUA_BASE + index；单张 PNG 超过上限时切成多片。
 * width/height 是图片的逻辑尺寸，客户端据此生成 bitmap font provider 的 height 和 ascent。
 */
public record EmojiSyncChunkPayload(long sessionId, int index, String name,
                                    int partIndex, int partTotal, byte[] png,
                                    int width, int height) implements CustomPacketPayload {

    public static final int MAX_PART_BYTES = 768 * 1024;

    public static final Type<EmojiSyncChunkPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MemeEmoji.MOD_ID, "emoji_sync_chunk"));

    public static final StreamCodec<ByteBuf, EmojiSyncChunkPayload> STREAM_CODEC = new StreamCodec<>() {
        private final StreamCodec<ByteBuf, byte[]> bytes = ByteBufCodecs.byteArray(MAX_PART_BYTES);

        @Override
        public EmojiSyncChunkPayload decode(ByteBuf buffer) {
            long sessionId = ByteBufCodecs.VAR_LONG.decode(buffer);
            int index = ByteBufCodecs.VAR_INT.decode(buffer);
            String name = ByteBufCodecs.STRING_UTF8.decode(buffer);
            int partIndex = ByteBufCodecs.VAR_INT.decode(buffer);
            int partTotal = ByteBufCodecs.VAR_INT.decode(buffer);
            byte[] png = bytes.decode(buffer);
            int width = ByteBufCodecs.VAR_INT.decode(buffer);
            int height = ByteBufCodecs.VAR_INT.decode(buffer);
            return new EmojiSyncChunkPayload(sessionId, index, name, partIndex, partTotal, png, width, height);
        }

        @Override
        public void encode(ByteBuf buffer, EmojiSyncChunkPayload payload) {
            ByteBufCodecs.VAR_LONG.encode(buffer, payload.sessionId());
            ByteBufCodecs.VAR_INT.encode(buffer, payload.index());
            ByteBufCodecs.STRING_UTF8.encode(buffer, payload.name());
            ByteBufCodecs.VAR_INT.encode(buffer, payload.partIndex());
            ByteBufCodecs.VAR_INT.encode(buffer, payload.partTotal());
            bytes.encode(buffer, payload.png());
            ByteBufCodecs.VAR_INT.encode(buffer, payload.width());
            ByteBufCodecs.VAR_INT.encode(buffer, payload.height());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
