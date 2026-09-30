package com.memeemoji.mixin;

import com.memeemoji.gui.EmojiPickerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatScreen.class)
public class ChatScreenMixin {

    @Shadow
    protected EditBox input;

    @Unique
    private String memeemoji$pendingInsert;

    @Unique
    private int memeemoji$btnX() {
        return input.getX() + input.getWidth() + 2;
    }

    @Unique
    private int memeemoji$btnY() {
        return input.getY();
    }

    @Unique
    private int memeemoji$btnW() {
        return 20;
    }

    @Unique
    private int memeemoji$btnH() {
        return 12;
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void memeemoji$onInit(CallbackInfo ci) {
        // 缩窄输入框腾出按钮位置
        input.setWidth(input.getWidth() - 22);

        // 从选择界面返回后插入表情文本
        if (memeemoji$pendingInsert != null) {
            String text = input.getValue();
            int cursor = input.getCursorPosition();
            String before = text.substring(0, cursor);
            String after = text.substring(cursor);
            input.setValue(before + memeemoji$pendingInsert + after);
            input.setCursorPosition(cursor + memeemoji$pendingInsert.length());
            memeemoji$pendingInsert = null;
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void memeemoji$onRender(GuiGraphics gr, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        int x = memeemoji$btnX();
        int y = memeemoji$btnY();
        boolean hovered = mouseX >= x && mouseX < x + memeemoji$btnW()
                && mouseY >= y && mouseY < y + memeemoji$btnH();
        // 按钮背景
        gr.fill(x, y, x + memeemoji$btnW(), y + memeemoji$btnH(), hovered ? 0xFF555555 : 0xFF333333);
        // 表情符号文本
        Font font = Minecraft.getInstance().font;
        gr.drawString(font, "\uD83D\uDE0A", x + 3, y + 2, 0xFFFFFF, false);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void memeemoji$onMouseClicked(double mouseX, double mouseY, int button,
                                          CallbackInfoReturnable<Boolean> cir) {
        int x = memeemoji$btnX();
        int y = memeemoji$btnY();
        if (button == 0 && mouseX >= x && mouseX < x + memeemoji$btnW()
                && mouseY >= y && mouseY < y + memeemoji$btnH()) {
            ChatScreen self = (ChatScreen) (Object) this;
            Minecraft.getInstance().setScreen(new EmojiPickerScreen(name -> {
                memeemoji$pendingInsert = ":" + name + ":";
                Minecraft.getInstance().setScreen(self);
            }));
            cir.setReturnValue(true);
        }
    }
}