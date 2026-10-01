package com.memeemoji.mixin;

import com.memeemoji.glyph.MemeEmojiClientAccess;
import com.memeemoji.glyph.MemeEmojiGlyph;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ModernUI 的 StandardFontSet.getGlyph() 不走 FontSet.getGlyph()，
 * 需要通过这个 mixin 拦截并返回我们的自定义 BakedGlyph，
 * 使表情使用直接纹理渲染而非 ModernUI 的位图图集，大幅提升清晰度。
 */
@Pseudo
@Mixin(targets = "icyllis.modernui.mc.text.StandardFontSet")
public class StandardFontSetMixin {

    @Inject(method = "getGlyph(I)Lnet/minecraft/client/gui/font/glyphs/BakedGlyph;",
            at = @At("HEAD"), cancellable = true)
    private void memeemoji(int codepoint, CallbackInfoReturnable<BakedGlyph> cir) {
        MemeEmojiGlyph custom = MemeEmojiClientAccess.getGlyph(codepoint);
        if (custom != null) {
            cir.setReturnValue(custom);
        }
    }
}