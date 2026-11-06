package com.memeemoji.qa;

import com.memeemoji.EmojiTokens;
import com.memeemoji.chat.EmojiChatRenderer;
import com.memeemoji.gui.EmojiCollection;
import com.memeemoji.gui.EmojiPreviewScreen;
import com.memeemoji.media.EmojiAsset;
import com.memeemoji.media.EmojiLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.memeemoji.qa.SettingsGameChecks.*;

final class CollectionGameChecks {
    private final Path root, evidence;
    private final List<UUID> ids;
    private final ChatScreen chat;
    private int ticks, stage;
    private EmojiAsset png, gif, webp;
    private CompletableFuture<?> pending;
    private int count;
    private int sent;

    CollectionGameChecks(Path root, Path evidence, List<UUID> ids, ChatScreen chat) {
        this.root = root; this.evidence = evidence; this.ids = ids; this.chat = chat;
    }

    void start(Minecraft mc) {
        png = find("清晰测试"); gif = find("动图"); webp = find("WebP动画");
        mc.setScreen(new EmojiPreviewScreen(chat, png));
        click(mc.screen, "收藏到表情库");
    }

    boolean tick(Minecraft mc) throws Exception {
        if (++ticks > 2400) throw new AssertionError("Collection timed out at " + stage);
        if (stage == 0 && EmojiLibrary.personalCopy(png.id()) != null) {
            checkOriginal(png);
            capture(mc, "collection-preview");
            count = EmojiLibrary.local().size();
            pending = EmojiCollection.collect(png.id()); stage = 1;
        } else if (stage == 1 && pending.isDone()) {
            pending.join();
            check(EmojiLibrary.local().size() == count, "Repeated collect must not duplicate");
            mc.screen.onClose();
            mc.gui.getChat().addMessage(Component.literal(EmojiTokens.reference(gif.id(), true)));
            stage = 2; ticks = 0;
        } else if (stage == 2 && ticks > 20) {
            boolean clicked = false;
            for (int y = 0; y < chat.height - 35 && !clicked; y++) for (int x = 0; x < Math.min(320, chat.width) && !clicked; x++) {
                EmojiAsset hit = EmojiChatRenderer.assetAt(x, y);
                if (hit != null && hit.id().equals(gif.id())) clicked = chat.mouseClicked(x, y, 1);
            }
            check(clicked && mc.screen == chat, "Right-click collects without leaving chat");
            stage = 3;
        } else if (stage == 3 && EmojiLibrary.personalCopy(gif.id()) != null) {
            checkOriginal(gif);
            Path collision = root.resolve("build/qa-import/WebP动画.webp");
            Files.createDirectories(collision.getParent()); Files.write(collision, EmojiLibrary.read(webp.id()));
            pending = EmojiLibrary.importFiles(List.of(collision)); stage = 4;
        } else if (stage == 4 && pending.isDone()) {
            pending.join(); pending = EmojiCollection.collect(webp.id()); stage = 5;
        } else if (stage == 5 && pending.isDone()) {
            pending.join(); checkOriginal(webp);
            check(!EmojiLibrary.personalCopy(webp.id()).name().equals(webp.name()), "Name collision gets unique filename");
            count = EmojiLibrary.local().size();
            pending = EmojiLibrary.reload(); stage = 6;
        } else if (stage == 6 && pending.isDone()) {
            pending.join(); pending = EmojiCollection.collect(webp.id()); stage = 7;
        } else if (stage == 7 && pending.isDone()) {
            pending.join(); check(EmojiLibrary.local().size() == count, "Reload preserves collected identity");
            String preferences = Files.readString(EmojiLibrary.directory().resolve("library-ui.json"));
            for (EmojiAsset source : List.of(png, gif, webp)) {
                EmojiAsset copy = EmojiLibrary.personalCopy(source.id());
                check(preferences.contains(copy.id().toString()), "Saved copy is in favorites");
            }
            stage = 8; ticks = 0;
        } else if (stage == 8 && ticks > 0 && ticks % 40 == 0) {
            var outgoing = com.memeemoji.net.EmojiNetworkClient.class.getDeclaredField("OUTGOING"); outgoing.setAccessible(true);
            if (!((java.util.Map<?, ?>) outgoing.get(null)).isEmpty()) return false;
            if (sent < 3) {
                EmojiAsset source = List.of(png, gif, webp).get(sent++);
                chat.handleChatInput(EmojiTokens.reference(EmojiLibrary.personalCopy(source.id()).id(), false), true);
                return false;
            }
            var field = net.minecraft.client.gui.components.ChatComponent.class.getDeclaredField("allMessages"); field.setAccessible(true);
            @SuppressWarnings("unchecked") var messages = (List<net.minecraft.client.GuiMessage>) field.get(mc.gui.getChat());
            for (EmojiAsset source : List.of(png, gif, webp)) {
                String marker = EmojiTokens.reference(EmojiLibrary.personalCopy(source.id()).id(), false);
                check(messages.stream().anyMatch(m -> m.content().getString().contains(marker)), "Collected image was echoed by server");
            }
            Files.writeString(evidence.resolve("collection-result.txt"), "PASS: preview/right-click, original PNG/GIF/WebP bytes, repeated collection, collision, index reload, favorites and resend");
            return true;
        }
        return false;
    }

    private EmojiAsset find(String name) { return ids.stream().map(EmojiLibrary::find).filter(a -> a.name().equals(name)).findFirst().orElseThrow(); }
    private void checkOriginal(EmojiAsset source) throws Exception {
        EmojiAsset copy = EmojiLibrary.personalCopy(source.id());
        check(copy != null && !copy.id().equals(source.id()), "Collection creates independent local identity");
        check(Arrays.equals(EmojiLibrary.read(source.id()), EmojiLibrary.read(copy.id())), "Collection preserves original bytes");
        check(copy.animated() == source.animated(), "Collection preserves animation metadata");
    }
    private void capture(Minecraft mc, String name) throws Exception {
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(evidence.resolve(name + ".png")); }
    }
}
