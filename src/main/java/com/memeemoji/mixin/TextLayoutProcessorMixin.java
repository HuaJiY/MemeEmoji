package com.memeemoji.mixin;

import com.memeemoji.ShapingScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "icyllis.modernui.mc.text.TextLayoutProcessor")
public class TextLayoutProcessorMixin {

    @Inject(method = "createVanillaLayout", at = @At("HEAD"))
    private void memeemoji$beforeVanillaLayout(String text, net.minecraft.network.chat.Style style,
                                                int resLevel, int computeFlags,
                                                CallbackInfoReturnable<?> cir) {
        ShapingScope.setActive(false);
    }

    @Inject(method = "createVanillaLayout", at = @At("RETURN"))
    private void memeemoji$afterVanillaLayout(String text, net.minecraft.network.chat.Style style,
                                               int resLevel, int computeFlags,
                                               CallbackInfoReturnable<?> cir) {
        ShapingScope.setActive(true);
    }
}