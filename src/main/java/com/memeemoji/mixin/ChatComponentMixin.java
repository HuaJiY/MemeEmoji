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
        // 使用 lineHeight（cell+3），让背景框在 emoji 底部多 3px 往下拓展
        int needed = Math.max(original, MemeEmoji.lineHeight());
        cir.setReturnValue(needed);
    }
}
