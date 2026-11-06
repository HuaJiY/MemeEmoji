package com.memeemoji.mixin;

import com.memeemoji.chat.EmojiChatRenderer;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @Shadow @Final private List<GuiMessage> allMessages;
    @Shadow private int chatScrollbarPos;
    @Shadow private boolean newMessageSinceScroll;
    @Unique private boolean memeemoji$adding;
    @Unique private int memeemoji$savedScroll;

    @Unique
    private ChatComponent memeemoji$self() {
        return (ChatComponent) (Object) this;
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void memeemoji$render(GuiGraphics graphics, int tick, int mouseX, int mouseY, boolean focused, CallbackInfo ci) {
        if (EmojiChatRenderer.active(allMessages)) {
            chatScrollbarPos = EmojiChatRenderer.clampScroll(memeemoji$self(), allMessages, chatScrollbarPos);
            EmojiChatRenderer.render(memeemoji$self(), allMessages, chatScrollbarPos, newMessageSinceScroll,
                    graphics, tick, mouseX, mouseY, focused);
            ci.cancel();
        }
    }

    @Inject(method = "scrollChat", at = @At("HEAD"), cancellable = true)
    private void memeemoji$scroll(int amount, CallbackInfo ci) {
        if (memeemoji$adding) {
            ci.cancel();
            return;
        }
        if (EmojiChatRenderer.active(allMessages)) {
            chatScrollbarPos = EmojiChatRenderer.clampScroll(memeemoji$self(), allMessages, chatScrollbarPos + amount);
            if (chatScrollbarPos == 0) newMessageSinceScroll = false;
            ci.cancel();
        }
    }

    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V", at = @At("HEAD"))
    private void memeemoji$beforeMessage(Component content, MessageSignature signature, GuiMessageTag tag, CallbackInfo ci) {
        GuiMessage incoming = new GuiMessage(0, content, signature, tag);
        memeemoji$adding = memeemoji$self().isChatFocused() && chatScrollbarPos > 0
                && (EmojiChatRenderer.active(allMessages) || EmojiChatRenderer.active(List.of(incoming)));
        memeemoji$savedScroll = chatScrollbarPos;
    }

    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V", at = @At("RETURN"))
    private void memeemoji$afterMessage(Component content, MessageSignature signature, GuiMessageTag tag, CallbackInfo ci) {
        if (!memeemoji$adding) return;
        memeemoji$adding = false;
        int added = EmojiChatRenderer.messageLineCount(memeemoji$self(), allMessages.getFirst());
        chatScrollbarPos = EmojiChatRenderer.clampScroll(memeemoji$self(), allMessages, memeemoji$savedScroll + added);
        newMessageSinceScroll = true;
    }

    @Inject(method = "getLinesPerPage", at = @At("HEAD"), cancellable = true)
    private void memeemoji$pageSize(CallbackInfoReturnable<Integer> cir) {
        if (EmojiChatRenderer.active(allMessages)) {
            cir.setReturnValue(EmojiChatRenderer.visibleCount(memeemoji$self(), allMessages, chatScrollbarPos));
        }
    }

    @Inject(method = "getClickedComponentStyleAt", at = @At("HEAD"), cancellable = true)
    private void memeemoji$style(double x, double y, CallbackInfoReturnable<Style> cir) {
        if (EmojiChatRenderer.active(allMessages)) {
            cir.setReturnValue(EmojiChatRenderer.styleAt(memeemoji$self(), allMessages, chatScrollbarPos, x, y));
        }
    }

    @Inject(method = "getMessageTagAt", at = @At("HEAD"), cancellable = true)
    private void memeemoji$tag(double x, double y, CallbackInfoReturnable<GuiMessageTag> cir) {
        if (EmojiChatRenderer.active(allMessages)) {
            cir.setReturnValue(EmojiChatRenderer.tagAt(memeemoji$self(), allMessages, chatScrollbarPos, x, y));
        }
    }
}
