package com.memeemoji;

import com.memeemoji.net.EmojiSync;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Locale;

public final class MemeEmoji implements ModInitializer {
    public static final String MOD_ID = "memeemoji";
    public static final Logger LOGGER = LoggerFactory.getLogger("MemeEmoji");

    /** 生成资源包里表情字体和贴图使用的固定名字。 */
    public static final ResourceLocation EMOJI_FONT = ResourceLocation.fromNamespaceAndPath(MOD_ID, "emoji");

    /** 图集列数，1.21.1 的 BitmapProvider 按 chars 每行的码位数切列。 */
    public static final int COLUMNS = 16;

    /** 码位从 BMP 私用区低位开始分配，保证一个码位就是一个 char。 */
    public static final int PUA_BASE = 0xE000;
    public static final int PUA_LAST = 0xF8FF;
    public static final int MAX_EMOJI = PUA_LAST - PUA_BASE + 1;

    public static final String GENERATED_PACK_ID = MOD_ID + "/generated";

    private static volatile MemeEmojiConfig config;

    public static Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
    }

    public static Path emojiDir() {
        return configDir().resolve("emoji");
    }

    public static Path cacheDir() {
        return configDir().resolve(".cache");
    }

    public static Path generatedPackDir() {
        return configDir().resolve("generated");
    }

    /** 图集精灵的边长，根据 emojiSize 预设决定。 */
    public static int cell() {
        return switch (config().emojiSize) {
            case "medium" -> 32;
            case "large" -> 48;
            default -> 18; // small
        };
    }

    /** 表情在文字里的逻辑高度，对应 font.json 的 height 字段。 */
    public static int glyphHeight() {
        return switch (config().emojiSize) {
            case "medium" -> 16;
            case "large" -> 24;
            default -> 9; // small
        };
    }

    /** 表情在文字里的基线偏移，对应 font.json 的 ascent 字段。 */
    public static int glyphAscent() {
        return switch (config().emojiSize) {
            case "medium" -> 13;
            case "large" -> 19;
            default -> 8; // small
        };
    }

    /** 表情选择界面每个格子的大小。 */
    public static int pickerCell() {
        return switch (config().emojiSize) {
            case "medium" -> 52;
            case "large" -> 68;
            default -> 38; // small
        };
    }

    /**
     * 配置可能被更早的调用点读到（资源包列表早于客户端入口点初始化），所以这里做惰性加载而不是只在 onInitialize 里赋值。
     */
    public static MemeEmojiConfig config() {
        MemeEmojiConfig loaded = config;
        if (loaded == null) {
            synchronized (MemeEmoji.class) {
                loaded = config;
                if (loaded == null) {
                    loaded = MemeEmojiConfig.load(configDir().resolve("config.json"));
                    config = loaded;
                }
            }
        }
        return loaded;
    }

    @Override
    public void onInitialize() {
        config();
        WebpSupport.ensureRegistered();
        EmojiSync.initCommon();
        EmojiSync.initServer();
        MemeEmojiCommand.register();
    }
}