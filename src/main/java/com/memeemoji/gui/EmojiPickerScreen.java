package com.memeemoji.gui;

import com.memeemoji.MemeEmoji;
import com.memeemoji.EmojiTokens;
import com.memeemoji.media.EmojiAsset;
import com.memeemoji.media.EmojiLibrary;
import com.memeemoji.media.EmojiTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class EmojiPickerScreen extends Screen {
    private static final String[] STAR = {"000010000", "000010000", "111111111", "011111110", "001111100", "001111100", "011101110", "010000010"};

    private final Screen parent;
    private final Consumer<String> callback;
    private PickerPreferences preferences = PickerPreferences.load();
    private GlassStyle theme = GlassStyle.current();
    private List<EmojiAsset> assets = List.of();
    private List<EmojiAsset> visible = List.of();
    private EditBox search;
    private PickerLayout layout;
    private Category category = Category.ALL;
    private boolean sticker;
    private boolean busy;
    private boolean error;
    private String status = "";
    private int scroll;
    private int selected = -1;
    private boolean gridFocused;
    private boolean draggingScrollbar;
    private PickerButton importButton;
    private PickerButton reloadButton;
    private PickerButton manageButton;
    private PickerButton deleteButton;
    private PickerButton renameButton;
    private PickerButton selectAllButton;
    private boolean managing;
    private final Set<UUID> marked = new LinkedHashSet<>();

    public EmojiPickerScreen(Screen parent, Consumer<String> callback) {
        super(Component.literal("表情库"));
        this.parent = parent;
        this.callback = callback;
        assets = List.copyOf(EmojiLibrary.local());
    }

    @Override
    protected void init() {
        preferences = PickerPreferences.load();
        theme = GlassStyle.current();
        String query = search == null ? "" : search.getValue();
        layout = PickerLayout.calculate(width, height);
        int left = layout.x() + 10;
        int right = layout.x() + layout.width() - 10;
        int top = layout.y();
        manageButton = button(right - 116, top + 8, 20, "管理", PickerButton.MANAGE, () -> managing, () -> {
            managing = !managing;
            marked.clear();
            rebuildWidgets();
        });
        importButton = button(right - 92, top + 8, 20, "导入图片", PickerButton.IMPORT, () -> false, this::chooseFiles);
        button(right - 68, top + 8, 20, "设置", PickerButton.SETTINGS, () -> false,
                () -> Minecraft.getInstance().setScreen(new EmojiSettingsScreen(this)));
        reloadButton = button(right - 44, top + 8, 20, "刷新", PickerButton.REFRESH, () -> false, this::reload);
        button(right - 20, top + 8, 20, "关闭", PickerButton.CLOSE, () -> false, this::onClose);
        search = new EditBox(font, left + 22, top + 42, right - left - 30, 10, Component.literal("搜索表情"));
        search.setBordered(false);
        search.setTextColor(theme.text);
        search.setMaxLength(128);
        search.setValue(query);
        search.setResponder(value -> { scroll = 0; selected = -1; filter(); });
        addRenderableWidget(search);
        int tabWidth = layout.width() < 300 ? 34 : 42;
        for (Category value : Category.values()) {
            button(left + value.ordinal() * tabWidth, top + 60, tabWidth, value.label, PickerButton.TEXT,
                    () -> category == value, () -> { category = value; scroll = 0; selected = -1; filter(); });
        }
        deleteButton = null;
        renameButton = null;
        selectAllButton = null;
        if (managing) {
            renameButton = button(right - 102, top + 60, 20, "修改名称", PickerButton.RENAME, () -> false, this::rename);
            selectAllButton = button(right - 78, top + 60, 54, "全选", PickerButton.TEXT, () -> false, () -> {
                if (!visible.isEmpty() && visible.stream().allMatch(a -> marked.contains(a.id())))
                    visible.forEach(a -> marked.remove(a.id()));
                else visible.forEach(a -> marked.add(a.id()));
                updateBusyButtons();
            });
            deleteButton = button(right - 20, top + 60, 20, "删除所选", PickerButton.DELETE, () -> false, this::confirmDelete);
        } else {
            button(right - 78, top + 60, 38, "行内", PickerButton.TEXT, () -> !sticker, () -> sticker = false);
            button(right - 38, top + 60, 38, "大图", PickerButton.TEXT, () -> sticker, () -> sticker = true);
        }
        filter();
        updateBusyButtons();
        setInitialFocus(search);
    }

    @Override
    protected void setInitialFocus() { setInitialFocus(search); }

    private PickerButton button(int x, int y, int w, String label, int icon,
                                BooleanSupplier selected, Runnable action) {
        return addRenderableWidget(new PickerButton(x, y, w, label, icon, selected, action));
    }

    private void filter() {
        String query = search == null ? "" : search.getValue().strip().toLowerCase(Locale.ROOT);
        List<EmojiAsset> source = assets;
        if (category == Category.RECENT) {
            Map<UUID, EmojiAsset> byId = new HashMap<>();
            assets.forEach(asset -> byId.put(asset.id(), asset));
            source = preferences.recent().stream().map(byId::get).filter(a -> a != null).toList();
        }
        visible = source.stream()
                .filter(asset -> category != Category.FAVORITES || preferences.isFavorite(asset.id()))
                .filter(asset -> asset.name().toLowerCase(Locale.ROOT).contains(query)).toList();
        selected = Math.min(selected, visible.size() - 1);
        clampScroll();
    }

    @Override
    public void tick() {
        List<EmojiAsset> current = EmojiLibrary.local();
        if (!assets.equals(current)) {
            preferences = PickerPreferences.load();
            assets = List.copyOf(current);
            marked.retainAll(assets.stream().map(EmojiAsset::id).toList());
            filter();
            updateBusyButtons();
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        theme = GlassStyle.current();
        search.setTextColor(theme.text);
        theme.background(graphics, width, height, delta);
        theme.panel(graphics, layout.x(), layout.y(), layout.width(), layout.height());
        graphics.drawString(font, title, layout.x() + 11, layout.y() + 14, theme.text, false);
        graphics.fill(layout.x() + 10, layout.y() + 84, layout.x() + layout.width() - 10, layout.y() + 85, theme.border);
        renderSearchBackground(graphics);

        graphics.enableScissor(layout.gridX(), layout.gridY(), layout.gridX() + layout.gridWidth(), layout.gridY() + layout.gridHeight());
        int first = (scroll / layout.cell()) * layout.columns();
        int last = Math.min(visible.size(), ((scroll + layout.gridHeight()) / layout.cell() + 1) * layout.columns());
        int hovered = indexAt(mouseX, mouseY);
        for (int i = first; i < last; i++) {
            EmojiAsset asset = visible.get(i);
            int x = cellX(i);
            int y = cellY(i);
            int size = layout.cell() - 4;
            boolean focused = gridFocused && selected == i;
            if (hovered == i || focused || marked.contains(asset.id())) {
                GlassStyle.rounded(graphics, x, y, size, size, 5, GlassStyle.alpha(theme.hover, 200));
                if (focused || marked.contains(asset.id())) graphics.renderOutline(x, y, size, size, theme.accent);
            }
            int box = Math.max(8, size - 19);
            double scale = Math.min((double) box / asset.width(), (double) box / asset.height());
            int imageWidth = Math.max(1, (int) Math.round(asset.width() * scale));
            int imageHeight = Math.max(1, (int) Math.round(asset.height() * scale));
            EmojiTextures.render(graphics, asset, x + (size - imageWidth) / 2,
                    y + 3 + (box - imageHeight) / 2, imageWidth, imageHeight, 255);
            String label = font.plainSubstrByWidth(asset.name(), size - 8);
            graphics.drawString(font, label, x + (size - font.width(label)) / 2, y + size - 12, theme.muted, false);
            if (managing) {
                int color = marked.contains(asset.id()) ? theme.accent : theme.muted;
                graphics.renderOutline(x + size - 13, y + 3, 10, 10, color);
                if (marked.contains(asset.id())) graphics.fill(x + size - 11, y + 5, x + size - 5, y + 11, color);
            } else if (hovered == i || preferences.isFavorite(asset.id())) {
                int color = preferences.isFavorite(asset.id()) ? 0xFFFFD57B : 0xFFB6C5BF;
                GlassStyle.rounded(graphics, x + size - 15, y + 1, 14, 14, 3, GlassStyle.alpha(theme.surface, 220));
                drawStar(graphics, x + size - 13, y + 4, color);
            }
            if (asset.animated()) graphics.fill(x + 4, y + 4, x + 7, y + 7, theme.accent);
        }
        if (visible.isEmpty()) {
            String empty = busy ? "正在加载…" : !search.getValue().isBlank() ? "没有匹配的表情"
                    : switch (category) { case ALL -> "表情库为空"; case RECENT -> "暂无最近使用"; case FAVORITES -> "暂无收藏"; };
            graphics.drawCenteredString(font, empty, layout.x() + layout.width() / 2,
                    layout.gridY() + Math.max(0, (layout.gridHeight() - font.lineHeight) / 2), theme.muted);
        }
        graphics.disableScissor();
        drawScrollbar(graphics);
        int problems = EmojiLibrary.problems().size();
        String footer = status.isBlank() ? managing ? "已选 " + marked.size() + " 个 · " + visible.size() + " 个表情"
                : visible.size() + " 个表情" + (problems == 0 ? "" : " · " + problems + " 个无法载入") : status;
        graphics.drawString(font, font.plainSubstrByWidth(footer, layout.width() - 22),
                layout.x() + 11, layout.y() + layout.height() - 14, error || problems > 0 ? 0xFFFFA7A7 : theme.muted, false);
        super.render(graphics, mouseX, mouseY, delta);
        if (hovered >= 0) {
            EmojiAsset asset = visible.get(hovered);
            boolean star = !managing && isStar(mouseX, mouseY, hovered);
            String failure = EmojiTextures.failure(asset.id());
            String tooltip = star ? (preferences.isFavorite(asset.id()) ? "取消收藏" : "收藏") : failure != null ? failure
                    : asset.name() + " · " + asset.width() + " × " + asset.height() + (asset.animated() ? " · 动图" : "");
            graphics.renderTooltip(font, font.split(Component.literal(tooltip), Math.min(350, width - 24)), mouseX, mouseY);
        } else if (mouseX >= layout.x() + 10 && mouseX < layout.x() + layout.width() - 10
                && mouseY >= layout.y() + layout.height() - 18 && mouseY < layout.y() + layout.height()) {
            String details = error ? status : EmojiLibrary.problems().entrySet().stream()
                    .map(entry -> entry.getKey() + "：" + entry.getValue()).reduce((a, b) -> a + "\n" + b).orElse(footer);
            graphics.renderTooltip(font, font.split(Component.literal(details), Math.min(350, width - 24)), mouseX, mouseY);
        }
    }

    private void renderSearchBackground(GuiGraphics graphics) {
        int x = layout.x() + 11;
        int y = layout.y() + 37;
        int width = layout.width() - 22;
        GlassStyle.rounded(graphics, x, y, width, 18, 4, GlassStyle.alpha(theme.control, 210));
        graphics.fill(x + 5, y + 17, x + width - 5, y + 18, search.isFocused() ? theme.accent : theme.border);
        int iconX = x + 5;
        int iconY = y + 4;
        int color = search.isFocused() ? theme.accent : theme.muted;
        graphics.fill(iconX + 2, iconY, iconX + 6, iconY + 1, color);
        graphics.fill(iconX + 2, iconY + 7, iconX + 6, iconY + 8, color);
        graphics.fill(iconX, iconY + 2, iconX + 1, iconY + 6, color);
        graphics.fill(iconX + 7, iconY + 2, iconX + 8, iconY + 6, color);
        graphics.fill(iconX + 1, iconY + 1, iconX + 2, iconY + 2, color);
        graphics.fill(iconX + 6, iconY + 1, iconX + 7, iconY + 2, color);
        graphics.fill(iconX + 1, iconY + 6, iconX + 2, iconY + 7, color);
        for (int i = 0; i < 4; i++) {
            graphics.fill(iconX + 6 + i, iconY + 6 + i, iconX + 8 + i, iconY + 7 + i, color);
        }
        if (search.getValue().isEmpty()) {
            graphics.drawString(font, font.plainSubstrByWidth("搜索表情", search.getWidth() - 7),
                    search.getX(), search.getY(), theme.muted, false);
        }
    }

    private static void drawStar(GuiGraphics graphics, int x, int y, int color) {
        for (int row = 0; row < STAR.length; row++) {
            for (int col = 0; col < STAR[row].length(); col++) {
                if (STAR[row].charAt(col) == '1') graphics.fill(x + col, y + row, x + col + 1, y + row + 1, color);
            }
        }
    }

    private int cellX(int index) { return layout.gridX() + index % layout.columns() * layout.cell(); }
    private int cellY(int index) { return layout.gridY() + index / layout.columns() * layout.cell() - scroll; }
    private int contentHeight() { return (visible.size() + layout.columns() - 1) / layout.columns() * layout.cell(); }
    private int maxScroll() { return Math.max(0, contentHeight() - layout.gridHeight()); }
    private void clampScroll() { if (layout != null) scroll = Math.max(0, Math.min(scroll, maxScroll())); }

    private int indexAt(double x, double y) {
        if (!layout.inGrid(x, y)) return -1;
        int column = (int) (x - layout.gridX()) / layout.cell();
        int row = (int) (y - layout.gridY() + scroll) / layout.cell();
        int index = row * layout.columns() + column;
        if (column >= layout.columns() || index >= visible.size()) return -1;
        return x < cellX(index) + layout.cell() - 4 && y < cellY(index) + layout.cell() - 4 ? index : -1;
    }

    private boolean isStar(double x, double y, int index) {
        return x >= cellX(index) + layout.cell() - 20 && y < cellY(index) + 16;
    }

    private void drawScrollbar(GuiGraphics graphics) {
        if (maxScroll() <= 0) return;
        int x = layout.gridX() + layout.gridWidth() + 2;
        int thumb = Math.max(14, layout.gridHeight() * layout.gridHeight() / contentHeight());
        int y = layout.gridY() + (layout.gridHeight() - thumb) * scroll / maxScroll();
        graphics.fill(x, layout.gridY(), x + 3, layout.gridY() + layout.gridHeight(), theme.border);
        graphics.fill(x, y, x + 3, y + thumb, theme.muted);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button == 0 && x >= layout.x() + 11 && x < layout.x() + layout.width() - 11
                && y >= layout.y() + 37 && y < layout.y() + 55) {
            gridFocused = false;
            setFocused(search);
            search.mouseClicked(x, y, button);
            return true;
        }
        if (button == 0 && maxScroll() > 0 && x >= layout.gridX() + layout.gridWidth()
                && x < layout.x() + layout.width() && y >= layout.gridY() && y < layout.gridY() + layout.gridHeight()) {
            draggingScrollbar = true;
            dragScrollbar(y);
            return true;
        }
        int index = indexAt(x, y);
        if (index >= 0 && (button == 0 || button == 1)) {
            if (busy) return true;
            selected = index;
            gridFocused = true;
            setFocused(null);
            if (managing) toggleMarked(visible.get(index).id());
            else if (button == 1 || isStar(x, y, index)) toggleFavorite(visible.get(index).id());
            else choose(index);
            return true;
        }
        gridFocused = false;
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (draggingScrollbar) { dragScrollbar(y); return true; }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        draggingScrollbar = false;
        return super.mouseReleased(x, y, button);
    }

    private void dragScrollbar(double y) {
        int thumb = Math.max(14, layout.gridHeight() * layout.gridHeight() / Math.max(1, contentHeight()));
        scroll = (int) ((y - layout.gridY() - thumb / 2.0) * maxScroll() / Math.max(1, layout.gridHeight() - thumb));
        clampScroll();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (layout.inGrid(x, y)) {
            scroll -= (int) (dy * layout.cell() * 0.75);
            clampScroll();
            return true;
        }
        return super.mouseScrolled(x, y, dx, dy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE && managing && !busy) {
            managing = false; marked.clear(); rebuildWidgets(); return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        if (managing && key == GLFW.GLFW_KEY_DELETE) { confirmDelete(); return true; }
        if (managing && key == GLFW.GLFW_KEY_F2) { rename(); return true; }
        if (hasControlDown() && key == GLFW.GLFW_KEY_F) { gridFocused = false; setFocused(search); return true; }
        if ((search.isFocused() && key == GLFW.GLFW_KEY_DOWN) || gridFocused) {
            if (visible.isEmpty()) {
                gridFocused = false;
                setFocused(search);
                return super.keyPressed(key, scan, modifiers);
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { choose(Math.max(0, selected)); return true; }
            if (key == GLFW.GLFW_KEY_SPACE && gridFocused) {
                if (!busy) {
                    UUID id = visible.get(Math.max(0, selected)).id();
                    if (managing) toggleMarked(id); else toggleFavorite(id);
                }
                return true;
            }
            int move = switch (key) {
                case GLFW.GLFW_KEY_LEFT -> -1;
                case GLFW.GLFW_KEY_RIGHT -> 1;
                case GLFW.GLFW_KEY_UP -> -layout.columns();
                case GLFW.GLFW_KEY_DOWN -> layout.columns();
                case GLFW.GLFW_KEY_PAGE_UP -> -layout.columns() * Math.max(1, layout.gridHeight() / layout.cell());
                case GLFW.GLFW_KEY_PAGE_DOWN -> layout.columns() * Math.max(1, layout.gridHeight() / layout.cell());
                default -> 0;
            };
            if (move != 0 || key == GLFW.GLFW_KEY_HOME || key == GLFW.GLFW_KEY_END) {
                selected = selected < 0 ? 0 : Math.max(0, Math.min(visible.size() - 1, selected + move));
                if (key == GLFW.GLFW_KEY_HOME) selected = 0;
                if (key == GLFW.GLFW_KEY_END) selected = visible.size() - 1;
                gridFocused = true;
                setFocused(null);
                int top = selected / layout.columns() * layout.cell();
                if (top < scroll) scroll = top;
                if (top + layout.cell() > scroll + layout.gridHeight()) scroll = top + layout.cell() - layout.gridHeight();
                clampScroll();
                return true;
            }
            if (key == GLFW.GLFW_KEY_TAB) gridFocused = false;
        }
        if (search.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && !visible.isEmpty()) {
            choose(0);
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    private void toggleFavorite(UUID id) {
        preferences = PickerPreferences.load();
        preferences.toggleFavorite(id);
        savePreferences();
        filter();
    }

    private void choose(int index) {
        if (busy || index < 0 || index >= visible.size()) return;
        EmojiAsset asset = visible.get(index);
        if (managing) { toggleMarked(asset.id()); return; }
        preferences = PickerPreferences.load();
        preferences.use(asset.id());
        savePreferences();
        callback.accept(sticker ? EmojiTokens.reference(asset.id(), true) : ":" + asset.name() + ":");
    }

    private void savePreferences() {
        try { preferences.save(); }
        catch (Exception exception) { failure("无法保存收藏与最近使用", exception); }
    }

    private void chooseFiles() {
        if (busy) return;
        begin("正在选择图片…");
        CompletableFuture.supplyAsync(() -> {
            // tinyfd owns a blocking native window, so it must not run on the render thread.
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.mallocPointer(6);
                for (String pattern : List.of("*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.bmp")) filters.put(stack.UTF8(pattern));
                filters.flip();
                TinyFileDialogs.tinyfd_setGlobalInt("tinyfd_winUtf8", 1);
                String result = TinyFileDialogs.tinyfd_openFileDialog("导入表情", "", filters, "图片", true);
                if (result == null) return List.<Path>of();
                List<Path> paths = new ArrayList<>();
                for (String path : result.split("\\|")) paths.add(Path.of(path));
                return paths;
            }
        }).whenComplete((paths, exception) -> Minecraft.getInstance().execute(() -> {
            if (exception != null) { finish(); failure("无法打开文件选择器", exception); }
            else if (paths.isEmpty()) { finish(); status = "已取消导入"; }
            else importFiles(paths);
        }));
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
        if (!busy && !paths.isEmpty()) { begin("正在导入…"); importFiles(List.copyOf(paths)); }
    }

    private void importFiles(List<Path> paths) {
        status = "正在导入…";
        EmojiLibrary.importFiles(paths).whenComplete((result, exception) -> Minecraft.getInstance().execute(() -> {
            finish();
            if (exception != null) failure("导入失败", exception);
            else {
                assets = List.copyOf(EmojiLibrary.local());
                category = Category.ALL;
                if (search != null) search.setValue("");
                scroll = 0;
                filter();
                status = "已导入 " + result.size() + " 个表情";
            }
        }));
    }

    private void reload() {
        if (busy) return;
        begin("正在刷新…");
        EmojiLibrary.reload().whenComplete((result, exception) -> Minecraft.getInstance().execute(() -> {
            finish();
            if (exception != null) failure("刷新失败", exception);
            else { assets = List.copyOf(result); filter(); status = ""; }
        }));
    }

    private void begin(String message) { busy = true; error = false; status = message; updateBusyButtons(); }
    private void finish() { busy = false; updateBusyButtons(); }
    private void updateBusyButtons() {
        if (importButton != null) importButton.active = !busy;
        if (reloadButton != null) reloadButton.active = !busy;
        if (manageButton != null) manageButton.active = !busy;
        if (deleteButton != null) deleteButton.active = !busy && !marked.isEmpty();
        if (renameButton != null) renameButton.active = !busy && marked.size() == 1;
        if (selectAllButton != null) selectAllButton.active = !busy && !visible.isEmpty();
    }

    private void toggleMarked(UUID id) {
        if (!marked.remove(id)) marked.add(id);
        status = "";
        updateBusyButtons();
    }

    private void rename() {
        if (busy || marked.size() != 1) return;
        EmojiAsset asset = assets.stream().filter(a -> marked.contains(a.id())).findFirst().orElse(null);
        if (asset != null) Minecraft.getInstance().setScreen(new EmojiRenameScreen(this, asset, saved -> {
            assets = List.copyOf(EmojiLibrary.local());
            if (search != null) search.setValue("");
            filter();
            error = false;
            status = "已改名为 " + saved.name();
            updateBusyButtons();
        }));
    }

    private void confirmDelete() {
        if (busy || marked.isEmpty()) return;
        Set<UUID> ids = Set.copyOf(marked);
        Minecraft.getInstance().setScreen(new EmojiDeleteScreen(this, ids.size(), () -> delete(ids)));
    }

    private void delete(Set<UUID> ids) {
        begin("正在删除…");
        EmojiLibrary.delete(ids).whenComplete((result, exception) -> Minecraft.getInstance().execute(() -> {
            if (exception == null) {
                preferences = PickerPreferences.load();
                preferences.remove(result.deleted());
                marked.removeAll(result.deleted());
                assets = List.copyOf(EmojiLibrary.local());
                filter();
                if (result.failures().isEmpty()) status = "已删除 " + result.deleted().size() + " 个表情";
                else {
                    error = true;
                    status = "已删除 " + result.deleted().size() + " 个；" + result.failures().entrySet().stream()
                            .map(e -> e.getKey() + "：" + e.getValue()).reduce((a, b) -> a + "；" + b).orElse("");
                }
                try { preferences.save(); }
                catch (Exception failure) {
                    String deletionStatus = status;
                    failure("无法保存收藏与最近使用", failure);
                    status = deletionStatus + "；" + status;
                }
            } else failure("删除失败", exception);
            finish();
        }));
    }

    private void failure(String message, Throwable exception) {
        Throwable cause = exception;
        while (cause.getCause() != null) cause = cause.getCause();
        error = true;
        status = message + (cause.getMessage() == null ? "" : "：" + cause.getMessage());
        MemeEmoji.LOGGER.warn(message, exception);
    }

    @Override
    public void onClose() { Minecraft.getInstance().setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }

    private enum Category {
        ALL("全部"), RECENT("最近"), FAVORITES("收藏");
        final String label;
        Category(String label) { this.label = label; }
    }
}
