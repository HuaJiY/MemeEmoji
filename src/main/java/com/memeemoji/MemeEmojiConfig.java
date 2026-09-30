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
 * <p>表情格大小默认由图片原始尺寸决定（取所有图片的最大宽高 × scale 后再 clamp 到 maxCellSize），
 * 不再需要手动选 small/medium/large。
 */
public final class MemeEmojiConfig {
    public static final MemeEmojiConfig DEFAULT = new MemeEmojiConfig();

    public boolean enabled = true;
    public int maxNameLength = 32;
    public boolean sendToClients = true;
    /** 缩放系数，取所有图片最大边 × scale = 实际格大小。1.0 = 原尺寸，0.5 = 一半。 */
    public float scale = 1.0f;
    /** 格大小上限，防止大图直接撑爆聊天界面。默认 128。 */
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
        if (scale <= 0 || Float.isNaN(scale) || Float.isInfinite(scale)) {
            scale = DEFAULT.scale;
        } else {
            scale = Math.clamp(scale, 0.1f, 5.0f);
        }
        if (maxCellSize < 8 || maxCellSize > 512) {
            maxCellSize = DEFAULT.maxCellSize;
        }
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
