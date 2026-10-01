package com.memeemoji.glyph;

import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.SheetGlyphInfo;
import net.minecraft.client.gui.font.GlyphRenderTypes;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Function;

/**
 * 自定义 BakedGlyph，直接引用原始纹理渲染，绕过 ModernUI 的位图图集管道。
 * 与 Twemoji 的 StaticBakedGlyph 思路相同。
 */
public class MemeEmojiGlyph extends BakedGlyph {

    private final int width;
    private final int height;
    private final GlyphInfo info;

    public MemeEmojiGlyph(ResourceLocation texture, int width, int height) {
        super(
                GlyphRenderTypes.createForColorTexture(texture),
                0.0F, 1.0F, 0.0F, 1.0F,
                0.0F, width,
                height, 0.0F
        );
        this.width = width;
        this.height = height;
        this.info = new GlyphInfo() {
            @Override
            public float getAdvance() {
                return width;
            }

            @Override
            public float getShadowOffset() {
                return 0.0F;
            }

            @Override
            public float getBoldOffset() {
                return 0.0F;
            }

            @Override
            public BakedGlyph bake(Function<SheetGlyphInfo, BakedGlyph> function) {
                return MemeEmojiGlyph.this;
            }
        };
    }

    public GlyphInfo info() {
        return info;
    }

    public int glyphWidth() {
        return width;
    }

    public int glyphHeight() {
        return height;
    }
}
