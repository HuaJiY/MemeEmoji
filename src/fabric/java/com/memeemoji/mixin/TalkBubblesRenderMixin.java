package com.memeemoji.mixin;

import com.memeemoji.compat.TalkBubblesCompat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Pseudo
@Mixin(targets = "net.talkbubbles.util.RenderBubble", remap = false)
public abstract class TalkBubblesRenderMixin {
    @Inject(method = "renderBubble", at = @At("HEAD"), cancellable = true, remap = false)
    private static void memeemoji$render(PoseStack pose, MultiBufferSource buffers, Font font,
                                         EntityRenderDispatcher dispatcher, List<String> text,
                                         int width, int height, float playerHeight, int light, CallbackInfo ci) {
        if (TalkBubblesCompat.render(pose, buffers, font, dispatcher, text, playerHeight, light)) ci.cancel();
    }
}
