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

    /** 图集列数，1.21.1 的 BitmapProvider 按 chars 每行的码位数切列。 */
    public static final int COLUMNS = 16;

    /** 码位从 BMP 私用区低位开始分配，保证一个码位就是一个 char。 */
    public static final int PUA_BASE = 0xE000;
    public static final int PUA_LAST = 0xF8FF;
    public static final int MAX_EMOJI = PUA_LAST - PUA_BASE + 1;

    public static final String GENERATED_PACK_ID = MOD_ID + "/generated";

    /** 启动时由 MemeEmojiClient 根据预设算出。 */
    private static volatile int computedCell = 32;

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

    /** 由 MemeEmojiClient 在扫描图片后设置，所有文字链路共用同一个格大小。 */
    public static void setComputedCell(int cell) {
        computedCell = Math.max(8, cell);
    }

    /** 图集精灵的边长。 */
    public static int cell() {
        return computedCell;
    }

    /** 表情在文字里的逻辑高度，等于 cell。 */
    public static int glyphHeight() {
        return computedCell;
    }

    /**
     * 表情在文字里的基线偏移 = cell，使 emoji 从 baseline - cell 到 baseline，
     * 正好填满聊天气泡背景框。
     */
    public static int glyphAscent() {
        return Math.max(1, computedCell);
    }

    /** 表情选择界面每格大小 = cell + 20px 余白。 */
    public static int pickerCell() {
        return computedCell + 20;
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
