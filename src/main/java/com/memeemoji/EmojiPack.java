package com.memeemoji;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 把每个表情图写成独立纹理文件，font.json 里每个纹理一个 bitmap provider。
 * 再传给 PackRepositoryMixin 挂进客户端资源包列表。
 */
public final class EmojiPack {
    private EmojiPack() {
    }

    public static void write(Path packDir, List<EmojiTile> tiles) throws IOException {
        TileStore.deleteTree(packDir);
        Files.createDirectories(packDir);
        Files.writeString(packDir.resolve("pack.mcmeta"), meta(), StandardCharsets.UTF_8);

        if (tiles.isEmpty()) {
            return;
        }

        Path textureDir = packDir.resolve("assets")
                .resolve(MemeEmoji.MOD_ID)
                .resolve("textures")
                .resolve("font")
                .resolve("emoji");
        Files.createDirectories(textureDir);

        // 写入每个表情的独立纹理
        for (int i = 0; i < tiles.size(); i++) {
            Files.write(textureDir.resolve(i + ".png"), tiles.get(i).png());
        }

        // 生成 font.json，每个表情一个独立的 bitmap provider
        String fontJson = buildFontJson(tiles);
        Path fontDir = packDir.resolve("assets")
                .resolve(MemeEmoji.MOD_ID)
                .resolve("font");
        Files.createDirectories(fontDir);
        Files.writeString(fontDir.resolve("emoji.json"), fontJson, StandardCharsets.UTF_8);
    }

    private static String meta() {
        return """
                {
                  "pack": {
                    "pack_format": 34,
                    "description": "MemeEmoji 自动生成的表情纹理"
                  }
                }
                """;
    }

    private static String buildFontJson(List<EmojiTile> tiles) {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"providers\": [\n");
        for (int i = 0; i < tiles.size(); i++) {
            EmojiTile tile = tiles.get(i);
            // ascent = height - 5：让表情底部在 baseline 以下 5px，与 background box 底部保持 3px 间距
            int ascent = Math.max(1, tile.height() - 5);
            int codepoint = MemeEmoji.PUA_BASE + i;

            if (i > 0) {
                json.append(",\n");
            }
            json.append("    {\n");
            json.append("      \"type\": \"bitmap\",\n");
            json.append("      \"file\": \"").append(MemeEmoji.MOD_ID).append(":font/emoji/").append(i).append(".png\",\n");
            json.append("      \"height\": ").append(tile.height()).append(",\n");
            json.append("      \"ascent\": ").append(ascent).append(",\n");
            json.append("      \"chars\": [\n");
            json.append("        \"").append(escapeCodepoint(codepoint)).append("\"\n");
            json.append("      ]\n");
            json.append("    }");
        }
        json.append("\n  ]\n}\n");
        return json.toString();
    }

    /** 把码位写成 \\uXXXX 形式，避免不同平台/解析器对私用区字符的编码差异。 */
    private static String escapeCodepoint(int codepoint) {
        StringBuilder sb = new StringBuilder();
        sb.append("\\u");
        String hex = Integer.toHexString(codepoint).toUpperCase(java.util.Locale.ROOT);
        for (int i = hex.length(); i < 4; i++) {
            sb.append('0');
        }
        sb.append(hex);
        return sb.toString();
    }
}
