package com.memeemoji.mixin;

import com.memeemoji.EmojiRegistry;
import com.memeemoji.registry.ShapingSink;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSink;
import net.minecraft.util.StringDecomposer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 所有走 FormattedCharSequence 的文本最终都会进这两个方法，聊天、告示牌、书、输入框都覆盖得到，
 * ModernUI 的字体引擎同样复用这条链路。
 */
@Mixin(StringDecomposer.class)
public abstract class StringDecomposerMixin {

    @Inject(method = "iterate", at = @At("HEAD"), cancellable = true)
    private static void memeemoji$iterate(String string, Style style, FormattedCharSink output, CallbackInfoReturnable<Boolean> cir) {
        if (output instanceof ShapingSink || !EmojiRegistry.INSTANCE.needsShaping(string)) {
            return;
        }
        ShapingSink sink = ShapingSink.acquire(output, EmojiRegistry.INSTANCE.shapingTable());
        try {
            boolean ok = StringDecomposer.iterate(string, style, sink);
            cir.setReturnValue(ok && sink.finish());
        } finally {
            sink.release();
        }
    }

    @Inject(method = "iterateFormatted(Ljava/lang/String;ILnet/minecraft/network/chat/Style;Lnet/minecraft/network/chat/Style;Lnet/minecraft/util/FormattedCharSink;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void memeemoji$iterateFormatted(String string, int offset, Style currentStyle, Style resetStyle,
                                                    FormattedCharSink output, CallbackInfoReturnable<Boolean> cir) {
        if (output instanceof ShapingSink || !EmojiRegistry.INSTANCE.needsShaping(string)) {
            return;
        }
        ShapingSink sink = ShapingSink.acquire(output, EmojiRegistry.INSTANCE.shapingTable());
        try {
            boolean ok = StringDecomposer.iterateFormatted(string, offset, currentStyle, resetStyle, sink);
            cir.setReturnValue(ok && sink.finish());
        } finally {
            sink.release();
        }
    }
}
