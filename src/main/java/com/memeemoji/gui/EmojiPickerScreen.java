package com.memeemoji.gui;

import com.memeemoji.EmojiRegistry;
import com.memeemoji.EmojiTile;
import com.memeemoji.MemeEmoji;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.function.Consumer;

/**
 * 表情选择界面，打开后显示所有已加载的表情供点击。
 * 点击就把表情名交给回调（由调用方处理导航），按 ESC 关闭。
 */
public final class EmojiPickerScreen extends Screen {

    private static final int GRID_COLS = 8;
    private static final int CELL_SIZE = 38;

    private final Consumer<String> callback;
    private final List<EmojiTile> tiles;
    private final int gridWidth;
    private int scrollOffset;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    public EmojiPickerScreen(Consumer<String> callback) {
        super(Component.literal("\u8868\u60C5\u9009\u62E9"));
        this.callback = callback;
        this.tiles = EmojiRegistry.INSTANCE.tiles();
        this.gridWidth = GRID_COLS * CELL_SIZE;
    }

    @Override
    protected void init() {
        int maxW = Math.min(width - 40, 400);
        int maxH = Math.min(height - 40, 400);
        int rows = Math.max(1, (tiles.size() + GRID_COLS - 1) / GRID_COLS);
        int gridH = rows * CELL_SIZE;
        panelW = Math.max(maxW, gridWidth + 20);
        panelH = Math.min(maxH, Math.max(80, gridH + 40));
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
    }

    @Override
    public void render(GuiGraphics gr, int mouseX, int mouseY, float delta) {
        renderBackground(gr, mouseX, mouseY, delta);

        // 半透明面板
        gr.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xC0101010);
        gr.renderOutline(panelX, panelY, panelW, panelH, 0xFF555555);

        if (tiles.isEmpty()) {
            gr.drawCenteredString(font, Component.literal("\u6CA1\u6709\u52A0\u8F7D\u8868\u60C5"),
                    width / 2, panelY + panelH / 2 - font.lineHeight / 2, 0x888888);
            super.render(gr, mouseX, mouseY, delta);
            return;
        }

        int rows = (tiles.size() + GRID_COLS - 1) / GRID_COLS;
        int gridH = rows * CELL_SIZE;
        int contentH = panelH - 20;
        int maxScroll = Math.max(0, gridH - contentH);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        int startX = panelX + (panelW - gridWidth) / 2;
        int startY = panelY + 10;

        Style emojiStyle = Style.EMPTY.withFont(MemeEmoji.EMOJI_FONT);

        for (int i = 0; i < tiles.size(); i++) {
            int col = i % GRID_COLS;
            int row = i / GRID_COLS;
            int cellX = startX + col * CELL_SIZE;
            int cellY = startY + row * CELL_SIZE - scrollOffset;

            if (cellY + CELL_SIZE < panelY + 10 || cellY > panelY + panelH - 10) {
                continue;
            }

            boolean hovered = mouseX >= cellX && mouseX < cellX + CELL_SIZE
                    && mouseY >= cellY && mouseY < cellY + CELL_SIZE;

            if (hovered) {
                gr.fill(cellX, cellY, cellX + CELL_SIZE, cellY + CELL_SIZE, 0x40FFFFFF);
            }

            // 用字体渲染表情码位
            int codepoint = MemeEmoji.PUA_BASE + i;
            String emojiStr = new String(Character.toChars(codepoint));
            FormattedCharSequence seq = FormattedCharSequence.forward(emojiStr, emojiStyle);
            int emojiW = font.width(emojiStr);
            int drawX = cellX + (CELL_SIZE - emojiW) / 2;
            int drawY = cellY + (CELL_SIZE - font.lineHeight) / 2 + 1;
            gr.drawString(font, seq, drawX, drawY, 0xFFFFFFFF, false);
        }

        super.render(gr, mouseX, mouseY, delta);

        // 悬停 tooltip
        if (panelY + 10 <= mouseY && mouseY <= panelY + panelH - 10) {
            int relX = mouseX - startX;
            int relY = mouseY - startY + scrollOffset;
            if (relX >= 0 && relY >= 0) {
                int col = relX / CELL_SIZE;
                int row = relY / CELL_SIZE;
                int idx = row * GRID_COLS + col;
                if (col < GRID_COLS && idx >= 0 && idx < tiles.size()) {
                    EmojiTile tile = tiles.get(idx);
                    gr.renderTooltip(font, Component.literal(":" + tile.name() + ":"), mouseX, mouseY);
                }
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (mouseX >= panelX && mouseX <= panelX + panelW && mouseY >= panelY && mouseY <= panelY + panelH) {
            int rows = (tiles.size() + GRID_COLS - 1) / GRID_COLS;
            int gridH = rows * CELL_SIZE;
            int contentH = panelH - 20;
            if (gridH > contentH) {
                scrollOffset = (int) Math.max(0, Math.min(scrollOffset - deltaY * CELL_SIZE, gridH - contentH));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= panelX && mouseX <= panelX + panelW
                && mouseY >= panelY && mouseY <= panelY + panelH) {
            int startX = panelX + (panelW - gridWidth) / 2;
            int relX = (int) mouseX - startX;
            int relY = (int) mouseY - panelY - 10 + scrollOffset;
            if (relX >= 0 && relY >= 0) {
                int col = relX / CELL_SIZE;
                int row = relY / CELL_SIZE;
                int idx = row * GRID_COLS + col;
                if (col < GRID_COLS && idx >= 0 && idx < tiles.size()) {
                    callback.accept(tiles.get(idx).name());
                    return true; // 回调自己处理屏幕导航
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
