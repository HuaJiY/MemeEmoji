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
 * 显示尺寸与文件像素分辨率独立，切换尺寸不会重新压缩图片。
 */
public final class MemeEmojiConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final MemeEmojiConfig DEFAULT = new MemeEmojiConfig();

    public boolean enabled = true;
    public int maxNameLength = 32;
    /** 大小预设：small / medium / large */
    public String sizePreset = "small";
    /** 小号最大尺寸 */
    public int smallCellSize = 24;
    /** 中号最大尺寸 */
    public int mediumCellSize = 48;
    /** 大号最大尺寸 */
    public int largeCellSize = 96;
    /** 尺寸上限，兜底防止意外超大值 */
    public int maxCellSize = 128;
    public int stickerSize = 96;
    public String uiTheme = "dark";
    public int blurRadius = 7;
    public int panelOpacity = 190;
    public String accentColor = "#7AD8BE";
    public String darkSurfaceColor = "#192222";
    public String lightSurfaceColor = "#F1F5F3";

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
                config.save(file);
                return config;
            }
        } catch (IOException | JsonSyntaxException e) {
            MemeEmoji.LOGGER.warn("读取 {} 失败，改用默认配置", file, e);
        }
        return new MemeEmojiConfig();
    }

    public MemeEmojiConfig copy() { return GSON.fromJson(GSON.toJson(this), MemeEmojiConfig.class); }

    public void normalize() {
        if (maxNameLength < 1) {
            maxNameLength = DEFAULT.maxNameLength;
        }
        maxNameLength = Math.min(128, maxNameLength);
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
        if (stickerSize < 24 || stickerSize > 256) {
            stickerSize = DEFAULT.stickerSize;
        }
        if (!"light".equals(uiTheme)) uiTheme = "dark";
        blurRadius = Math.max(0, Math.min(10, blurRadius));
        panelOpacity = Math.max(64, Math.min(255, panelOpacity));
        accentColor = color(accentColor, DEFAULT.accentColor);
        darkSurfaceColor = color(darkSurfaceColor, DEFAULT.darkSurfaceColor);
        lightSurfaceColor = color(lightSurfaceColor, DEFAULT.lightSurfaceColor);
    }

    private static String color(String value, String fallback) {
        return value != null && value.matches("#[0-9a-fA-F]{6}") ? value.toUpperCase(java.util.Locale.ROOT) : fallback;
    }

    /** 根据预设返回最大尺寸限制，再 clamp 到 maxCellSize。 */
    public int targetMaxSize() {
        int size = switch (sizePreset) {
            case "small" -> smallCellSize;
            case "large" -> largeCellSize;
            default -> mediumCellSize;
        };
        return Math.max(8, Math.min(size, maxCellSize));
    }

    public void save(Path file) {
        try {
            saveOrThrow(file);
        } catch (IOException e) {
            MemeEmoji.LOGGER.warn("写入 {} 失败", file, e);
        }
    }

    public void saveOrThrow(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        String json = GSON.toJson(this) + System.lineSeparator();
        if (!Files.isRegularFile(file) || !Files.readString(file, StandardCharsets.UTF_8).equals(json))
            Files.writeString(file, json, StandardCharsets.UTF_8);
    }
}
