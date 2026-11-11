package com.memeemoji.gui;

import com.memeemoji.MemeEmoji;
import com.memeemoji.media.EmojiAsset;
import com.memeemoji.media.EmojiLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

final class EmojiRenameScreen extends Screen {
    private final Screen parent;
    private final EmojiAsset asset;
    private final Consumer<EmojiAsset> saved;
    private EditBox name;
    private PickerButton save;
    private PickerButton cancel;
    private int left, top, panelWidth, panelHeight;
    private boolean saving;
    private String error = "";

    EmojiRenameScreen(Screen parent, EmojiAsset asset, Consumer<EmojiAsset> saved) {
        super(Component.literal("修改表情名称"));
        this.parent = parent;
        this.asset = asset;
        this.saved = saved;
    }

    @Override
    protected void init() {
        String value = name == null ? asset.name() : name.getValue();
        panelWidth = Math.min(320, width - 20);
        panelHeight = 148;
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        name = new EditBox(font, left + 12, top + 48, panelWidth - 24, 20, Component.literal("表情名称"));
        name.setMaxLength(128);
        name.setValue(value);
        name.setResponder(ignored -> error = "");
        name.setTextColor(GlassStyle.current().text);
        name.setEditable(!saving);
        addRenderableWidget(name);
        cancel = addRenderableWidget(new PickerButton(left + 12, top + panelHeight - 32, (panelWidth - 30) / 2,
                "取消", PickerButton.TEXT, () -> false, this::onClose));
        save = addRenderableWidget(new PickerButton(left + 18 + (panelWidth - 30) / 2, top + panelHeight - 32, (panelWidth - 30) / 2,
                "保存", PickerButton.TEXT, () -> false, this::save));
        save.active = cancel.active = !saving;
        setInitialFocus(name);
    }

    private void save() {
        if (saving) return;
        saving = true;
        error = "";
        save.active = cancel.active = false;
        name.setEditable(false);
        EmojiLibrary.rename(asset.id(), name.getValue()).whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> {
            saving = false;
            if (failure == null) {
                saved.accept(result);
                onClose();
            } else {
                Throwable cause = failure;
                while (cause.getCause() != null) cause = cause.getCause();
                error = cause.getMessage() == null ? "名称保存失败，请重试" : cause.getMessage();
                save.active = cancel.active = true;
                name.setEditable(true);
                setFocused(name);
            }
        }));
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { save(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override public void renderBackground(GuiGraphics graphics, int x, int y, float delta) {}

    @Override
    public void render(GuiGraphics graphics, int x, int y, float delta) {
        GlassStyle theme = GlassStyle.current();
        theme.background(graphics, width, height, delta);
        theme.panel(graphics, left, top, panelWidth, panelHeight);
        graphics.drawString(font, title, left + 12, top + 12, theme.text, false);
        graphics.drawString(font, "名称", left + 12, top + 32, theme.muted, false);
        Component message = Component.literal(error.isEmpty()
                ? "1–" + MemeEmoji.config().maxNameLength + " 字，不能含空格或冒号" : error);
        int row = top + 77;
        for (var line : font.split(message, panelWidth - 24)) {
            graphics.drawString(font, line, left + 12, row, error.isEmpty() ? theme.muted : 0xFFFFA7A7, false);
            row += 11;
        }
        super.render(graphics, x, y, delta);
    }

    @Override public void onClose() { if (!saving) minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
