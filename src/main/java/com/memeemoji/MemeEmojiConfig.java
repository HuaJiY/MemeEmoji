package com.memeemoji;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 可调项都放在这里。表情图片本身不需要写进配置，丢进 emoji 文件夹就算一张表情，
 * 文件名（去掉扩展名）就是 :名字: 里的名字。
 *
 * <p>表情格大小由预设档位（small/medium/large）决定，每档对应的格大小可自定义。
 */
public final class MemeEmojiConfig {
    public static final MemeEmojiConfig DEFAULT = new MemeEmojiConfig();

    public boolean enabled = true;
    public int maxNameLength = 32;
    public boolean sendToClients = true;
    /** 大小预设：small / medium / large */
    public String sizePreset = "medium";
    /** 小号格大小 */
    public int smallCellSize = 24;
    /** 中号格大小 */
    public int mediumCellSize = 48;
    /** 大号格大小 */
    public int largeCellSize = 96;
    /** 格大小上限，兜底防止意外超大值 */
    public int maxCellSize = 128;

    public MemeEmojiConfig() {
    }

    public static MemeEmojiConfig load(Path file) {
        if (!Files.isRegularFile(file)) {
            MemeEmojiConfig config = new MemeEmojiConfig();
            config.save(file);
            return config;
        }
        try {
            MemeEmojiConfig config = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), MemeEmojiConfig.class);
            if (config != null) {
                config.normalize();
                config.save(file); // 回写确保配置文件里有所有新字段
                return config;
            }
        } catch (IOException | JsonSyntaxException e) {
            MemeEmoji.LOGGER.warn("读取 {} 失败，改用默认配置", file, e);
        }
        return new MemeEmojiConfig();
    }

    private void normalize() {
        if (maxNameLength < 1) {
            maxNameLength = DEFAULT.maxNameLength;
        }
        if (sizePreset == null || (!sizePreset.equals("small") && !sizePreset.equals("medium") && !sizePreset.equals("large"))) {
            sizePreset = DEFAULT.sizePreset;
        }
        if (smallCellSize < 8 || smallCellSize > 512) {
            smallCellSize = DEFAULT.smallCellSize;
        }
        if (mediumCellSize < 8 || mediumCellSize > 512) {
            mediumCellSize = DEFAULT.mediumCellSize;
        }
        if (largeCellSize < 8 || largeCellSize > 512) {
            largeCellSize = DEFAULT.largeCellSize;
        }
        if (maxCellSize < 8 || maxCellSize > 512) {
            maxCellSize = DEFAULT.maxCellSize;
        }
    }

    /** 根据预设返回目标格大小，再 clamp 到 maxCellSize。 */
    public int targetCellSize() {
        int cell = switch (sizePreset) {
            case "small" -> smallCellSize;
            case "large" -> largeCellSize;
            default -> mediumCellSize;
        };
        return Math.max(8, Math.min(cell, maxCellSize));
    }

    public void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(this) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            MemeEmoji.LOGGER.warn("写入 {} 失败", file, e);
        }
    }
}
