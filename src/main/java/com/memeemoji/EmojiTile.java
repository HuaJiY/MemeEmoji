package com.memeemoji;

/**
 * 一张表情图。png 是可直接当 PNG 解码的字节，width/height 是图片的逻辑尺寸（像素），
 * 用于生成独立 bitmap font provider 时的 height 和 ascent 计算。
 */
public record EmojiTile(String name, byte[] png, int width, int height) {
    public String key() {
        return EmojiNames.key(name);
    }
}
