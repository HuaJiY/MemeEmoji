package com.memeemoji.qa;

import com.memeemoji.EmojiTokens;
import com.memeemoji.chat.EmojiChatRenderer;
import com.memeemoji.media.EmojiLibrary;
import com.memeemoji.media.EmojiTextures;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.talkbubbles.TalkBubbles;
import net.talkbubbles.accessor.AbstractClientPlayerEntityAccessor;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class TalkBubblesGameChecks {
    private final Path evidence;
    private int ticks;
    private int stage;
    private int since;
    private boolean finished;
    private String mixed;
    private final Map<UUID, Set<Integer>> frames = new HashMap<>();

    TalkBubblesGameChecks(Path evidence) { this.evidence = evidence; }

    void start() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (finished) return;
            try { tick(mc); }
            catch (Throwable failure) {
                failure.printStackTrace();
                finish(mc, "FAIL: " + failure);
            }
        });
    }

    private void tick(Minecraft mc) throws Exception {
        if (mc.player == null || mc.level == null) return;
        if (++ticks > 1800) throw new AssertionError("TalkBubbles QA timed out at stage " + stage);
        var bubble = (AbstractClientPlayerEntityAccessor) mc.player;
        if (ticks == 1) {
            mc.options.pauseOnLostFocus = false;
            TalkBubbles.CONFIG.showOwnBubble = true;
            TalkBubbles.CONFIG.chatTime = 1200;
            TalkBubbles.CONFIG.chatScale = .6f;
            mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            mc.options.fov().set(90);
            mc.player.setXRot(0);
        }
        observeAnimation();
        if (stage == 0 && ticks > 100 && EmojiLibrary.findName("清晰测试") != null) {
            send("普通文字气泡"); next(1);
        } else if (stage == 1 && elapsed() > 40) {
            check(bubble.getChatText() != null && bubble.getHeight() == 1, "Original plain-text bubble must remain one line");
            capture(mc, "plain");
            mixed = "你好 " + token("清晰测试", false) + " 动图 " + token("动图", false) + " " + token("WebP动画", false);
            send(mixed); next(2);
        } else if (stage == 2 && elapsed() > 100) {
            check(bubble.getChatText().equals(List.of(mixed)), "Network image message must target sender by UUID and preserve body");
            var layout = EmojiChatRenderer.messageLayout(Component.literal(mixed), TalkBubbles.CONFIG.maxChatWidth, 9);
            check(layout.stream().allMatch(l -> l.width() <= TalkBubbles.CONFIG.maxChatWidth), "Bubble content must fit maxChatWidth");
            check(layout.stream().anyMatch(l -> l.height() > 9), "Only image rows should grow");
            for (String name : List.of("动图", "WebP动画")) {
                check(frames.getOrDefault(EmojiLibrary.findName(name).id(), Set.of()).size() >= 2, name + " must animate inside world bubble");
            }
            capture(mc, "inline");
            send(token("清晰测试", true)); next(3);
        } else if (stage == 3 && elapsed() > 60) {
            check(bubble.getChatText().equals(List.of(token("清晰测试", true))), "Standalone image must preserve original marker");
            capture(mc, "sticker");
            send("图片之后的普通文字"); next(4);
        } else if (stage == 4 && elapsed() > 40) {
            check(bubble.getHeight() == 1 && bubble.getChatText().stream().noneMatch(EmojiChatRenderer::hasImages), "Plain text after an image must restore original bubble dimensions");
            capture(mc, "after");
            TalkBubbles.CONFIG.showOwnBubble = false;
            send(token("清晰测试", false)); next(5);
        } else if (stage == 5 && elapsed() > 40) {
            check(bubble.getChatText().stream().noneMatch(EmojiChatRenderer::hasImages), "showOwnBubble=false must not replace own bubble");
            TalkBubbles.CONFIG.chatTime = 1;
            next(6);
        } else if (stage == 6 && elapsed() > 10) {
            check(bubble.getChatText() == null, "Original TalkBubbles expiry must still clear messages");
            finish(mc, "PASS: actual network receipt, plain/inline/sticker/plain sizing, original width/config/expiry, GIF and WebP GPU frames=" + frames);
        }
    }

    private void observeAnimation() throws Exception {
        Field cacheField = EmojiTextures.class.getDeclaredField("CACHE"); cacheField.setAccessible(true);
        for (var item : ((Map<?, ?>) cacheField.get(null)).entrySet()) {
            UUID id = (UUID) item.getKey();
            var asset = EmojiLibrary.find(id);
            if (asset == null || !asset.animated()) continue;
            Field frame = item.getValue().getClass().getDeclaredField("frame"); frame.setAccessible(true);
            int value = frame.getInt(item.getValue());
            if (value >= 0) frames.computeIfAbsent(id, unused -> new HashSet<>()).add(value);
        }
    }

    private static String token(String name, boolean sticker) {
        return EmojiTokens.reference(EmojiLibrary.findName(name).id(), sticker);
    }

    private static void send(String text) {
        Minecraft mc = Minecraft.getInstance();
        ChatScreen chat = new ChatScreen("");
        mc.setScreen(chat);
        chat.handleChatInput(text, true);
        mc.setScreen(null);
    }
    private void next(int value) { stage = value; since = ticks; }
    private int elapsed() { return ticks - since; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private void capture(Minecraft mc, String name) throws Exception {
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            image.writeToFile(evidence.resolve("talkbubbles-" + name + ".png"));
        }
    }

    private void finish(Minecraft mc, String result) {
        finished = true;
        try { Files.writeString(evidence.resolve("talkbubbles-result.txt"), result); }
        catch (Exception e) { e.printStackTrace(); }
        System.out.println("MEMEEMOJI_QA talkbubbles " + result);
        mc.stop();
    }
}
