package com.memeemoji;

/**
 * 一张已经裁成字体盒大小的表情图。png 是可直接当 PNG 解码的字节，用于图集拼图和网络下发。
 */
public record EmojiTile(String name, byte[] png) {
    public String key() {
        return EmojiNames.key(name);
    }
}
