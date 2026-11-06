package com.memeemoji.chat;

public final class ChatGeometry {
    private ChatGeometry() {
    }

    public static int lineHeight(int normalHeight, int imageHeight) {
        return imageHeight == 0 ? normalHeight : Math.max(normalHeight, imageHeight + 4);
    }

    public static int indexAt(int[] heights, int start, double distance, int viewport) {
        if (distance < 0 || distance >= viewport) return -1;
        int used = 0;
        for (int i = start; i < heights.length; i++) {
            if (used >= viewport) break;
            used += heights[i];
            if (distance < used) return i;
        }
        return -1;
    }

    public static int visibleCount(int[] heights, int start, int viewport) {
        int used = 0;
        int count = 0;
        for (int i = start; i < heights.length && used < viewport; i++) {
            used += heights[i];
            count++;
        }
        return Math.max(1, count);
    }

    public static int maxScroll(int[] heights, int viewport) {
        int used = 0;
        for (int i = heights.length - 1; i >= 0; i--) {
            used += heights[i];
            if (used >= viewport) return i;
        }
        return 0;
    }
}
