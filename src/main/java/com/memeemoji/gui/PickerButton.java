package com.memeemoji.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class PickerButton extends AbstractButton {
    static final int TEXT = 0;
    static final int IMPORT = 1;
    static final int REFRESH = 2;
    static final int CLOSE = 3;
    static final int SETTINGS = 4;
    static final int MANAGE = 5;
    static final int DELETE = 6;
    static final int RENAME = 7;
    private final int icon;
    private final BooleanSupplier selected;
    private final Runnable action;
    private final Supplier<GlassStyle> style;

    PickerButton(int x, int y, int w, String label, int icon, BooleanSupplier selected, Runnable action) {
        this(x, y, w, label, icon, selected, action, GlassStyle::current);
    }

    PickerButton(int x, int y, int w, String label, int icon, BooleanSupplier selected, Runnable action, Supplier<GlassStyle> style) {
        super(x, y, w, 20, Component.literal(label));
        this.icon = icon;
        this.selected = selected;
        this.action = action;
        this.style = style;
        if (icon != TEXT) setTooltip(Tooltip.create(Component.literal(label)));
    }

    @Override
    public void onPress() { action.run(); }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        GlassStyle theme = style.get();
        int color = !active ? theme.muted : theme.text;
        int background = selected.getAsBoolean() ? theme.selected : isHoveredOrFocused() ? theme.hover : theme.control;
        GlassStyle.rounded(graphics, getX(), getY(), getWidth(), getHeight(), 4, GlassStyle.alpha(background, 210));
        if (selected.getAsBoolean()) graphics.fill(getX() + 6, getBottom() - 2, getRight() - 6, getBottom() - 1, theme.accent);
        if (isFocused()) graphics.renderOutline(getX(), getY(), getWidth(), getHeight(), theme.accent);
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
            } else if (icon == SETTINGS) {
                graphics.renderOutline(x + 2, y + 2, 6, 6, color);
                graphics.fill(x + 4, y, x + 6, y + 3, color);
                graphics.fill(x + 4, y + 7, x + 6, y + 10, color);
                graphics.fill(x, y + 4, x + 3, y + 6, color);
                graphics.fill(x + 7, y + 4, x + 10, y + 6, color);
                graphics.fill(x + 4, y + 4, x + 6, y + 6, color);
            } else if (icon == MANAGE) {
                graphics.renderOutline(x, y, 10, 10, color);
                graphics.fill(x + 2, y + 4, x + 4, y + 6, color);
                graphics.fill(x + 4, y + 6, x + 6, y + 8, color);
                graphics.fill(x + 6, y + 2, x + 8, y + 6, color);
            } else if (icon == DELETE) {
                graphics.fill(x + 3, y, x + 7, y + 1, color);
                graphics.fill(x, y + 2, x + 10, y + 3, color);
                graphics.renderOutline(x + 2, y + 4, 6, 6, color);
            } else if (icon == RENAME) {
                for (int i = 0; i < 7; i++) graphics.fill(x + 1 + i, y + 7 - i, x + 3 + i, y + 9 - i, color);
                graphics.fill(x, y + 9, x + 3, y + 10, color);
                graphics.fill(x + 7, y, x + 10, y + 2, color);
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
