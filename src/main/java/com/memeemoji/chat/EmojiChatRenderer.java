package com.memeemoji.chat;

import com.memeemoji.EmojiTokens;
import com.memeemoji.MemeEmoji;
import com.memeemoji.media.EmojiAsset;
import com.memeemoji.media.EmojiLibrary;
import com.memeemoji.media.EmojiTextures;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.ChatVisiblity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EmojiChatRenderer {
    private static final Pattern TOKENS = Pattern.compile(":(?:(?:emoji|sticker)/[0-9a-fA-F-]{36}|[^:\\s]{1,128}):");
    private static List<GuiMessage> snapshot = List.of();
    private static List<EmojiAsset> localSnapshot;
    private static List<Line> lines = List.of();
    private static int[] heights = new int[0];
    private static int cachedWidth = -1;
    private static int cachedNormal;
    private static int cachedInline;
    private static int cachedSticker;
    private static ChatComponent lastChat;
    private static int lastScroll;

    private EmojiChatRenderer() {
    }

    public static void invalidate() {
        cachedWidth = -1;
        snapshot = List.of();
        lines = List.of();
        heights = new int[0];
        lastChat = null;
    }

    public static boolean active(List<GuiMessage> messages) {
        if (!MemeEmoji.config().enabled) return false;
        for (GuiMessage message : messages) {
            if (hasImages(message.content().getString())) return true;
        }
        return false;
    }

    public static boolean hasImages(String text) {
        if (!MemeEmoji.config().enabled) return false;
        Matcher matcher = TOKENS.matcher(text);
        while (matcher.find()) {
            if (asset(matcher.group()) != null) return true;
        }
        return false;
    }

    public static List<RichLine> messageLayout(Component content, int width, int normal) {
        Builder builder = new Builder(Minecraft.getInstance().font, Math.max(12, width), normal,
                MemeEmoji.config().targetMaxSize(), MemeEmoji.config().stickerSize);
        content.visit((style, text) -> {
            builder.append(text, style);
            return Optional.empty();
        }, Style.EMPTY);
        return builder.finish();
    }

    private static EmojiAsset asset(String token) {
        Matcher reference = EmojiTokens.REFERENCE.matcher(token);
        if (reference.matches()) {
            try {
                return EmojiLibrary.find(UUID.fromString(reference.group(2)));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return EmojiLibrary.findName(token.substring(1, token.length() - 1));
    }

    private static void layout(ChatComponent chat, List<GuiMessage> messages) {
        Minecraft mc = Minecraft.getInstance();
        int width = Math.max(12, Mth.floor(chat.getWidth() / chat.getScale()));
        int normal = (int) (9 * (mc.options.chatLineSpacing().get() + 1));
        int inline = MemeEmoji.config().targetMaxSize();
        int sticker = MemeEmoji.config().stickerSize;
        List<EmojiAsset> local = EmojiLibrary.local();
        if (width == cachedWidth && normal == cachedNormal && inline == cachedInline && sticker == cachedSticker
                && localSnapshot == local && snapshot.equals(messages)) return;

        List<Line> next = new ArrayList<>();
        for (GuiMessage message : messages) {
            List<Line> messageLines = new ArrayList<>();
            for (RichLine line : messageLayout(message.content(), width, normal)) {
                messageLines.add(new Line(line.parts(), line.height(), message));
            }
            Collections.reverse(messageLines);
            next.addAll(messageLines);
        }
        snapshot = List.copyOf(messages);
        lines = List.copyOf(next);
        heights = lines.stream().mapToInt(Line::height).toArray();
        localSnapshot = local;
        cachedWidth = width;
        cachedNormal = normal;
        cachedInline = inline;
        cachedSticker = sticker;
    }

    public static int clampScroll(ChatComponent chat, List<GuiMessage> messages, int scroll) {
        layout(chat, messages);
        return Mth.clamp(scroll, 0, ChatGeometry.maxScroll(heights, chat.getHeight()));
    }

    public static int visibleCount(ChatComponent chat, List<GuiMessage> messages, int scroll) {
        layout(chat, messages);
        return ChatGeometry.visibleCount(heights, Math.max(0, scroll), chat.getHeight());
    }

    public static int messageLineCount(ChatComponent chat, GuiMessage message) {
        layout(chat, List.of(message));
        return lines.size();
    }

    public static void render(ChatComponent chat, List<GuiMessage> messages, int scroll, boolean unread,
                              GuiGraphics graphics, int tick, int mouseX, int mouseY, boolean focused) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.chatVisibility().get() == ChatVisiblity.HIDDEN) return;
        layout(chat, messages);
        lastChat = chat;
        lastScroll = scroll;
        double scale = chat.getScale();
        int bottom = Mth.floor((graphics.guiHeight() - 40) / scale);
        int viewport = chat.getHeight();
        double opacity = mc.options.chatOpacity().get() * 0.9 + 0.1;
        double background = mc.options.textBackgroundOpacity().get();
        double spacing = mc.options.chatLineSpacing().get();
        int textOffset = (int) Math.round(-8 * (spacing + 1) + 4 * spacing);
        int used = 0;
        graphics.pose().pushPose();
        graphics.pose().scale((float) scale, (float) scale, 1);
        graphics.pose().translate(4, 0, 0);
        graphics.enableScissor(0, Mth.floor((bottom - viewport) * scale), graphics.guiWidth(), graphics.guiHeight() - 40);
        for (int i = scroll; i < lines.size() && used < viewport; i++) {
            Line line = lines.get(i);
            int lineBottom = bottom - used;
            used += line.height();
            int age = tick - line.message().addedTime();
            if (age >= 200 && !focused) continue;
            double fade = focused ? 1 : Math.pow(Mth.clamp((1 - age / 200.0) * 10, 0, 1), 2);
            int alpha = (int) (255 * fade * opacity);
            if (alpha <= 3) continue;
            graphics.fill(-4, lineBottom - line.height(), cachedWidth + 8, lineBottom, (int) (255 * fade * background) << 24);
            GuiMessageTag tag = line.message().tag();
            if (tag != null) {
                graphics.fill(-4, lineBottom - line.height(), -2, lineBottom, tag.indicatorColor() | alpha << 24);
            }
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 50);
            for (Part part : line.parts()) {
                if (part.asset() == null) {
                    graphics.drawString(mc.font, Component.literal(part.text()).withStyle(part.style()),
                            part.x(), lineBottom + textOffset, 0xFFFFFF | alpha << 24);
                } else {
                    EmojiTextures.render(graphics, part.asset(), part.x(), lineBottom - part.height() - 2,
                            part.width(), part.height(), alpha);
                }
            }
            graphics.pose().popPose();
        }
        graphics.disableScissor();
        if (focused && lines.size() > 0) {
            int total = 0;
            int skipped = 0;
            for (int i = 0; i < heights.length; i++) {
                total += heights[i];
                if (i < scroll) skipped += heights[i];
            }
            if (total > viewport) {
                int thumb = Math.max(8, viewport * viewport / total);
                int y = bottom - thumb - (int) ((viewport - thumb) * (double) skipped / Math.max(1, total - viewport));
                graphics.fill(cachedWidth + 5, y, cachedWidth + 7, y + thumb, unread ? 0xCC65D6B4 : 0x997F8B87);
            }
        }
        long queued = mc.getChatListener().queueSize();
        if (queued > 0) {
            graphics.fill(-2, bottom, cachedWidth + 4, bottom + 9, (int) (255 * background) << 24);
            graphics.drawString(mc.font, Component.translatable("chat.queue", queued), 0, bottom + 1, 0xFFFFFF | (int) (128 * opacity) << 24);
        }
        graphics.pose().popPose();
    }

    private static Hit hit(ChatComponent chat, List<GuiMessage> messages, int scroll, double x, double y) {
        Minecraft mc = Minecraft.getInstance();
        if (!chat.isChatFocused() || mc.options.chatVisibility().get() == ChatVisiblity.HIDDEN) return null;
        layout(chat, messages);
        double localX = x / chat.getScale() - 4;
        double distance = Mth.floor((mc.getWindow().getGuiScaledHeight() - 40) / chat.getScale()) - y / chat.getScale();
        if (localX < -4 || localX > cachedWidth + 8) return null;
        int index = ChatGeometry.indexAt(heights, scroll, distance, chat.getHeight());
        if (index < 0) return null;
        Line line = lines.get(index);
        for (Part part : line.parts()) {
            if (localX >= part.x() && localX < part.x() + part.width()) return new Hit(line, part);
        }
        return new Hit(line, null);
    }

    public static Style styleAt(ChatComponent chat, List<GuiMessage> messages, int scroll, double x, double y) {
        Hit hit = hit(chat, messages, scroll, x, y);
        if (hit == null || hit.part() == null) return null;
        Part part = hit.part();
        if (part.asset() == null) return part.style();
        EmojiAsset asset = part.asset();
        return Style.EMPTY.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                Component.literal(asset.name() + " · " + asset.width() + " × " + asset.height())));
    }

    public static EmojiAsset assetAt(double x, double y) {
        if (lastChat == null) return null;
        Hit hit = hit(lastChat, snapshot, lastScroll, x, y);
        return hit == null || hit.part() == null ? null : hit.part().asset();
    }

    public static GuiMessageTag tagAt(ChatComponent chat, List<GuiMessage> messages, int scroll, double x, double y) {
        Hit hit = hit(chat, messages, scroll, x, y);
        return hit != null && x / chat.getScale() - 4 < 0 ? hit.line().message().tag() : null;
    }

    public record Part(String text, Style style, EmojiAsset asset, int x, int width, int height) { }
    public record RichLine(List<Part> parts, int height) {
        public int width() {
            return parts.stream().mapToInt(p -> p.x() + p.width()).max().orElse(0);
        }
    }
    private record Line(List<Part> parts, int height, GuiMessage message) { }
    private record Hit(Line line, Part part) { }

    private static final class Builder {
        private final Font font;
        private final int width;
        private final int normal;
        private final int inline;
        private final int sticker;
        private final List<RichLine> result = new ArrayList<>();
        private List<Part> parts = new ArrayList<>();
        private int x;
        private int imageHeight;

        Builder(Font font, int width, int normal, int inline, int sticker) {
            this.font = font;
            this.width = width;
            this.normal = normal;
            this.inline = inline;
            this.sticker = sticker;
        }

        void append(String text, Style style) {
            Matcher matcher = TOKENS.matcher(text);
            int offset = 0;
            while (matcher.find()) {
                text(text.substring(offset, matcher.start()), style);
                EmojiAsset asset = asset(matcher.group());
                if (asset == null) {
                    text(matcher.group(), style);
                } else {
                    boolean standalone = matcher.group().startsWith(":sticker/");
                    if (standalone && !parts.isEmpty()) flush();
                    int max = Math.min(width - 4, standalone ? sticker : inline);
                    double ratio = Math.min(1, (double) max / Math.max(asset.width(), asset.height()));
                    int w = Math.max(1, (int) Math.round(asset.width() * ratio));
                    int h = Math.max(1, (int) Math.round(asset.height() * ratio));
                    if (x + w + 2 > width && !parts.isEmpty()) flush();
                    parts.add(new Part(null, style, asset, x + 1, w, h));
                    x += w + 2;
                    imageHeight = Math.max(imageHeight, h);
                    if (standalone) flush();
                }
                offset = matcher.end();
            }
            text(text.substring(offset), style);
        }

        private void text(String text, Style style) {
            while (!text.isEmpty()) {
                int newline = text.indexOf('\n');
                String segment = newline >= 0 ? text.substring(0, newline) : text;
                if (!segment.isEmpty()) {
                    // Minecraft's splitter handles Unicode code points and styled glyph widths.
                    int count = font.getSplitter().plainIndexAtWidth(segment, Math.max(1, width - x), style);
                    if (count == 0) {
                        if (!parts.isEmpty()) {
                            flush();
                            continue;
                        }
                        count = Character.charCount(segment.codePointAt(0));
                    }
                    if (count < segment.length()) {
                        int space = segment.lastIndexOf(' ', count);
                        if (space > 0) count = space + 1;
                    }
                    String chunk = segment.substring(0, count);
                    int w = font.width(Component.literal(chunk).withStyle(style));
                    parts.add(new Part(chunk, style, null, x, w, 0));
                    x += w;
                    text = text.substring(count);
                    if (count < segment.length()) {
                        flush();
                        continue;
                    }
                }
                if (newline >= 0) {
                    flush();
                    text = text.substring(1);
                } else {
                    break;
                }
            }
        }

        private void flush() {
            result.add(new RichLine(List.copyOf(parts), ChatGeometry.lineHeight(normal, imageHeight)));
            parts = new ArrayList<>();
            x = 0;
            imageHeight = 0;
        }

        List<RichLine> finish() {
            if (!parts.isEmpty() || result.isEmpty()) flush();
            return result;
        }
    }
}
