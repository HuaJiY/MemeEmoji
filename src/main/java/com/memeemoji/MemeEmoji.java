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

    /**
     * 字形盒边长（像素）。BitmapProvider 要求整张图集的每个精灵同尺寸，所以服务端下发的单图也按这个尺寸裁切，
     * 客户端与服务端必须用同一个值。
     */
    public static final int CELL = 18;
    /** 图集列数，1.21.1 的 BitmapProvider 按 chars 每行的码位数切列。 */
    public static final int COLUMNS = 16;
    /** 表情在文字里的逻辑高度，与原版 9px 行高一致。 */
    public static final int GLYPH_HEIGHT = 9;
    public static final int GLYPH_ASCENT = 8;

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
