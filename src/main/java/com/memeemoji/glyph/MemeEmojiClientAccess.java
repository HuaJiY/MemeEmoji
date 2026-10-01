package com.memeemoji.glyph;

import com.memeemoji.glyph.MemeEmojiGlyph;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/**
 * 为 FontSetMixin 提供码位→自定义 BakedGlyph 的查找。
 * 由 MemeEmojiClient 在初始化时填充。
 */
public final class MemeEmojiClientAccess {
    private static final Int2ObjectOpenHashMap<MemeEmojiGlyph> GLYPHS = new Int2ObjectOpenHashMap<>();

    private MemeEmojiClientAccess() {
    }

    public static void put(int codepoint, MemeEmojiGlyph glyph) {
        GLYPHS.put(codepoint, glyph);
    }

    public static MemeEmojiGlyph getGlyph(int codepoint) {
        return GLYPHS.get(codepoint);
    }

    public static void clear() {
        GLYPHS.clear();
    }

    public static int size() {
        return GLYPHS.size();
    }
}
