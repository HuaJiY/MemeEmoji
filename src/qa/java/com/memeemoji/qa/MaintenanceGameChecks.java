package com.memeemoji.qa;

import com.memeemoji.EmojiTokens;
import com.memeemoji.gui.EmojiCollection;
import com.memeemoji.gui.EmojiPickerScreen;
import com.memeemoji.media.EmojiAsset;
import com.memeemoji.media.EmojiLibrary;
import com.memeemoji.media.EmojiTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.memeemoji.qa.SettingsGameChecks.*;

final class MaintenanceGameChecks {
    private final Path evidence;
    private EmojiPickerScreen picker;
    private EmojiAsset png, copy;
    private List<EmojiAsset> batch;
    private Map<UUID, byte[]> originals;
    private Path originalFile;
    private byte[] originalBytes;
    private int stage, ticks, since;
    private CompletableFuture<?> pending;
    private final String prefix = com.memeemoji.platform.Platform.isModLoaded("modernui") ? "modernui-" : "vanilla-";

    MaintenanceGameChecks(Path evidence) { this.evidence = evidence; }

    void start(Minecraft mc) throws Exception {
        png = EmojiLibrary.findName("清晰测试");
        originalFile = EmojiLibrary.file(png.id()); originalBytes = EmojiLibrary.read(png.id());
        mc.gui.getChat().addMessage(Component.literal(EmojiTokens.reference(png.id(), true)));
        picker = new EmojiPickerScreen(new ChatScreen(""), token -> {});
        mc.setScreen(picker);
        search(png.name());
        cell(1); picker.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        String prefs = preferences(); check(prefs.contains(png.id().toString()), "Fixture favorite/recent persisted");
        click(picker, "管理"); cell(0); click(picker, "删除所选");
    }

    boolean tick(Minecraft mc) throws Exception {
        mc.getToasts().clear();
        if (++ticks > 1800) throw new AssertionError("Maintenance timed out at " + stage);
        if (stage == 0 && ticks > 20) {
            capture(mc, "delete-confirm");
            click(mc.screen, "取消");
            check(mc.screen == picker && Files.exists(originalFile), "Cancel keeps original file");
            since = ticks; stage = 8;
        } else if (stage == 8 && ticks - since > 10) {
            capture(mc, "manage");
            click(picker, "删除所选"); click(mc.screen, "永久删除");
            stage = 1;
        } else if (stage == 1 && EmojiLibrary.personalCopy(png.id()) == null) {
            check(Files.notExists(originalFile), "Permanent deletion removes original file");
            check(Arrays.equals(originalBytes, EmojiLibrary.read(png.id())), "Deleted image retains original cached bytes");
            if (preferences().contains(png.id().toString())) return false;
            pending = EmojiLibrary.reload(); stage = 2;
        } else if (stage == 2 && pending.isDone()) {
            pending.join(); check(EmojiLibrary.personalCopy(png.id()) == null, "Reload does not resurrect deleted item");
            pending = EmojiCollection.collect(png.id()); stage = 3;
        } else if (stage == 3 && pending.isDone()) {
            pending.join(); copy = EmojiLibrary.personalCopy(png.id());
            check(copy != null && !copy.id().equals(png.id()), "Deleted chat image can be recollected with fresh identity");
            batch = List.of(copy, EmojiLibrary.findName("动图"), EmojiLibrary.findName("WebP动画"));
            var bytes = new java.util.HashMap<UUID, byte[]>();
            for (EmojiAsset asset : batch) bytes.put(asset.id(), EmojiLibrary.read(asset.id()));
            originals = Map.copyOf(bytes);
            picker.tick();
            search("");
            for (EmojiAsset asset : batch) { search(asset.name()); cell(0); }
            mc.options.guiScale().set(4); mc.resizeDisplay();
            since = ticks; stage = 4;
        } else if (stage == 4 && ticks - since > 20) {
            capture(mc, "manage-small");
            click(picker, "删除所选");
            since = ticks; stage = 9;
        } else if (stage == 9 && ticks - since > 10) {
            capture(mc, "delete-small");
            click(mc.screen, "永久删除"); stage = 5;
        } else if (stage == 5 && batch.stream().noneMatch(a -> EmojiLibrary.local().stream().anyMatch(b -> b.id().equals(a.id())))) {
            if (batch.stream().anyMatch(a -> preferences().contains(a.id().toString()))) return false;
            for (EmojiAsset asset : batch) check(Arrays.equals(originals.get(asset.id()), EmojiLibrary.read(asset.id())), "Batch preserves PNG/GIF/WebP bytes");
            check(EmojiLibrary.personalCopy(png.id()) == null, "Deleting collected copy clears source mapping");
            pending = EmojiCollection.collect(png.id()); stage = 6;
        } else if (stage == 6 && pending.isDone()) {
            pending.join(); check(EmojiLibrary.personalCopy(png.id()) != null, "Collected copy can be saved again after deletion");
            mc.options.guiScale().set(2); mc.resizeDisplay();
            picker.onClose();
            for (EmojiAsset asset : batch) mc.gui.getChat().addMessage(Component.literal(EmojiTokens.reference(asset.id(), false)));
            since = ticks; stage = 7;
        } else if (stage == 7 && ticks - since > 40) {
            for (EmojiAsset asset : batch) check(EmojiTextures.failure(asset.id()) == null, "Deleted history decodes");
            capture(mc, "deleted-history");
            Files.writeString(evidence.resolve(prefix + "deletion-result.txt"), "PASS: picker management/cancel, permanent single and batch removal, preference cleanup, reload, original PNG/GIF/WebP history cache, collected mapping cleanup/recollection and GUI scales 2/4");
            return true;
        }
        return false;
    }

    private String preferences() {
        try { return Files.readString(EmojiLibrary.directory().resolve("library-ui.json")); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private void search(String value) {
        picker.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow().setValue(value);
    }
    private void cell(int button) {
        int x = (picker.width - Math.min(600, picker.width - 16)) / 2 + 20;
        int y = (picker.height - Math.min(410, picker.height - 16)) / 2 + 100;
        check(picker.mouseClicked(x, y, button), "Click library cell");
    }
    private void capture(Minecraft mc, String name) throws Exception {
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(evidence.resolve(prefix + name + ".png")); }
    }
}
