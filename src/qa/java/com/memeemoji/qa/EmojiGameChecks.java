package com.memeemoji.qa;

import com.memeemoji.EmojiTokens;
import com.memeemoji.chat.EmojiChatRenderer;
import com.memeemoji.gui.EmojiPickerScreen;
import com.memeemoji.gui.EmojiPreviewScreen;
import com.memeemoji.media.EmojiAsset;
import com.memeemoji.media.EmojiLibrary;
import com.memeemoji.media.EmojiTextures;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/** Runs only in the qa Loom source set, never in the distributed mod. */
public final class EmojiGameChecks implements ClientModInitializer {
    private final String role = System.getProperty("memeemoji.qa.role", "receiver");
    private final Path root = Path.of(System.getProperty("memeemoji.qa.root"));
    private final Path evidence = root.resolve("build/qa-evidence");
    private int ticks;
    private int connected;
    private int stage;
    private int since;
    private boolean finished;
    private List<UUID> ids = List.of();
    private ChatScreen chat;
    private final Map<UUID, Set<Integer>> frames = new HashMap<>();
    private UUID replacedId;
    private boolean reloaded;
    private SettingsGameChecks settings;
    private CollectionGameChecks collection;
    private final boolean settingsOnly = System.getProperty("memeemoji.qa.focus", "full").equals("settings");

