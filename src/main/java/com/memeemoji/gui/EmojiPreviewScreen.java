package com.memeemoji.gui;

import com.memeemoji.media.EmojiAsset;
import com.memeemoji.media.EmojiTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class EmojiPreviewScreen extends Screen {
    private final Screen parent;
    private final EmojiAsset asset;

    public EmojiPreviewScreen(Screen parent, EmojiAsset asset) {
        super(Component.literal(asset.name()));
        this.parent = parent;
        this.asset = asset;
    }

    @Override
    protected void init() {
        addRenderableWidget(new PickerButton(width - 30, 10, 20, "关闭", PickerButton.CLOSE, () -> false, this::onClose));
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xEF141919);
        graphics.drawString(font, font.plainSubstrByWidth(asset.name(), Math.max(1, width - 56)), 12, 16, EmojiPickerScreen.TEXT, false);
        int availableWidth = Math.max(1, width - 32);
        int availableHeight = Math.max(1, height - 72);
        double scale = Math.min((double) availableWidth / asset.width(), (double) availableHeight / asset.height());
        int imageWidth = Math.max(1, (int) Math.round(asset.width() * scale));
        int imageHeight = Math.max(1, (int) Math.round(asset.height() * scale));
        int x = (width - imageWidth) / 2;
        int y = 40 + (availableHeight - imageHeight) / 2;
        EmojiTextures.render(graphics, asset, x, y, imageWidth, imageHeight, 255);
        String failure = EmojiTextures.failure(asset.id());
        String dimensions = failure == null ? asset.width() + " × " + asset.height() + (asset.animated() ? " · 动图" : "") : failure;
        graphics.drawCenteredString(font, font.plainSubstrByWidth(dimensions, Math.max(1, width - 24)), width / 2, height - 18,
                failure == null ? EmojiPickerScreen.MUTED : 0xFFFFA7A7);
        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() { Minecraft.getInstance().setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}
