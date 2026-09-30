package com.memeemoji.mixin;

import com.memeemoji.MemeEmoji;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatComponent.class)
public class ChatComponentMixin {

    @Inject(method = "getLineHeight", at = @At("RETURN"), cancellable = true)
    private void memeemoji(CallbackInfoReturnable<Integer> cir) {
        int original = cir.getReturnValueI();
        // 如果不比 emoji 格大，就撑到 emoji 那么大，让背景框能包住图片
        int needed = Math.max(original, MemeEmoji.glyphHeight());
        cir.setReturnValue(needed);
    }
}
