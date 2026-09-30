package com.memeemoji.registry;

import com.memeemoji.EmojiRegistry;
import com.memeemoji.MemeEmoji;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSink;

import java.util.Arrays;

/**
 * 把文本流里的 :名字: 换成一个私用区码位，让它走表情字体。
 *
 * <p>字符要等到确定匹配失败（或者文本结束）才能吐给下游，否则 ":猫猫:" 打到一半就会被当成普通文字渲染出来；
 * 匹配过程中只记位置和样式，真正输出时才重新算位置，下游看到的顺序和字符位置都跟原文一致。
 */
public final class ShapingSink implements FormattedCharSink {
    private static final int INITIAL_BUFFER = 16;
    private static final ThreadLocal<ShapingSink> POOL = ThreadLocal.withInitial(ShapingSink::new);

    private FormattedCharSink delegate;
    private ShapingTable table;
    private int[] bufPositions = new int[INITIAL_BUFFER];
    private Style[] bufStyles = new Style[INITIAL_BUFFER];
    private int[] bufCodepoints = new int[INITIAL_BUFFER];
    private int bufLen;
    private ShapingTable.Node currentNode;
    private int matchLen;
    private int matchPua;
    private boolean stopped;
    private boolean inUse;
    private Style cachedPlainStyle;
    private Style cachedEmojiStyle;

    private ShapingSink() {
    }

    public static ShapingSink acquire(FormattedCharSink delegate, ShapingTable table) {
        ShapingSink sink = POOL.get();
        if (sink.inUse) {
            ShapingSink fresh = new ShapingSink();
            fresh.reset(delegate, table);
            fresh.inUse = true;
            return fresh;
        }
        sink.reset(delegate, table);
        sink.inUse = true;
        return sink;
    }

    private void reset(FormattedCharSink delegate, ShapingTable table) {
        this.delegate = delegate;
        this.table = table;
        this.currentNode = table.root();
        this.bufLen = 0;
        this.matchLen = 0;
        this.matchPua = 0;
        this.stopped = false;
        this.cachedPlainStyle = null;
        this.cachedEmojiStyle = null;
        int needed = Math.max(INITIAL_BUFFER, table.maxDepth() + 1);
        if (bufPositions.length < needed) {
            bufPositions = new int[needed];
            bufStyles = new Style[needed];
            bufCodepoints = new int[needed];
        }
        // 缓冲区是复用的，上一次渲染的样式不能继续被引用，否则会拖住整棵样式树的回收。
        Arrays.fill(bufStyles, null);
    }

    public void release() {
        this.delegate = null;
        this.table = null;
        this.currentNode = null;
        this.cachedPlainStyle = null;
        this.cachedEmojiStyle = null;
        for (int i = 0; i < bufStyles.length; i++) {
            bufStyles[i] = null;
        }
        this.inUse = false;
    }

    @Override
    public boolean accept(int position, Style style, int codepoint) {
        if (stopped) {
            return false;
        }
        ShapingTable.Node next = currentNode.next(Character.toLowerCase(codepoint));
        if (next == null && bufLen > 0) {
            if (!flushBuffer()) {
                return false;
            }
            next = table.root().next(Character.toLowerCase(codepoint));
        }
        if (next != null) {
            if (bufLen >= bufCodepoints.length) {
                if (!flushBuffer()) {
                    return false;
                }
                next = table.root().next(Character.toLowerCase(codepoint));
                if (next == null) {
                    return passthrough(position, style, codepoint);
                }
            }
            bufPositions[bufLen] = position;
            bufStyles[bufLen] = style;
            bufCodepoints[bufLen] = codepoint;
            bufLen++;
            currentNode = next;
            if (next.terminal() != null) {
                matchLen = bufLen;
                matchPua = next.terminal();
            }
            return true;
        }
        return passthrough(position, style, codepoint);
    }

    private boolean passthrough(int position, Style style, int codepoint) {
        currentNode = table.root();
        boolean ok = delegate.accept(position, styleFor(style, codepoint), codepoint);
        if (!ok) {
            stopped = true;
        }
        return ok;
    }

    private boolean flushBuffer() {
        if (bufLen == 0) {
            return true;
        }
        int emitted = 0;
        if (matchLen > 0) {
            int end = bufPositions[matchLen - 1] + Character.charCount(bufCodepoints[matchLen - 1]);
            int position = Math.max(0, end - Character.charCount(matchPua));
            if (!delegate.accept(position, emojiStyle(bufStyles[0]), matchPua)) {
                stopped = true;
                return false;
            }
            emitted = matchLen;
        }
        for (int i = emitted; i < bufLen; i++) {
            if (!delegate.accept(bufPositions[i], styleFor(bufStyles[i], bufCodepoints[i]), bufCodepoints[i])) {
                stopped = true;
                return false;
            }
        }
        bufLen = 0;
        matchLen = 0;
        currentNode = table.root();
        return true;
    }

    private Style styleFor(Style style, int codepoint) {
        return EmojiRegistry.INSTANCE.rendersInEmojiFont(codepoint) ? emojiStyle(style) : style;
    }

    private Style emojiStyle(Style style) {
        if (style != cachedPlainStyle) {
            cachedPlainStyle = style;
            cachedEmojiStyle = style.withFont(MemeEmoji.EMOJI_FONT);
        }
        return cachedEmojiStyle;
    }

    public boolean finish() {
        return flushBuffer();
    }
}
