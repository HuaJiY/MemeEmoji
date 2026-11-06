package com.memeemoji.chat;

public final class ChatGeometryTest {
    public static void main(String[] args) {
        require(ChatGeometry.lineHeight(9, 0) == 9, "Plain text must retain vanilla height");
        require(ChatGeometry.lineHeight(9, 24) == 28, "Inline image needs its own height");
        require(ChatGeometry.lineHeight(18, 0) == 18, "Vanilla line spacing must remain effective");
        int[] mixed = {9, 100, 9, 28, 9};
        require(ChatGeometry.indexAt(mixed, 0, 8, 120) == 0, "Text hit must not occupy the next image");
        require(ChatGeometry.indexAt(mixed, 0, 9, 120) == 1, "Image hit starts after text");
        require(ChatGeometry.indexAt(mixed, 0, 109, 120) == 2, "Text after image must remain clickable");
        require(ChatGeometry.indexAt(mixed, 0, 120, 120) == -1, "Clipped area must not be clickable");
        require(ChatGeometry.indexAt(mixed, 2, 10, 120) == 3, "Scrolling must move hit targets with pixels");
        require(ChatGeometry.visibleCount(mixed, 0, 120) == 4, "Viewport counts variable-height lines");
        require(ChatGeometry.maxScroll(mixed, 120) == 1, "Last page should not overscroll");
        require(ChatGeometry.maxScroll(new int[]{9, 9}, 120) == 0, "Short history must not scroll");
        System.out.println("Chat geometry checks passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
