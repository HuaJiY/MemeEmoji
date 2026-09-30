package com.memeemoji;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 把表情图集写成一个真实资源包目录，再由 PackRepositoryMixin 挂进客户端资源包列表。
 */
public final class EmojiPack {
    private EmojiPack() {
    }

    public static void write(Path packDir, List<EmojiTile> tiles) throws IOException {
        TileStore.deleteTree(packDir);
        Files.createDirectories(packDir);
        Files.writeString(packDir.resolve("pack.mcmeta"), meta(), StandardCharsets.UTF_8);

        Path fontDir = packDir.resolve("assets").resolve(MemeEmoji.MOD_ID).resolve("font");
        Files.createDirectories(fontDir);
        Files.writeString(fontDir.resolve("emoji.json"), fontJson(tiles), StandardCharsets.UTF_8);

        if (tiles.isEmpty()) {
            return;
        }
        Path textureDir = packDir.resolve("assets").resolve(MemeEmoji.MOD_ID).resolve("textures").resolve("font");
        Files.createDirectories(textureDir);
        ImageIO.write(sheet(tiles), "png", textureDir.resolve("emoji.png").toFile());
    }

    private static BufferedImage sheet(List<EmojiTile> tiles) throws IOException {
        int columns = MemeEmoji.COLUMNS;
        int cell = MemeEmoji.CELL;
        int rows = Math.max(1, (tiles.size() + columns - 1) / columns);
        BufferedImage image = new BufferedImage(columns * cell, rows * cell, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            for (int i = 0; i < tiles.size(); i++) {
                BufferedImage tile = ImageTiles.read(tiles.get(i).png());
                graphics.drawImage(tile, (i % columns) * cell, (i / columns) * cell, null);
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static String meta() {
        return """
                {
                  "pack": {
                    "pack_format": 34,
                    "description": "MemeEmoji 自动生成的表情图集"
                  }
                }
                """;
    }

    private static String fontJson(List<EmojiTile> tiles) {
        StringBuilder json = new StringBuilder();
        if (tiles.isEmpty()) {
            return "{\n  \"providers\": []\n}\n";
        }
        int columns = MemeEmoji.COLUMNS;
        int rows = Math.max(1, (tiles.size() + columns - 1) / columns);
        json.append("{\n  \"providers\": [\n    {\n");
        json.append("      \"type\": \"bitmap\",\n");
        json.append("      \"file\": \"").append(MemeEmoji.MOD_ID).append(":font/emoji.png\",\n");
        json.append("      \"height\": ").append(MemeEmoji.GLYPH_HEIGHT).append(",\n");
        json.append("      \"ascent\": ").append(MemeEmoji.GLYPH_ASCENT).append(",\n");
        json.append("      \"chars\": [\n");
        for (int row = 0; row < rows; row++) {
            json.append("        \"");
            for (int column = 0; column < columns; column++) {
                int index = row * columns + column;
                appendEscaped(json, index < tiles.size() ? MemeEmoji.PUA_BASE + index : 0);
            }
            json.append('"');
            json.append(row + 1 < rows ? ',' : '\n');
        }
        json.append("      ]\n    }\n  ]\n}\n");
        return json.toString();
    }

    /** 一律写成 \\uXXXX，避免不同平台 / 解析器对私用区字符的编码差异。 */
    private static void appendEscaped(StringBuilder json, int codePoint) {
        json.append("\\u");
        String hex = Integer.toHexString(codePoint).toUpperCase(java.util.Locale.ROOT);
        for (int i = hex.length(); i < 4; i++) {
            json.append('0');
        }
        json.append(hex);
    }
}
