package com.memeemoji.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

final class PickerButton extends AbstractButton {
    static final int TEXT = 0;
    static final int IMPORT = 1;
    static final int REFRESH = 2;
    static final int CLOSE = 3;
    private final int icon;
    private final BooleanSupplier selected;
    private final Runnable action;

    PickerButton(int x, int y, int w, String label, int icon, BooleanSupplier selected, Runnable action) {
        super(x, y, w, 20, Component.literal(label));
        this.icon = icon;
        this.selected = selected;
        this.action = action;
        if (icon != TEXT) setTooltip(Tooltip.create(Component.literal(label)));
    }

    @Override
    public void onPress() { action.run(); }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        int color = !active ? 0xFF65736D : selected.getAsBoolean() ? EmojiPickerScreen.ACCENT : EmojiPickerScreen.TEXT;
        int background = selected.getAsBoolean() ? 0xFF344C43 : isHoveredOrFocused() ? 0xFF35413D : 0xFF252E2B;
        graphics.fill(getX(), getY(), getRight(), getBottom(), background);
        if (isFocused()) graphics.renderOutline(getX(), getY(), getWidth(), getHeight(), EmojiPickerScreen.ACCENT);
        if (icon == TEXT) graphics.drawCenteredString(Minecraft.getInstance().font, getMessage(), getX() + width / 2, getY() + 6, color);
        else {
            int x = getX() + 5;
            int y = getY() + 5;
            if (icon == IMPORT) {
                graphics.fill(x + 4, y, x + 6, y + 8, color);
                graphics.fill(x + 1, y + 3, x + 9, y + 5, color);
                graphics.fill(x, y + 9, x + 10, y + 10, color);
            } else if (icon == REFRESH) {
                graphics.renderOutline(x + 1, y + 1, 8, 8, color);
                graphics.fill(x + 5, y, x + 10, y + 3, background);
                graphics.fill(x + 7, y, x + 9, y + 5, color);
                graphics.fill(x + 5, y + 3, x + 9, y + 5, color);
            } else {
                for (int i = 0; i < 9; i++) {
                    graphics.fill(x + i, y + i, x + i + 1, y + i + 1, color);
                    graphics.fill(x + 8 - i, y + i, x + 9 - i, y + i + 1, color);
                }
            }
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
}
