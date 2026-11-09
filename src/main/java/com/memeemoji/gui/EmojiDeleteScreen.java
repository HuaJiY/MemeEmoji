package com.memeemoji.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class EmojiDeleteScreen extends Screen {
    private final Screen parent;
    private final int count;
    private final Runnable confirm;
    private int left, top, panelWidth, panelHeight;

    EmojiDeleteScreen(Screen parent, int count, Runnable confirm) {
        super(Component.literal("删除 " + count + " 个表情？"));
        this.parent = parent;
        this.count = count;
        this.confirm = confirm;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(300, width - 20);
        panelHeight = 64 + font.split(Component.literal("将永久删除所选的 " + count + " 个本地原文件，无法撤销。已发送的聊天图片保留缓存。"), panelWidth - 24).size() * 12;
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        addRenderableWidget(new PickerButton(left + 12, top + panelHeight - 30, (panelWidth - 30) / 2,
                "取消", PickerButton.TEXT, () -> false, this::onClose));
        addRenderableWidget(new PickerButton(left + 18 + (panelWidth - 30) / 2, top + panelHeight - 30, (panelWidth - 30) / 2,
                "永久删除", PickerButton.TEXT, () -> false, () -> { onClose(); confirm.run(); }));
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int x, int y, float delta) {}

    @Override
    public void render(GuiGraphics graphics, int x, int y, float delta) {
        GlassStyle theme = GlassStyle.current();
        theme.background(graphics, width, height, delta);
        theme.panel(graphics, left, top, panelWidth, panelHeight);
        graphics.drawString(font, title, left + 12, top + 12, theme.text, false);
        int row = top + 30;
        for (var line : font.split(Component.literal("将永久删除所选的 " + count + " 个本地原文件，无法撤销。已发送的聊天图片保留缓存。"), panelWidth - 24)) {
            graphics.drawString(font, line, left + 12, row, theme.muted, false);
            row += 12;
        }
        super.render(graphics, x, y, delta);
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
