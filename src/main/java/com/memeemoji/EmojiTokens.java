package com.memeemoji;

import java.util.UUID;
import java.util.regex.Pattern;

public final class EmojiTokens {
    public static final Pattern REFERENCE = Pattern.compile(":(emoji|sticker)/([0-9a-fA-F-]{36}):");
    public static final Pattern LOCAL = Pattern.compile(":([^:\\s]{1,128}):");

    private EmojiTokens() {
    }

    public static String reference(UUID id, boolean sticker) {
        return ":" + (sticker ? "sticker" : "emoji") + "/" + id + ":";
    }
}