    @Override
    public void onInitializeClient() {
        try {
            Files.createDirectories(evidence);
            if (role.equals("sender") || role.equals("bubbles") || role.equals("vanilla")) fixtures();
        } catch (Exception e) { throw new RuntimeException(e); }
        if (role.equals("bubbles") && !settingsOnly) {
            new TalkBubblesGameChecks(evidence).start();
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (finished) return;
            try { tick(client); }
            catch (Throwable failure) {
                failure.printStackTrace();
                finish(client, "FAIL: " + failure);
            }
        });
    }

    private void tick(Minecraft mc) throws Exception {
        if (++ticks > 12000) throw new AssertionError("Game QA timed out at stage " + stage);
        if (settings != null) {
            if (settings.tick(mc)) finish(mc, "PASS: settings draft/cancel/save, themes, verified shader blur, responsive layout and queued/offline collection");
            return;
        }
        if (mc.player == null || mc.getConnection() == null) return;
        connected++;
        if (connected == 1) {
            mc.options.guiScale().set(2);
            mc.options.renderDistance().set(2);
            mc.options.simulationDistance().set(5);
            mc.options.chatHeightFocused().set(1.0);
            mc.options.chatHeightUnfocused().set(1.0);
            mc.resizeDisplay();
        }
        observeAnimation();
        if (settingsOnly) {
            if (connected > 80 && !EmojiLibrary.local().isEmpty()) {
                settings = new SettingsGameChecks(evidence);
                settings.start(mc, new ChatScreen(""));
            }
            return;
        }
        if (collection != null) {
            if (collection.tick(mc)) finish(mc, "PASS: network/chat + preview and right-click collection, original GIF/WebP, duplicate and collision handling, resend");
            return;
        }
        if (role.equals("sender")) {
            sender(mc);
            return;
        }
        Path manifest = evidence.resolve("assets.txt");
        if (ids.isEmpty() && Files.exists(manifest)) ids = Files.readAllLines(manifest).stream().map(UUID::fromString).toList();
        if (stage == 0) {
            if (ids.size() < 8 || ids.stream().anyMatch(id -> EmojiLibrary.find(id) == null)
                    || !Files.exists(evidence.resolve("messages-sent.txt"))) return;
            mc.setScreen(new ChatScreen(""));
            stage = 1; since = connected;
        } else if (stage == 1 && connected - since > 80) {
            // Moving through history exercises the same visibility-triggered downloads as a player.
            mc.gui.getChat().scrollChat(1);
            if (ids.stream().anyMatch(id -> !EmojiLibrary.hasFile(id))) return;
            check(EmojiLibrary.local().isEmpty(), "Receiver's personal library must stay empty");
            check(ids.stream().noneMatch(id -> EmojiTextures.failure(id) != null), "Received image decode failure");
            mc.gui.getChat().resetChatScroll();
            capture(mc, "chat");
            Field field = EmojiChatRenderer.class.getDeclaredField("heights"); field.setAccessible(true);
            int[] heights = (int[]) field.get(null);
            check(java.util.Arrays.stream(heights).anyMatch(h -> h == 9), "Text line must stay 9 px");
            check(java.util.Arrays.stream(heights).anyMatch(h -> h >= 52), "Sticker line must have its own height");
            ChatComponent history = mc.gui.getChat();
            var saved = history.storeState();
            for (int i = 0; i < 30; i++) history.addMessage(Component.literal("history " + i));
            history.scrollChat(4);
            Field scroll = ChatComponent.class.getDeclaredField("chatScrollbarPos"); scroll.setAccessible(true);
            int before = scroll.getInt(history);
            history.addMessage(Component.literal("one\ntwo\n" + EmojiTokens.reference(ids.getFirst(), false)));
            check(scroll.getInt(history) == before + 3, "New mixed message must preserve scrolled history position");
            history.resetChatScroll(); history.restoreState(saved);
            Files.writeString(evidence.resolve("receiver-network.txt"), "PASS: 8 referenced assets received; personal library empty; mixed line heights=" + java.util.Arrays.toString(heights));
            stage = 8; since = connected;
        } else if (stage == 8 && connected - since > 5) {
            chat = (ChatScreen) mc.screen;
            input(chat).setValue("draft-right"); input(chat).setCursorPosition(5);
            boolean clicked = false;
            for (int y = 1; y < mc.getWindow().getGuiScaledHeight() - 40 && !clicked; y++) {
                for (int x = 4; x < 325 && !clicked; x++) {
                    EmojiAsset candidate = EmojiChatRenderer.assetAt(x, y);
                    if (candidate != null && candidate.name().equals("清晰测试")) clicked = chat.mouseClicked(x, y, 0);
                }
            }
            check(clicked && mc.screen instanceof EmojiPreviewScreen, "Click chat image opens preview");
            stage = 2; since = connected;
        } else if (stage == 2 && connected - since > 30) {
            capture(mc, "preview");
            mc.screen.onClose();
            check(mc.screen == chat && input(chat).getValue().equals("draft-right") && input(chat).getCursorPosition() == 5, "Preview preserves draft/cursor");
            collection = new CollectionGameChecks(root, evidence, ids, chat);
            collection.start(mc);
        }
    }

    private void sender(Minecraft mc) throws Exception {
        if (stage == 0 && connected > 120 && EmojiLibrary.local().size() >= 9) {
            ids = EmojiLibrary.local().stream().filter(EmojiGameChecks::sentFixture).map(EmojiAsset::id).toList();
            Files.write(evidence.resolve("assets.txt"), ids.stream().map(UUID::toString).toList());
            chat = new ChatScreen(""); mc.setScreen(chat);
            chat.handleChatInput("普通文字：这条消息保持正常高度。", true);
            chat.handleChatInput("行内图片 " + String.join(" ", EmojiLibrary.local().stream().filter(EmojiGameChecks::sentFixture).map(a -> ":" + a.name() + ":").toList()), true);
            stage = 1; since = connected;
        } else if (stage == 1 && connected - since > 100) {
            chat.handleChatInput(EmojiTokens.reference(EmojiLibrary.findName("清晰测试").id(), true), true);
            stage = 2; since = connected;
        } else if (stage == 2 && connected - since > 60) {
            chat.handleChatInput("图片之后的纯文字也不增高。", true);
            Files.writeString(evidence.resolve("messages-sent.txt"), "ready");
            input(chat).setValue("leftright"); input(chat).setCursorPosition(4);
            chat.keyPressed(GLFW.GLFW_KEY_E, 0, GLFW.GLFW_MOD_CONTROL);
            check(mc.screen instanceof EmojiPickerScreen, "Ctrl+E opens picker");
            search((EmojiPickerScreen) mc.screen).setValue("清晰测试");
            stage = 3; since = connected;
        } else if (stage == 3 && connected - since > 30) {
            capture(mc, "picker-search");
            mc.screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
            check(mc.screen == chat, "Choose returns to chat");
            check(input(chat).getValue().equals("left:清晰测试:right"), "Selection inserts at original cursor");
            chat.keyPressed(GLFW.GLFW_KEY_E, 0, GLFW.GLFW_MOD_CONTROL);
            stage = 4; since = connected;
        } else if (stage == 4 && connected - since > 30) {
            capture(mc, "picker");
            EmojiPickerScreen picker = (EmojiPickerScreen) mc.screen;
            int x = (picker.width - Math.min(600, picker.width - 16)) / 2 + 12;
            int y = (picker.height - Math.min(410, picker.height - 16)) / 2 + 95;
            picker.mouseClicked(x, y, 1);
            picker.onClose();
            check(input(chat).getValue().equals("left:清晰测试:right"), "ESC preserves draft");
            mc.options.guiScale().set(3); mc.resizeDisplay();
            chat.keyPressed(GLFW.GLFW_KEY_E, 0, GLFW.GLFW_MOD_CONTROL);
            stage = 5; since = connected;
        } else if (stage == 5 && connected - since > 30) {
            capture(mc, "picker-small");
            Path imported = root.resolve("build/qa-import/新表情.png"); Files.createDirectories(imported.getParent());
            ImageIO.write(picture(200, 150, 3), "png", imported.toFile());
            ((EmojiPickerScreen) mc.screen).onFilesDrop(List.of(imported));
            stage = 6; since = connected;
        } else if (stage == 6 && connected - since > 40 && EmojiLibrary.findName("新表情") != null) {
            capture(mc, "picker-import");
            mc.screen.onClose();
            for (String name : List.of("动图", "WebP动画")) {
                var asset = EmojiLibrary.findName(name);
                check(frames.getOrDefault(asset.id(), Set.of()).size() >= 2, name + " must advance GPU texture frames");
            }
            Files.writeString(evidence.resolve("animation.txt"), "PASS: GIF and animated WebP advanced actual GPU texture frames: " + frames);
            replacedId = EmojiLibrary.findName("清晰测试").id();
            ImageIO.write(picture(640, 320, 2), "png", EmojiLibrary.file(replacedId).toFile());
            EmojiLibrary.reload().thenRun(() -> mc.execute(() -> reloaded = true));
            stage = 7; since = connected;
        } else if (stage == 7 && reloaded && connected - since > 40) {
            check(EmojiLibrary.find(replacedId) != null, "Sent metadata survives local file replacement");
            check(!EmojiLibrary.findName("清晰测试").id().equals(replacedId), "Replacement must get a new UUID");
            if (!EmojiLibrary.hasFile(replacedId)) return;
            settings = new SettingsGameChecks(evidence);
            settings.start(mc, chat);
        }
    }

    private static EditBox input(ChatScreen screen) throws Exception {
        Field field = ChatScreen.class.getDeclaredField("input"); field.setAccessible(true); return (EditBox) field.get(screen);
    }

    private static EditBox search(EmojiPickerScreen picker) {
        return picker.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
    }

    private void capture(Minecraft mc, String name) throws Exception {
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(evidence.resolve(role + "-" + name + ".png"));
        }
    }

    private void finish(Minecraft mc, String result) {
        finished = true;
        try { Files.writeString(evidence.resolve(role + (settingsOnly ? "-focus" : "") + "-result.txt"), result); }
        catch (Exception e) { e.printStackTrace(); }
        System.out.println("MEMEEMOJI_QA " + role + " " + result);
        mc.stop();
    }

    private void fixtures() throws Exception {
        Path dir = EmojiLibrary.directory().resolve("emoji"); Files.createDirectories(dir);
        if (!Files.exists(dir.resolve("清晰测试.png"))) ImageIO.write(picture(640, 320, 0), "png", dir.resolve("清晰测试.png").toFile());
        for (int i = 1; i <= 5; i++) if (!Files.exists(dir.resolve("表情" + i + ".png"))) ImageIO.write(picture(160, 100, i), "png", dir.resolve("表情" + i + ".png").toFile());
        if (!Files.exists(dir.resolve("未发送.png"))) ImageIO.write(picture(200, 200, 7), "png", dir.resolve("未发送.png").toFile());
        if (!Files.exists(dir.resolve("动图.gif"))) Files.write(dir.resolve("动图.gif"), gif());
        if (!Files.exists(dir.resolve("WebP动画.webp"))) Files.write(dir.resolve("WebP动画.webp"), Base64.getDecoder().decode("UklGRrQAAABXRUJQVlA4WAoAAAACAAAAAwAAAwAAQU5JTQYAAAAAAAAAAABBTk1GKAAAAAAAAAAAAAMAAAMAAFAAAANWUDhMDwAAAC8DwAAABxD9j/4HIqL/AQBBTk1GKAAAAAEAAAEAAAAAAAAAAHgAAABWUDhMDwAAAC8AAAAAB9D/iP4HIqL/AQBBTk1GKAAAAAAAAAAAAAMAAAMAAMgAAABWUDhMDwAAAC8DwAAABxDR//4HIqL/AQA="));
        EmojiLibrary.reload();
    }

    private static boolean sentFixture(EmojiAsset asset) {
        return asset.name().startsWith("表情") || List.of("清晰测试", "动图", "WebP动画").contains(asset.name());
    }

    private void observeAnimation() throws Exception {
        Field cacheField = EmojiTextures.class.getDeclaredField("CACHE"); cacheField.setAccessible(true);
        Map<?, ?> cache = (Map<?, ?>) cacheField.get(null);
        for (var item : cache.entrySet()) {
            UUID id = (UUID) item.getKey();
            EmojiAsset asset = EmojiLibrary.find(id);
            if (asset == null || !asset.animated()) continue;
            Field frame = item.getValue().getClass().getDeclaredField("frame"); frame.setAccessible(true);
            int value = frame.getInt(item.getValue());
            if (value >= 0) frames.computeIfAbsent(id, unused -> new HashSet<>()).add(value);
        }
    }

    private static BufferedImage picture(int w, int h, int index) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        Color[] colors = {new Color(67, 184, 146), new Color(239, 106, 115), new Color(255, 208, 86), new Color(83, 174, 224)};
        g.setColor(colors[index % colors.length]); g.fillRect(0, 0, w, h);
        g.setColor(Color.WHITE); g.fillOval(w / 4, h / 7, w / 2, h * 3 / 4);
        g.setColor(new Color(30, 43, 42)); g.fillOval(w * 3 / 8, h / 3, Math.max(2, w / 30), Math.max(2, h / 14));
        g.fillOval(w * 5 / 8, h / 3, Math.max(2, w / 30), Math.max(2, h / 14));
        g.drawArc(w * 3 / 8, h / 3, w / 4, h / 3, 190, 160);
        g.setFont(new Font("SansSerif", Font.BOLD, Math.max(10, h / 12))); g.drawString("MEME " + index, 8, h - 8);
        g.dispose(); return image;
    }

    private static byte[] gif() throws Exception {
        var writer = ImageIO.getImageWritersByFormatName("gif").next();
        var bytes = new ByteArrayOutputStream();
        try (var out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out); writer.prepareWriteSequence(null);
            for (int i = 0; i < 4; i++) {
                var image = picture(160, 100, i);
                var metadata = writer.getDefaultImageMetadata(new javax.imageio.ImageTypeSpecifier(image), writer.getDefaultWriteParam());
                var tree = (IIOMetadataNode) metadata.getAsTree("javax_imageio_gif_image_1.0");
                var control = (IIOMetadataNode) tree.getElementsByTagName("GraphicControlExtension").item(0);
                control.setAttribute("delayTime", "30"); control.setAttribute("disposalMethod", "doNotDispose");
                if (i == 0) {
                    var extensions = new IIOMetadataNode("ApplicationExtensions"); var loop = new IIOMetadataNode("ApplicationExtension");
                    loop.setAttribute("applicationID", "NETSCAPE"); loop.setAttribute("authenticationCode", "2.0");
                    loop.setUserObject(new byte[]{1, 0, 0}); extensions.appendChild(loop); tree.appendChild(extensions);
                }
                metadata.setFromTree("javax_imageio_gif_image_1.0", tree);
                writer.writeToSequence(new IIOImage(image, null, metadata), writer.getDefaultWriteParam());
            }
            writer.endWriteSequence();
        } finally { writer.dispose(); }
        return bytes.toByteArray();
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
