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
 */
public final class MemeEmojiConfig {
    public static final MemeEmojiConfig DEFAULT = new MemeEmojiConfig(true, 32, true);

    public boolean enabled = DEFAULT.enabled;
    public int maxNameLength = DEFAULT.maxNameLength;
    public boolean sendToClients = DEFAULT.sendToClients;

    public MemeEmojiConfig() {
    }

    private MemeEmojiConfig(boolean enabled, int maxNameLength, boolean sendToClients) {
        this.enabled = enabled;
        this.maxNameLength = maxNameLength;
        this.sendToClients = sendToClients;
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
                return config;
            }
        } catch (IOException | JsonSyntaxException e) {
            MemeEmoji.LOGGER.warn("读取 {} 失败，改用默认配置", file, e);
        }
        return new MemeEmojiConfig();
    }

    public void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(this) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            MemeEmoji.LOGGER.warn("写入 {} 失败", file, e);
        }
    }

    private void normalize() {
        if (maxNameLength < 1) {
            maxNameLength = DEFAULT.maxNameLength;
        }
    }
}
