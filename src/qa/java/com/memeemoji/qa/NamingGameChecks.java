package com.memeemoji.qa;

import com.memeemoji.EmojiTokens;
import com.memeemoji.MemeEmoji;
import com.memeemoji.gui.EmojiPickerScreen;
import com.memeemoji.media.EmojiAsset;
import com.memeemoji.media.EmojiLibrary;
import com.memeemoji.net.EmojiNetworkClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.memeemoji.qa.SettingsGameChecks.*;

final class NamingGameChecks {
    private final Path root, evidence;
    private final String prefix = FabricLoader.getInstance().isModLoaded("modernui") ? "modernui-" : "vanilla-";
    private EmojiPickerScreen picker;
    private EmojiAsset original;
    private Path originalPath;
    private byte[] bytes;
    private int stage, ticks, since;
    private CompletableFuture<?> pending;
    private ChatScreen chat;

    NamingGameChecks(Path root, Path evidence) { this.root = root; this.evidence = evidence; }

    void start(Minecraft mc) throws Exception {
        original = EmojiLibrary.findName("清晰测试");
        originalPath = EmojiLibrary.file(original.id());
        bytes = EmojiLibrary.read(original.id());
        Path imports = Files.createDirectories(root.resolve("build/qa-naming-import"));
        List<Path> inputs = List.of(imports.resolve("my meme.png"), imports.resolve("my_meme.png"),
                imports.resolve("a".repeat(60) + ".png"), imports.resolve("　.png"));
        for (Path path : inputs) Files.write(path, bytes);
        picker = new EmojiPickerScreen(new ChatScreen(""), token -> {});
        mc.setScreen(picker);
        picker.onFilesDrop(inputs);
    }

