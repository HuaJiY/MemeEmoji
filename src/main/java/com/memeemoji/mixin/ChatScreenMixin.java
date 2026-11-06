package com.memeemoji.mixin;

import com.memeemoji.gui.EmojiPickerScreen;
import com.memeemoji.gui.EmojiPreviewScreen;
import com.memeemoji.chat.EmojiChatRenderer;
import com.memeemoji.media.EmojiAsset;
import com.memeemoji.net.EmojiNetworkClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatScreen.class)
public class ChatScreenMixin {
    @Shadow protected EditBox input;
    @Unique private String memeemoji$draft;
    @Unique private String memeemoji$pendingInsert;
    @Unique private int memeemoji$cursor;

    @Inject(method = "init", at = @At("TAIL"))
    private void memeemoji$onInit(CallbackInfo ci) {
        input.setWidth(Math.max(20, input.getWidth() - 22));
        if (memeemoji$draft != null) {
            input.setValue(memeemoji$draft);
            input.setCursorPosition(memeemoji$cursor);
            input.setHighlightPos(memeemoji$cursor);
            if (memeemoji$pendingInsert != null) input.insertText(memeemoji$pendingInsert);
            memeemoji$draft = null;
            memeemoji$pendingInsert = null;
        }
    }

    @Unique
    private int memeemoji$buttonX() { return input.getX() + input.getWidth() + 2; }

    @Inject(method = "render", at = @At("TAIL"))
    private void memeemoji$onRender(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        int x = memeemoji$buttonX();
        int y = input.getY();
        boolean hover = mouseX >= x && mouseX < x + 20 && mouseY >= y && mouseY < y + 12;
        graphics.fill(x, y, x + 20, y + 12, hover ? 0xFF385247 : 0xFF293530);
        int color = hover ? 0xFF9AE8D0 : 0xFFC5D8CF;
        graphics.renderOutline(x + 5, y + 1, 10, 10, color);
        graphics.fill(x + 7, y + 4, x + 8, y + 5, color);
        graphics.fill(x + 12, y + 4, x + 13, y + 5, color);
        graphics.fill(x + 8, y + 7, x + 12, y + 8, color);
        if (hover) graphics.renderTooltip(Minecraft.getInstance().font, Component.literal("表情库"), mouseX, mouseY);
    }

    @Unique
    private void memeemoji$openPicker() {
        ChatScreen self = (ChatScreen) (Object) this;
        memeemoji$saveDraft();
        Minecraft.getInstance().setScreen(new EmojiPickerScreen(self, token -> {
            memeemoji$pendingInsert = token;
            Minecraft.getInstance().setScreen(self);
        }));
    }

    @Unique
    private void memeemoji$saveDraft() {
        memeemoji$draft = input.getValue();
        memeemoji$cursor = input.getCursorPosition();
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void memeemoji$onMouseClicked(double x, double y, int button, CallbackInfoReturnable<Boolean> cir) {
        if (button == 0 && x >= memeemoji$buttonX() && x < memeemoji$buttonX() + 20
                && y >= input.getY() && y < input.getY() + 12) {
            memeemoji$openPicker();
            cir.setReturnValue(true);
            return;
        }
        EmojiAsset asset = button == 0 ? EmojiChatRenderer.assetAt(x, y) : null;
        if (asset != null) {
            memeemoji$saveDraft();
            Minecraft.getInstance().setScreen(new EmojiPreviewScreen((ChatScreen) (Object) this, asset));
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void memeemoji$onKeyPressed(int key, int scan, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (key == GLFW.GLFW_KEY_E && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            memeemoji$openPicker();
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "handleChatInput", at = @At("HEAD"), cancellable = true)
    private void memeemoji$onChatInput(String text, boolean addToHistory, CallbackInfo ci) {
        String normalized = ((ChatScreen) (Object) this).normalizeChatMessage(text);
        if (!normalized.isEmpty() && !normalized.startsWith("/") && EmojiNetworkClient.send(normalized)) {
            if (addToHistory) Minecraft.getInstance().gui.getChat().addRecentChat(normalized);
            ci.cancel();
        }
    }
}
