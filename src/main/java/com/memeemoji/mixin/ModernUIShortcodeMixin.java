package com.memeemoji.mixin;

import com.memeemoji.MemeEmoji;
import com.memeemoji.media.EmojiLibrary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "icyllis.modernui.mc.FontResourceManager", remap = false)
public class ModernUIShortcodeMixin {
    @Inject(method = "lookupEmojiShortcode", at = @At("HEAD"), cancellable = true)
    private void memeemoji$preferPersonal(String shortcode, CallbackInfoReturnable<String> cir) {
        if (MemeEmoji.config().enabled && shortcode.length() > 2
                && shortcode.charAt(0) == ':' && shortcode.charAt(shortcode.length() - 1) == ':'
                && EmojiLibrary.findName(shortcode.substring(1, shortcode.length() - 1)) != null) {
            cir.setReturnValue(null);
        }
    }
}
