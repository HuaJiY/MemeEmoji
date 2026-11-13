package com.memeemoji;

import com.memeemoji.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class MemeEmoji {
    public static final String MOD_ID = "memeemoji";
    public static final Logger LOGGER = LoggerFactory.getLogger("MemeEmoji");
    private static volatile MemeEmojiConfig config;

    public static Path configDir() {
        return Platform.configDir().resolve(MOD_ID);
    }

    public static Path emojiDir() {
        return configDir().resolve("emoji");
    }

    public static Path cacheDir() {
        return configDir().resolve(".cache");
    }

    public static void invalidateConfig() {
        config = null;
    }

    public static void applyConfig(MemeEmojiConfig next) throws java.io.IOException {
        MemeEmojiConfig saved = next.copy();
        saved.normalize();
        saved.saveOrThrow(configDir().resolve("config.json"));
        config = saved;
    }

    public static MemeEmojiConfig config() {
        MemeEmojiConfig current = config;
        if (current == null) {
            synchronized (MemeEmoji.class) {
                current = config;
                if (current == null) {
                    current = MemeEmojiConfig.load(configDir().resolve("config.json"));
                    config = current;
                }
            }
        }
        return current;
    }

    public static void init() {
        config();
    }
}
