package com.memeemoji.media;

import java.util.UUID;

public record EmojiAsset(UUID id, String name, int width, int height, boolean animated) {
    public static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    public static final int MAX_DIMENSION = 2048;

    public EmojiAsset {
        if (id == null || name == null || name.isBlank() || name.length() > 128
                || width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("Invalid emoji metadata");
        }
    }
}
