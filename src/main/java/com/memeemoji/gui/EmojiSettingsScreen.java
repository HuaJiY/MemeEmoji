package com.memeemoji.gui;

import com.memeemoji.MemeEmoji;
import com.memeemoji.MemeEmojiConfig;
import com.memeemoji.chat.EmojiChatRenderer;
import com.memeemoji.media.EmojiAsset;
import com.memeemoji.media.EmojiLibrary;
import com.memeemoji.media.EmojiTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public final class EmojiSettingsScreen extends Screen {
    private final Screen parent;
    private MemeEmojiConfig draft = MemeEmoji.config().copy();
    private final Map<String, String> raw = new HashMap<>();
    private final Map<String, String> errors = new LinkedHashMap<>();
    private final List<Row> rows = new ArrayList<>();
    private int tab;
    private int scroll;
    private int left, top, panelWidth, panelHeight, bodyTop, bodyBottom;
    private PickerButton save;
    private String status = "";
    private final EmojiAsset previewAsset;

    public EmojiSettingsScreen(Screen parent) {
        super(Component.literal("表情设置"));
        this.parent = parent;
        previewAsset = PickerPreferences.load().recent().stream().map(EmojiLibrary::personalCopy)
                .filter(java.util.Objects::nonNull).findFirst().orElseGet(() -> EmojiLibrary.local().stream().findFirst().orElse(null));
    }

    private GlassStyle theme() { return new GlassStyle(draft); }

    @Override
    protected void init() {
        panelWidth = Math.min(560, width - 16);
        panelHeight = Math.min(390, height - 16);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        bodyTop = top + 70;
        bodyBottom = top + panelHeight - 112;
        rows.clear();
        button(left + panelWidth - 32, top + 10, 20, "取消", PickerButton.CLOSE, () -> false, this::onClose);
        String[] tabs = {"显示", "外观", "高级"};
        for (int i = 0; i < tabs.length; i++) {
            int index = i;
            button(left + 12 + i * 56, top + 38, 52, tabs[i], PickerButton.TEXT, () -> tab == index, () -> {
                tab = index; scroll = 0; rebuildWidgets();
            });
        }
        if (tab == 0) {
            segment("大小预设", new String[]{"小", "中", "大"}, new String[]{"small", "medium", "large"},
                    draft.sizePreset, value -> draft.sizePreset = value);
            number("smallCellSize", "小号尺寸", "8–512 px", draft.smallCellSize, 8, 512, value -> draft.smallCellSize = value);
            number("mediumCellSize", "中号尺寸", "8–512 px", draft.mediumCellSize, 8, 512, value -> draft.mediumCellSize = value);
            number("largeCellSize", "大号尺寸", "8–512 px", draft.largeCellSize, 8, 512, value -> draft.largeCellSize = value);
            number("stickerSize", "独立大图", "24–256 px", draft.stickerSize, 24, 256, value -> draft.stickerSize = value);
        } else if (tab == 1) {
            segment("主题", new String[]{"深色", "浅色"}, new String[]{"dark", "light"}, draft.uiTheme, value -> draft.uiTheme = value);
            number("blurRadius", "背景模糊", "0–10", draft.blurRadius, 0, 10, value -> draft.blurRadius = value);
            number("panelOpacity", "面板不透明度", "64–255", draft.panelOpacity, 64, 255, value -> draft.panelOpacity = value);
            color("accentColor", "强调色", draft.accentColor, value -> draft.accentColor = value);
            color("darkSurfaceColor", "深色面板", draft.darkSurfaceColor, value -> draft.darkSurfaceColor = value);
            color("lightSurfaceColor", "浅色面板", draft.lightSurfaceColor, value -> draft.lightSurfaceColor = value);
        } else {
            segment("图片聊天", new String[]{"关闭", "开启"}, new String[]{"false", "true"}, String.valueOf(draft.enabled),
                    value -> draft.enabled = Boolean.parseBoolean(value));
            number("maxCellSize", "行内尺寸上限", "8–512 px", draft.maxCellSize, 8, 512, value -> draft.maxCellSize = value);
            number("maxNameLength", "名称长度上限", "1–128 字符", draft.maxNameLength, 1, 128, value -> draft.maxNameLength = value);
        }
        int actionsY = top + panelHeight - 32;
        button(left + 12, actionsY, 20, "恢复默认", PickerButton.REFRESH, () -> false, () -> {
            draft = new MemeEmojiConfig(); raw.clear(); errors.clear(); status = ""; rebuildWidgets();
        });
        button(left + panelWidth - 140, actionsY, 60, "取消", PickerButton.TEXT, () -> false, this::onClose);
        save = button(left + panelWidth - 72, actionsY, 60, "保存", PickerButton.TEXT, () -> true, this::save);
        positionRows();
        validateSave();
    }

    private PickerButton button(int x, int y, int w, String label, int icon,
                                java.util.function.BooleanSupplier selected, Runnable action) {
        return addRenderableWidget(new PickerButton(x, y, w, label, icon, selected, action, this::theme));
    }

    private void segment(String label, String[] labels, String[] values, String selected, Consumer<String> setter) {
        Row row = new Row(label, "", null);
        String[] current = {selected};
        int available = Math.min(156, panelWidth / 2 - 12);
        int w = available / labels.length;
        for (int i = 0; i < labels.length; i++) {
            int index = i;
            row.widgets.add(button(left + panelWidth - 14 - available + i * w, 0, w - 2, labels[i], PickerButton.TEXT,
                    () -> current[0].equals(values[index]), () -> { current[0] = values[index]; setter.accept(values[index]); }));
        }
        rows.add(row);
    }

    private void number(String key, String label, String range, int value, int min, int max, IntConsumer setter) {
        Row row = new Row(label, range, key);
        EditBox field = input(key, String.valueOf(value), 6);
        field.setResponder(text -> {
            raw.put(key, text);
            try {
                int parsed = Integer.parseInt(text);
                if (parsed < min || parsed > max) throw new NumberFormatException();
                setter.accept(parsed); errors.remove(key);
            } catch (NumberFormatException failure) { errors.put(key, label + "：" + range); }
            validateSave();
        });
        row.widgets.add(field);
        rows.add(row);
    }

    private void color(String key, String label, String value, Consumer<String> setter) {
        Row row = new Row(label, "#RRGGBB", key);
        EditBox field = input(key, value, 7);
        field.setResponder(text -> {
            raw.put(key, text);
            if (text.matches("#[0-9a-fA-F]{6}")) { setter.accept(text); errors.remove(key); }
            else errors.put(key, label + "：请输入 #RRGGBB");
            validateSave();
        });
        row.swatch = () -> switch (key) {
            case "accentColor" -> draft.accentColor;
            case "darkSurfaceColor" -> draft.darkSurfaceColor;
            default -> draft.lightSurfaceColor;
        };
        row.widgets.add(field);
        rows.add(row);
    }

    private EditBox input(String key, String value, int length) {
        EditBox field = new EditBox(font, left + panelWidth - 112, 0, 96, 20, Component.literal(key));
        field.setMaxLength(length);
        field.setValue(raw.getOrDefault(key, value));
        return addRenderableWidget(field);
    }

    private int maxScroll() { return Math.max(0, rows.size() * 34 - (bodyBottom - bodyTop)); }

    private void positionRows() {
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            row.y = bodyTop + i * 34 - scroll;
            for (AbstractWidget widget : row.widgets) {
                widget.setY(row.y + 2);
                widget.visible = row.y >= bodyTop && row.y + 26 <= bodyBottom;
                if (!widget.visible && widget.isFocused()) { widget.setFocused(false); setFocused(null); }
            }
        }
    }

    private void validateSave() { if (save != null) save.active = errors.isEmpty(); }

    private void save() {
        if (!errors.isEmpty()) return;
        try {
            boolean rescan = draft.maxNameLength != MemeEmoji.config().maxNameLength;
            MemeEmoji.applyConfig(draft);
            EmojiChatRenderer.invalidate();
            if (rescan) EmojiLibrary.reload();
            onClose();
        } catch (Exception failure) {
            status = "保存失败：" + failure.getMessage();
            MemeEmoji.LOGGER.warn("Cannot save emoji settings", failure);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) { }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        GlassStyle theme = theme();
        theme.background(graphics, width, height, delta);
        theme.panel(graphics, left, top, panelWidth, panelHeight);
        graphics.drawString(font, title, left + 14, top + 17, theme.text, false);
        graphics.fill(left + 12, top + 64, left + panelWidth - 12, top + 65, theme.border);
        graphics.enableScissor(left + 10, bodyTop, left + panelWidth - 10, bodyBottom);
        for (Row row : rows) {
            if (row.y < bodyTop || row.y + 26 > bodyBottom) continue;
            graphics.drawString(font, row.label, left + 14, row.y + 2, theme.text, false);
            if (!row.detail.isEmpty()) graphics.drawString(font, row.detail, left + 14, row.y + 15, theme.muted, false);
            if (row.swatch != null) GlassStyle.rounded(graphics, left + panelWidth - 133, row.y + 4, 14, 14, 3, GlassStyle.rgb(row.swatch.get()));
            for (AbstractWidget widget : row.widgets) {
                if (widget instanceof EditBox edit) {
                    edit.setTextColor(theme.text);
                    edit.setBordered(false);
                    GlassStyle.rounded(graphics, edit.getX() - 4, edit.getY() - 2, edit.getWidth() + 8, 24, 4, theme.control);
                    graphics.fill(edit.getX(), edit.getY() + 20, edit.getRight(), edit.getY() + 21,
                            errors.containsKey(row.key) ? 0xFFEF7777 : edit.isFocused() ? theme.accent : theme.border);
                }
            }
        }
        graphics.disableScissor();
        if (maxScroll() > 0) {
            int track = bodyBottom - bodyTop;
            int thumb = Math.max(12, track * track / (rows.size() * 34));
            int y = bodyTop + scroll * (track - thumb) / maxScroll();
            graphics.fill(left + panelWidth - 7, y, left + panelWidth - 5, y + thumb, theme.muted);
        }
        preview(graphics, theme);
        super.render(graphics, mouseX, mouseY, delta);
    }

    private void preview(GuiGraphics graphics, GlassStyle theme) {
        int y = top + panelHeight - 104;
        graphics.fill(left + 12, y - 4, left + panelWidth - 12, y - 3, theme.border);
        int textX = left + 14;
        graphics.drawString(font, "预览", textX, y + 3, theme.muted, false);
        int size = draft.targetMaxSize();
        int sticker = draft.stickerSize;
        graphics.drawString(font, "行内 " + size + " px", textX, y + 19, theme.text, false);
        graphics.drawString(font, "大图 " + sticker + " px", textX, y + 32, theme.text, false);
        if (previewAsset != null) {
            EmojiAsset sample = previewAsset;
            double zoom = Math.min(1.0, 46.0 / Math.max(size, sticker));
            int box = Math.max(1, (int) (size * zoom));
            double scale = Math.min((double) box / sample.width(), (double) box / sample.height());
            int w = Math.max(1, (int) (sample.width() * scale));
            int h = Math.max(1, (int) (sample.height() * scale));
            EmojiTextures.render(graphics, sample, left + panelWidth - 130, y + (46 - h) / 2, w, h, 255);
            int largeBox = Math.max(1, (int) (sticker * zoom));
            scale = Math.min((double) largeBox / sample.width(), (double) largeBox / sample.height());
            w = Math.max(1, (int) (sample.width() * scale)); h = Math.max(1, (int) (sample.height() * scale));
            EmojiTextures.render(graphics, sample, left + panelWidth - 66, y + (46 - h) / 2, w, h, 255);
        }
        String message = errors.isEmpty() ? status : errors.values().iterator().next();
        if (!message.isEmpty()) graphics.drawString(font, font.plainSubstrByWidth(message, panelWidth - 28), textX, y + 43, 0xFFEF7777, false);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (x >= left && x < left + panelWidth && y >= bodyTop && y < bodyBottom) {
            scroll -= (int) (dy * 34); positionRows(); return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }

    @Override
    public void onClose() { Minecraft.getInstance().setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }

    private static final class Row {
        final String label, detail, key;
        final List<AbstractWidget> widgets = new ArrayList<>();
        java.util.function.Supplier<String> swatch;
        int y;
        Row(String label, String detail, String key) { this.label = label; this.detail = detail; this.key = key; }
    }
}
