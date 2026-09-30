package com.memeemoji.net;

import com.memeemoji.MemeEmoji;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 一次同步的开始，客户端拿到总数后才知道该收多少张表情。 */
public record EmojiSyncStartPayload(long sessionId, int total) implements CustomPacketPayload {

    public static final Type<EmojiSyncStartPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MemeEmoji.MOD_ID, "emoji_sync_start"));

    public static final StreamCodec<ByteBuf, EmojiSyncStartPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, EmojiSyncStartPayload::sessionId,
            ByteBufCodecs.VAR_INT, EmojiSyncStartPayload::total,
            EmojiSyncStartPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
