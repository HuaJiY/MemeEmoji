package com.memeemoji;

import com.memeemoji.chat.EmojiChatRenderer;
import com.memeemoji.media.EmojiLibrary;
import com.memeemoji.media.EmojiTextures;
import com.memeemoji.media.LibraryWatcher;
import com.memeemoji.net.EmojiNetworkClient;
import com.memeemoji.gui.GlassStyle;
import net.minecraft.client.Minecraft;

public final class MemeEmojiClient {
    public static void init() {
        EmojiLibrary.reload();
        LibraryWatcher.start(() -> Minecraft.getInstance().execute(EmojiChatRenderer::invalidate));
    }

    public static void disconnect() {
        EmojiNetworkClient.reset();
        EmojiChatRenderer.invalidate();
        EmojiTextures.close();
    }

    public static void close() {
        LibraryWatcher.close();
        EmojiTextures.close();
        GlassStyle.release();
    }

    public static void reloadResources() {
        GlassStyle.release();
        EmojiChatRenderer.invalidate();
    }
}
