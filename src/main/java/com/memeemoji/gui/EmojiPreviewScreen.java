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
    private PickerButton collect;
    private boolean collecting;
    private boolean collected;
    private String status = "";

    public EmojiPreviewScreen(Screen parent, EmojiAsset asset) {
        super(Component.literal(asset.name()));
        this.parent = parent;
        this.asset = asset;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(760, width - 16);
        int left = (width - panelWidth) / 2;
        addRenderableWidget(new PickerButton(left + panelWidth - 30, 18, 20, "关闭", PickerButton.CLOSE, () -> false, this::onClose));
        collect = addRenderableWidget(new PickerButton(left + panelWidth - 138, height - 40, 126,
                collected ? "已收藏到表情库" : "收藏到表情库", PickerButton.TEXT, () -> collected, this::collect));
        collect.active = !collecting && !collected;
    }

    private void collect() {
        if (collecting || collected) return;
        collecting = true;
        collect.active = false;
        collect.setMessage(Component.literal("正在收藏…"));
        EmojiCollection.collect(asset.id()).whenComplete((saved, failure) -> Minecraft.getInstance().execute(() -> {
            collecting = false;
            collected = failure == null;
            collect.active = !collected;
            collect.setMessage(Component.literal(collected ? "已收藏到表情库" : "重试收藏"));
            if (failure != null) {
                Throwable cause = failure;
                while (cause.getCause() != null) cause = cause.getCause();
                status = "收藏失败：" + cause.getMessage();
            } else status = "已保存为 " + saved.name();
        }));
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        GlassStyle theme = GlassStyle.current();
        theme.background(graphics, width, height, delta);
        int panelWidth = Math.min(760, width - 16);
        int left = (width - panelWidth) / 2;
        theme.panel(graphics, left, 8, panelWidth, height - 16);
        graphics.drawString(font, font.plainSubstrByWidth(asset.name(), Math.max(1, panelWidth - 60)), left + 12, 24, theme.text, false);
        int availableWidth = Math.max(1, panelWidth - 32);
        int availableHeight = Math.max(1, height - 108);
        double scale = Math.min((double) availableWidth / asset.width(), (double) availableHeight / asset.height());
        int imageWidth = Math.max(1, (int) Math.round(asset.width() * scale));
        int imageHeight = Math.max(1, (int) Math.round(asset.height() * scale));
        int x = (width - imageWidth) / 2;
        int y = 46 + (availableHeight - imageHeight) / 2;
        EmojiTextures.render(graphics, asset, x, y, imageWidth, imageHeight, 255);
        String failure = EmojiTextures.failure(asset.id());
        String dimensions = failure == null ? asset.width() + " × " + asset.height() + (asset.animated() ? " · 动图" : "") : failure;
        graphics.drawString(font, font.plainSubstrByWidth(dimensions, Math.max(1, panelWidth - 160)), left + 12, height - 34,
                failure == null ? theme.muted : 0xFFFFA7A7, false);
        if (!status.isEmpty()) graphics.drawString(font, font.plainSubstrByWidth(status, panelWidth - 24),
                left + 12, height - 53, collected ? theme.text : 0xFFFFA7A7, false);
        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() { Minecraft.getInstance().setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}
