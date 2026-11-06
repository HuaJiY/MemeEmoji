package com.memeemoji.qa;

import com.memeemoji.MemeEmoji;
import com.memeemoji.MemeEmojiConfig;
import com.memeemoji.gui.EmojiPickerScreen;
import com.memeemoji.gui.EmojiSettingsScreen;
import com.memeemoji.gui.EmojiPreviewScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.memeemoji.gui.EmojiCollection;
import com.memeemoji.media.EmojiAsset;
import com.memeemoji.media.EmojiLibrary;
import com.memeemoji.net.EmojiNetworkClient;
import net.fabricmc.loader.api.FabricLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class SettingsGameChecks {
    private final Path evidence;
    private int ticks;
    private MemeEmojiConfig original;
    private String originalFile;
    private EmojiPickerScreen picker;
    private ChatScreen chat;
    private final String prefix = FabricLoader.getInstance().isModLoaded("modernui") ? "modernui-" : "vanilla-";
    private List<EmojiAsset> cached = List.of();
    private CompletableFuture<?> downloads;
    private CompletableFuture<?> offlineCollection;
    private CompletableFuture<?> interrupted;
    private int disconnectedAt;
    private int previewAt;
    private boolean previewCaptured;

    SettingsGameChecks(Path evidence) { this.evidence = evidence; }

    void start(Minecraft mc, ChatScreen chat) throws Exception {
        this.chat = chat;
        original = MemeEmoji.config().copy();
        originalFile = Files.readString(MemeEmoji.configDir().resolve("config.json"));
        mc.options.guiScale().set(2); mc.resizeDisplay();
        mc.setScreen(chat);
        chat.keyPressed(GLFW.GLFW_KEY_E, 0, GLFW.GLFW_MOD_CONTROL);
        if (EmojiLibrary.findName("清晰测试") != null) {
            mc.screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
                    .findFirst().orElseThrow().setValue("清晰测试");
            mc.screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
            chat.keyPressed(GLFW.GLFW_KEY_E, 0, GLFW.GLFW_MOD_CONTROL);
        }
        picker = (EmojiPickerScreen) mc.screen;
    }

    boolean tick(Minecraft mc) throws Exception {
        ticks++;
        mc.getToasts().clear();
        if (ticks == 10) {
            capture(mc, "picker-dark");
            click(picker, "设置");
            check(mc.screen instanceof EmojiSettingsScreen, "Picker gear opens settings");
        } else if (ticks == 30) {
            edit(mc.screen, "smallCellSize").setValue("38");
            check(MemeEmoji.config().smallCellSize == original.smallCellSize, "Draft must not affect active config");
            check(Files.readString(MemeEmoji.configDir().resolve("config.json")).equals(originalFile), "Draft must not write file");
            capture(mc, "settings-display");
            click(mc.screen, "外观");
            click(mc.screen, "浅色");
            edit(mc.screen, "blurRadius").setValue("0");
        } else if (ticks == 60) {
            capture(mc, "settings-light-sharp");
            edit(mc.screen, "blurRadius").setValue("7");
        } else if (ticks == 90) {
            capture(mc, "settings-light-blur");
            double sharp = edgeEnergy(evidence.resolve(prefix + "settings-light-sharp.png"));
            double blurred = edgeEnergy(evidence.resolve(prefix + "settings-light-blur.png"));
            check(blurred < sharp * 0.8, "Blur must soften actual framebuffer pixels: " + sharp + " -> " + blurred);
            Files.writeString(evidence.resolve(prefix + "blur-result.txt"), "PASS: framebuffer edge energy " + sharp + " -> " + blurred);
            edit(mc.screen, "accentColor").setValue("broken");
            check(!widget(mc.screen, "保存").active, "Invalid color disables save");
            edit(mc.screen, "accentColor").setValue("#3AB7D0");
            check(widget(mc.screen, "保存").active, "Valid color enables save");
            mc.screen.onClose();
            check(mc.screen == picker, "Cancel returns to picker");
            check(MemeEmoji.config().smallCellSize == original.smallCellSize && MemeEmoji.config().uiTheme.equals(original.uiTheme), "Cancel discards draft");
            check(Files.readString(MemeEmoji.configDir().resolve("config.json")).equals(originalFile), "Cancel preserves config file");
            click(picker, "设置");
            edit(mc.screen, "smallCellSize").setValue("36");
            click(mc.screen, "外观"); click(mc.screen, "浅色");
            edit(mc.screen, "accentColor").setValue("#3AB7D0");
            click(mc.screen, "保存");
            check(mc.screen == picker, "Save returns to picker");
            check(MemeEmoji.config().smallCellSize == 36 && MemeEmoji.config().uiTheme.equals("light"), "Save applies draft");
            MemeEmojiConfig disk = MemeEmojiConfig.load(MemeEmoji.configDir().resolve("config.json"));
            check(disk.smallCellSize == 36 && disk.accentColor.equals("#3AB7D0"), "Save persists settings");
        } else if (ticks == 120) {
            capture(mc, "picker-light");
            click(picker, "设置"); click(mc.screen, "外观");
            mc.screen.mouseScrolled(mc.screen.width / 2.0, mc.screen.height / 2.0, 0, -5);
        } else if (ticks == 150) {
            check(edit(mc.screen, "lightSurfaceColor").visible, "Appearance colors reachable by scrolling");
            capture(mc, "settings-colors");
            mc.options.guiScale().set(4); mc.resizeDisplay();
        } else if (ticks == 180) {
            capture(mc, "settings-small");
            Screen screen = mc.screen;
            for (var child : screen.children()) if (child instanceof AbstractWidget widget && widget.visible) {
                check(widget.getX() >= 0 && widget.getRight() <= screen.width && widget.getY() >= 0 && widget.getBottom() <= screen.height,
                        "Visible settings controls fit small screen: " + widget.getMessage().getString());
            }
            click(screen, "显示");
            screen.mouseScrolled(screen.width / 2.0, 90, 0, -20);
            check(edit(screen, "stickerSize").visible, "Small screen can reach final row");
            click(screen, "取消");
            MemeEmoji.applyConfig(original);
            mc.options.guiScale().set(2); mc.resizeDisplay();
            picker.onClose();
            chat.handleChatInput("/memeemoji config", false);
        } else if (ticks == 190) {
            check(mc.screen instanceof EmojiSettingsScreen, "Config command opens settings");
            mc.screen.onClose();
            Files.writeString(evidence.resolve(prefix + "settings-result.txt"), "PASS: gear and command entry, no draft writes, cancel restoration, save/apply/disk, validation, live light/dark and blur, scrolling at GUI scales 2 and 4");
            Path server = evidence.getParent().resolve("qa-server/qa-world/memeemoji/assets");
            List<EmojiAsset> assets = new ArrayList<>();
            try (var files = Files.list(server)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".json")).limit(8).toList()) {
                    JsonObject json = new Gson().fromJson(Files.readString(file), JsonObject.class);
                    EmojiAsset asset = new Gson().fromJson(json.get("asset"), EmojiAsset.class);
                    EmojiLibrary.acceptMetadata(asset); assets.add(asset);
                }
            }
            check(assets.size() == 8, "Eight server fixtures for queued fetch"); cached = List.copyOf(assets);
            downloads = CompletableFuture.allOf(cached.stream().map(a -> EmojiNetworkClient.fetch(a.id())).toArray(CompletableFuture[]::new));
        } else if (ticks > 190 && offlineCollection == null && downloads != null && downloads.isDone()) {
            downloads.join();
            for (EmojiAsset asset : cached) check(EmojiLibrary.hasFile(asset.id()), "Queued fetch completes original file");
            if (!previewCaptured) {
                if (previewAt == 0) {
                    EmojiAsset sample = cached.stream().filter(a -> a.name().equals("清晰测试")).findFirst().orElse(cached.getFirst());
                    mc.setScreen(new EmojiPreviewScreen(chat, sample));
                    click(mc.screen, "收藏到表情库");
                    previewAt = ticks;
                } else if (ticks > previewAt + 30) {
                    check(!widget(mc.screen, "已收藏到表情库").active, "Preview confirms collection");
                    capture(mc, "collection");
                    mc.screen.onClose();
                    previewCaptured = true;
                }
                return false;
            }
            UUID missing = UUID.randomUUID();
            EmojiLibrary.acceptMetadata(new EmojiAsset(missing, "missing", 1, 1, false));
            interrupted = EmojiNetworkClient.fetch(missing);
            mc.level.disconnect();
            mc.disconnect();
            disconnectedAt = ticks;
            EmojiAsset source = cached.stream().filter(a -> EmojiLibrary.personalCopy(a.id()) == null).findFirst().orElse(cached.getFirst());
            offlineCollection = EmojiCollection.collect(source.id());
        } else if (offlineCollection != null && offlineCollection.isDone() && ticks > disconnectedAt + 20) {
            check(interrupted.isCompletedExceptionally(), "Disconnect completes pending fetch exceptionally");
            offlineCollection.join();
            Files.writeString(evidence.resolve(prefix + "fetch-result.txt"), "PASS: eight queued fetches, disconnect termination and cached collection without a server connection");
            return true;
        }
        if (ticks > 1600) throw new AssertionError("Settings/fetch QA timeout");
        return false;
    }

    static AbstractWidget widget(Screen screen, String label) {
        return screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                .filter(w -> w.getMessage().getString().equals(label) && w.visible).findFirst().orElseThrow(() -> new AssertionError("Missing control " + label));
    }

    static void click(Screen screen, String label) {
        AbstractWidget widget = widget(screen, label);
        check(screen.mouseClicked(widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0, 0), "Click " + label);
    }

    static EditBox edit(Screen screen, String key) {
        return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
                .filter(w -> w.getMessage().getString().equals(key)).findFirst().orElseThrow();
    }

    private void capture(Minecraft mc, String name) throws Exception {
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(evidence.resolve(prefix + name + ".png")); }
    }

    private static double edgeEnergy(Path file) throws Exception {
        var image = javax.imageio.ImageIO.read(file.toFile());
        double energy = 0;
        int count = 0;
        for (int y = 520; y < 640; y++) for (int x = 2; x < 60; x++) {
            int a = image.getRGB(x, y), b = image.getRGB(x + 1, y);
            for (int shift : new int[]{0, 8, 16}) energy += Math.abs(((a >> shift) & 255) - ((b >> shift) & 255));
            count += 3;
        }
        return energy / count;
    }

    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
