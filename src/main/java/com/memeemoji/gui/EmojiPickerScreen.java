package com.memeemoji.gui;

import com.memeemoji.EmojiRegistry;
import com.memeemoji.EmojiTile;
import com.memeemoji.ImageTiles;
import com.memeemoji.MemeEmoji;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * 表情选择界面，打开后显示所有已加载的表情供点击。
 * 使用 scaleToFill 把每个不同尺寸的表情缩放到统一格子大小显示。
 */
public final class EmojiPickerScreen extends Screen {

    private static final int GRID_COLS = 8;

    private final Consumer<String> callback;
    private final List<EmojiTile> tiles;
    private final int cellSize;
    private final int gridWidth;
    private int scrollOffset;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    private DynamicTexture atlasTexture;
    private ResourceLocation atlasLocation;
    private int atlasW;
    private int atlasH;
    private int atlasRows;

    public EmojiPickerScreen(Consumer<String> callback) {
        super(Component.literal("表情选择"));
        this.callback = callback;
        this.tiles = EmojiRegistry.INSTANCE.tiles();
        this.cellSize = MemeEmoji.pickerCell();
        this.gridWidth = GRID_COLS * cellSize;
    }

    @Override
    protected void init() {
        if (atlasTexture != null) {
            atlasTexture.close();
            atlasTexture = null;
        }

        int maxW = Math.min(width - 40, 400);
        int maxH = Math.min(height - 40, 400);
        atlasRows = Math.max(1, (tiles.size() + GRID_COLS - 1) / GRID_COLS);
        int gridH = atlasRows * cellSize;
        panelW = Math.max(maxW, gridWidth + 20);
        panelH = Math.min(maxH, Math.max(80, gridH + 40));
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        buildAtlas();
    }

    /** 把所有表情缩放到 pickerCell 大小，拼成一张纹理图集。 */
    private void buildAtlas() {
        if (tiles.isEmpty()) return;
        atlasW = GRID_COLS * cellSize;
        atlasH = atlasRows * cellSize;

        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, atlasW, atlasH, false);

        for (int i = 0; i < tiles.size(); i++) {
            try {
                BufferedImage img = ImageTiles.read(tiles.get(i).png());
                // 缩放到 pickerCell 大小，bicubic 保证清晰度
                img = ImageTiles.scaleToFill(img, cellSize);
                int col = i % GRID_COLS;
                int row = i / GRID_COLS;
                int baseX = col * cellSize;
                int baseY = row * cellSize;

                for (int y = 0; y < cellSize; y++) {
                    for (int x = 0; x < cellSize; x++) {
                        int argb = img.getRGB(x, y);
                        int a = (argb >> 24) & 0xFF;
                        int r = (argb >> 16) & 0xFF;
                        int g = (argb >> 8) & 0xFF;
                        int b = argb & 0xFF;
                        int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                        nativeImage.setPixelRGBA(baseX + x, baseY + y, abgr);
                    }
                }
            } catch (IOException e) {
                MemeEmoji.LOGGER.warn("Picker: 无法加载表情 {}: {}", tiles.get(i).name(), e.getMessage());
            }
        }

        atlasTexture = new DynamicTexture(nativeImage);
        atlasLocation = ResourceLocation.fromNamespaceAndPath(MemeEmoji.MOD_ID, "picker_atlas");
        Minecraft.getInstance().getTextureManager().register(atlasLocation, atlasTexture);
    }

    @Override
    public void render(GuiGraphics gr, int mouseX, int mouseY, float delta) {
        renderBackground(gr, mouseX, mouseY, delta);
        gr.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xC0101010);
        gr.renderOutline(panelX, panelY, panelW, panelH, 0xFF555555);

        if (tiles.isEmpty()) {
            gr.drawCenteredString(font, Component.literal("没有加载表情"),
                    width / 2, panelY + panelH / 2 - font.lineHeight / 2, 0x888888);
            super.render(gr, mouseX, mouseY, delta);
            return;
        }

        int gridH = atlasRows * cellSize;
        int contentH = panelH - 20;
        int maxScroll = Math.max(0, gridH - contentH);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        int startX = panelX + (panelW - gridWidth) / 2;
        int startY = panelY + 10;

        for (int i = 0; i < tiles.size(); i++) {
            int col = i % GRID_COLS;
            int row = i / GRID_COLS;
            int cellX = startX + col * cellSize;
            int cellY = startY + row * cellSize - scrollOffset;

            if (cellY + cellSize < panelY + 10 || cellY > panelY + panelH - 10) {
                continue;
            }

            boolean hovered = mouseX >= cellX && mouseX < cellX + cellSize
                    && mouseY >= cellY && mouseY < cellY + cellSize;

            gr.blit(atlasLocation, cellX, cellY, cellSize, cellSize,
                    col * cellSize, row * cellSize, cellSize, cellSize, atlasW, atlasH);

            if (hovered) {
                gr.fill(cellX, cellY, cellX + cellSize, cellY + cellSize, 0x40FFFFFF);
            }
        }

        super.render(gr, mouseX, mouseY, delta);

        if (panelY + 10 <= mouseY && mouseY <= panelY + panelH - 10) {
            int relX = mouseX - startX;
            int relY = mouseY - startY + scrollOffset;
            if (relX >= 0 && relY >= 0) {
                int col = relX / cellSize;
                int row = relY / cellSize;
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
            int gridH = atlasRows * cellSize;
            int contentH = panelH - 20;
            if (gridH > contentH) {
                scrollOffset = (int) Math.max(0, Math.min(scrollOffset - deltaY * cellSize, gridH - contentH));
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
                int col = relX / cellSize;
                int row = relY / cellSize;
                int idx = row * GRID_COLS + col;
                if (col < GRID_COLS && idx >= 0 && idx < tiles.size()) {
                    callback.accept(tiles.get(idx).name());
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        if (atlasTexture != null) {
            atlasTexture.close();
            atlasTexture = null;
        }
        super.removed();
    }
}
