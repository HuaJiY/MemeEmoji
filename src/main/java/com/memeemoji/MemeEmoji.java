package com.memeemoji;

import com.memeemoji.net.EmojiSync;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class MemeEmoji implements ModInitializer {
    public static final String MOD_ID = "memeemoji";
    public static final Logger LOGGER = LoggerFactory.getLogger("MemeEmoji");

    /** 生成资源包里表情字体和贴图使用的固定名字。 */
    public static final ResourceLocation EMOJI_FONT = ResourceLocation.fromNamespaceAndPath(MOD_ID, "emoji");

    /** 码位从 BMP 私用区低位开始分配，保证一个码位就是一个 char。 */
    public static final int PUA_BASE = 0xE000;
    public static final int PUA_LAST = 0xF8FF;
    public static final int MAX_EMOJI = PUA_LAST - PUA_BASE + 1;

    public static final String GENERATED_PACK_ID = MOD_ID + "/generated";

    /** 启动时由 MemeEmojiClient 根据预设算出。 */
    private static volatile int computedMaxSize = 48;

    /** 扫描后记录的最大表情高度，用于计算聊天气泡行高。 */
    private static volatile int maxEmojiHeight = 9;

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

    /** 由 MemeEmojiClient 在扫描图片后设置。 */
    public static void setComputedMaxSize(int size) {
        computedMaxSize = Math.max(8, size);
    }

    /** 扫描后记录最大表情高度，所有文字链路共用。 */
    public static void setMaxEmojiHeight(int height) {
        maxEmojiHeight = Math.max(9, height);
    }

    /** 表情的最大尺寸限制（超过此值的图片会按比例缩小）。 */
    public static int maxSize() {
        return computedMaxSize;
    }

    /** 实际的最大表情高度（可能小于 maxSize，保持原图比例）。 */
    public static int maxEmojiHeight() {
        return maxEmojiHeight;
    }

    /** 带 3px 底部余白的聊天气泡行高 = 最大表情高度 + 3。 */
    public static int lineHeight() {
        return maxEmojiHeight + 3;
    }

    /** 表情选择界面每格大小 = maxSize + 20px 余白。 */
    public static int pickerCell() {
        return computedMaxSize + 20;
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
