package com.memeemoji.mixin;

import com.memeemoji.EmojiRegistry;
import com.memeemoji.glyph.MemeEmojiGlyph;
import com.memeemoji.glyph.MemeEmojiClientAccess;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在 FontSet.getGlyph() 返回后替换成我们的自定义 BakedGlyph，
 * 使表情使用直接纹理渲染而非 ModernUI 的位图图集，大幅提升清晰度。
 */
@Mixin(FontSet.class)
public class FontSetMixin {

    @Inject(method = "getGlyph(I)Lnet/minecraft/client/gui/font/glyphs/BakedGlyph;",
            at = @At("RETURN"), cancellable = true)
    private void memeemoji$customGlyph(int codepoint, CallbackInfoReturnable<BakedGlyph> cir) {
        if (!EmojiRegistry.INSTANCE.rendersInEmojiFont(codepoint)) {
            return;
        }
        MemeEmojiGlyph custom = MemeEmojiClientAccess.getGlyph(codepoint);
        if (custom != null) {
            cir.setReturnValue(custom);
        }
    }
}
