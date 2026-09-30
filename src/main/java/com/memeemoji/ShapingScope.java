package com.memeemoji;

/**
 * 控制 StringDecomposerMixin 的 ShapingSink 是否生效。
 * ModernUI 的 TextLayoutProcessor.createVanillaLayout 会在布局计算期间触发我们的 mixin，
 * 导致 advances 数组长度与原始文本不一致（:001: 5字→1码位）。该路径只用于布局度量
 * 和 EditBox 渲染，聊天消息渲染走的是 FormattedCharSequence 路径不受影响。
 */
public final class ShapingScope {
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> true);

    private ShapingScope() {
    }

    public static boolean isActive() {
        return ACTIVE.get();
    }

    public static void setActive(boolean active) {
        ACTIVE.set(active);
    }
}
