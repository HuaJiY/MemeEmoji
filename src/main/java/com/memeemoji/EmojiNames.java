package com.memeemoji;

import java.util.Locale;

public final class EmojiNames {
    private EmojiNames() {
    }

    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public static String defaultName(String name, int limit) {
        StringBuilder cleaned = new StringBuilder();
        name.codePoints().forEach(c -> cleaned.appendCodePoint(Character.isWhitespace(c) || Character.isSpaceChar(c)
                || Character.isISOControl(c) || "<>:\"/\\|?*.".indexOf(c) >= 0 ? '_' : c));
        String result = cleaned.toString();
        if (result.isBlank() || result.chars().allMatch(c -> c == '_')) result = "表情";
        if (result.matches("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) result = "_" + result;
        result = truncate(result, limit);
        return result.isEmpty() ? truncate("表情", limit) : result;
    }

    public static String truncate(String name, int limit) {
        int end = Math.min(name.length(), limit);
        if (end > 0 && Character.isHighSurrogate(name.charAt(end - 1))) end--;
        return name.substring(0, end);
    }

    public static boolean isUsable(String name, int maxNameLength) {
        if (name.isEmpty() || name.length() > maxNameLength) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == ':' || Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }
}