    boolean tick(Minecraft mc) throws Exception {
        mc.getToasts().clear();
        if (++ticks > 1600) throw new AssertionError("Naming QA timed out at " + stage);
        if (stage == 0 && ticks > 30 && EmojiLibrary.findName("my_meme_2") != null) {
            check(EmojiLibrary.findName("my_meme") != null && EmojiLibrary.findName("a".repeat(32)) != null
                    && EmojiLibrary.findName("表情") != null, "Import cleans, truncates and assigns default names");
            edit(picker, "搜索表情").setValue(original.name());
            click(picker, "管理");
            cell();
            click(picker, "修改名称");
            edit(mc.screen, "表情名称").setValue("cancelled");
            click(mc.screen, "取消");
            check(EmojiLibrary.findName("cancelled") == null && EmojiLibrary.findName(original.name()) != null, "Cancel does not rename");
            click(picker, "修改名称");
            edit(mc.screen, "表情名称").setValue("my_meme");
            click(mc.screen, "保存");
            since = ticks; stage = 1;
        } else if (stage == 1 && ticks - since > 15) {
            check(mc.screen != picker && widget(mc.screen, "保存").active, "Duplicate stays in editable dialog");
            check(!EmojiLibrary.findName("my_meme").id().equals(original.id()), "Duplicate does not replace another asset");
            capture(mc, "rename-error");
            edit(mc.screen, "表情名称").setValue("bad name");
            click(mc.screen, "保存");
            since = ticks; stage = 2;
        } else if (stage == 2 && ticks - since > 15) {
            check(mc.screen != picker && widget(mc.screen, "保存").active, "Invalid names can be corrected");
            edit(mc.screen, "表情名称").setValue("smile");
            mc.options.guiScale().set(4); mc.resizeDisplay();
            since = ticks; stage = 3;
        } else if (stage == 3 && ticks - since > 15) {
            check(edit(mc.screen, "表情名称").getValue().equals("smile"), "Resize preserves rename draft");
            capture(mc, "rename-small");
            mc.screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
            stage = 4;
        } else if (stage == 4 && mc.screen == picker && EmojiLibrary.findName("SMILE") != null) {
            check(EmojiLibrary.findName("smile").id().equals(original.id()), "Rename retains UUID");
            check(EmojiLibrary.find(original.id()).equals(original), "Wire metadata remains immutable");
            check(EmojiLibrary.file(original.id()).equals(originalPath) && Arrays.equals(bytes, EmojiLibrary.read(original.id())), "Rename does not move or alter image");
            check(EmojiLibrary.findName(original.name()) == null, "Old name stops matching");
            Field snapshot = EmojiLibrary.class.getDeclaredField("snapshot"); snapshot.setAccessible(true);
            var constructor = snapshot.getType().getDeclaredConstructors()[0]; constructor.setAccessible(true);
            snapshot.set(null, constructor.newInstance(List.of(), Map.of(), Map.of(), Map.of(), Map.of()));
            pending = EmojiLibrary.reload(); stage = 5;
        } else if (stage == 5 && pending.isDone()) {
            pending.join();
            check(EmojiLibrary.findName("smile").id().equals(original.id()) && EmojiLibrary.find(original.id()).equals(original), "Cold index reload retains name and metadata");
            picker.tick(); edit(picker, "搜索表情").setValue("smile");
            capture(mc, "renamed-picker");
            chat = new ChatScreen(""); mc.setScreen(chat);
            input().setValue(":smile: :grin:");
            check(input().getValue().startsWith(":smile:"), "Personal shortcode survives built-in conversion");
            if (prefix.equals("modernui-")) check(!input().getValue().contains(":grin:"), "Unshadowed built-in shortcode still converts");
            MemeEmoji.config().enabled = false;
            input().setValue(":smile:");
            if (prefix.equals("modernui-")) check(!input().getValue().contains(":smile:"), "Disabled mod leaves built-in shortcode conversion intact");
            MemeEmoji.config().enabled = true;
            input().setValue(":SMILE: :grin:");
            check(input().getValue().startsWith(":SMILE:"), "Custom lookup is case insensitive");
            chat.handleChatInput(input().getValue(), true);
            stage = 6; since = ticks;
        } else if (stage == 6 && ticks - since > 30 && !outgoing()) {
            check(historyContains(EmojiTokens.reference(original.id(), false)), "Custom name sends UUID through real chat entry");
            pending = EmojiLibrary.rename(original.id(), "改名后"); stage = 7;
        } else if (stage == 7 && pending.isDone()) {
            pending.join();
            chat.handleChatInput(":改名后:", true);
            stage = 8; since = ticks;
        } else if (stage == 8 && ticks - since > 30 && !outgoing()) {
            check(!historyContains("缓存失败") && !historyContains("冲突"), "Resend after rename has no server metadata conflict");
            check(history().filter(message -> message.content().getString().contains(EmojiTokens.reference(original.id(), false))).count() == 2,
                    "Both sends reach the server and return to chat");
            mc.options.guiScale().set(2); mc.resizeDisplay();
            picker = new EmojiPickerScreen(chat, token -> {}); mc.setScreen(picker);
            edit(picker, "搜索表情").setValue("改名后"); click(picker, "管理"); cell(); click(picker, "修改名称");
            since = ticks; stage = 9;
        } else if (stage == 9 && ticks - since > 20) {
            capture(mc, "rename");
            click(mc.screen, "取消");
            return true;
        }
        return false;
    }

    private EditBox input() {
        return chat.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow();
    }

    private boolean historyContains(String text) throws Exception {
        return history().anyMatch(message -> message.content().getString().contains(text));
    }

    private java.util.stream.Stream<net.minecraft.client.GuiMessage> history() throws Exception {
        Field field = net.minecraft.client.gui.components.ChatComponent.class.getDeclaredField("allMessages");
        field.setAccessible(true);
        return ((List<?>) field.get(Minecraft.getInstance().gui.getChat())).stream().map(net.minecraft.client.GuiMessage.class::cast);
    }

    private boolean outgoing() throws Exception {
        Field field = EmojiNetworkClient.class.getDeclaredField("OUTGOING"); field.setAccessible(true);
        return !((Map<?, ?>) field.get(null)).isEmpty();
    }

    private void cell() {
        int x = (picker.width - Math.min(600, picker.width - 16)) / 2 + 20;
        int y = (picker.height - Math.min(410, picker.height - 16)) / 2 + 100;
        check(picker.mouseClicked(x, y, 0), "Select personal image");
    }

    private void capture(Minecraft mc, String name) throws Exception {
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(evidence.resolve(prefix + name + ".png")); }
    }
}
