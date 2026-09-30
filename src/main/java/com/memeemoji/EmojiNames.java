package com.memeemoji;

import java.util.Locale;

public final class EmojiNames {
    private EmojiNames() {
    }

    /** 名字匹配大小写不敏感，中文名不受影响。 */
    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public static boolean isUsable(String name, int maxNameLength) {
        if (name.isEmpty() || name.length() > maxNameLength) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == ':' || Character.isWhitespace(c) || Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }
}
