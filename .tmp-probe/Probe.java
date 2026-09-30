import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.serialization.JsonOps;
import com.memeemoji.EmojiPack;
import com.memeemoji.EmojiRegistry;
import com.memeemoji.EmojiTile;
import com.memeemoji.MemeEmoji;
import com.memeemoji.TileStore;
import com.memeemoji.WebpSupport;
import com.memeemoji.registry.ShapingSink;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.client.gui.font.providers.GlyphProviderDefinition;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.FormattedCharSink;
import net.minecraft.util.StringDecomposer;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** 无窗口端到端探针：真实图片 -> 裁切 -> 图集 -> 字体加载 -> :名字: 替换。 */
public final class Probe {
    private static int failures;
    private static Path work;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Path sampleRoot = Path.of(args[0]);
        work = Path.of(args[1]);
        if (Files.exists(work)) {
            deleteTree(work);
        }
        Path emojiDir = work.resolve("emoji");
        Files.createDirectories(emojiDir);

        WebpSupport.ensureRegistered();
        check("webp 解码库可用", WebpSupport.isAvailable());

        Path sampleWebp = firstWithExtension(sampleRoot, "webp");
        Path sampleGif = firstWithExtension(sampleRoot, "gif");
        Path sampleJpg = firstWithExtension(sampleRoot, "jpg");
        System.out.println("样例 webp=" + sampleWebp + " gif=" + sampleGif + " jpg=" + sampleJpg);
        check("找到 webp 样例", sampleWebp != null);
        check("找到 gif 样例", sampleGif != null);

        writeSolid(emojiDir.resolve("猫猫.png"), "png", 64, 64);
        writeSolid(emojiDir.resolve("猫.png"), "png", 9, 9);
        writeSolid(emojiDir.resolve("DOG.jpg"), "jpg", 200, 40);
        writeSolid(emojiDir.resolve("AAA.png"), "png", 18, 18);
        Files.createDirectories(emojiDir.resolve("子目录"));
        writeSolid(emojiDir.resolve("子目录").resolve("鲸鱼娘.png"), "png", 32, 48);
        writeSolid(emojiDir.resolve("带 空格.png"), "png", 18, 18);
        if (sampleWebp != null) {
            Files.copy(sampleWebp, emojiDir.resolve("webp表情.webp"));
        }
        if (sampleGif != null) {
            Files.copy(sampleGif, emojiDir.resolve("动图.gif"));
        }
        if (sampleJpg != null) {
            Files.copy(sampleJpg, emojiDir.resolve("大图.jpg"));
        }
        writeSolid(emojiDir.resolve("超".repeat(40) + ".png"), "png", 18, 18);

        Path cacheDir = work.resolve(".cache");
        long first = System.nanoTime();
        List<EmojiTile> tiles = TileStore.load(emojiDir, cacheDir, MemeEmoji.CELL, 32);
        long firstMs = (System.nanoTime() - first) / 1_000_000;
        System.out.println("首次解码耗时 " + firstMs + "ms，得到 " + tiles.size() + " 张");
        for (int i = 0; i < tiles.size(); i++) {
            System.out.println("  [" + i + "] " + tiles.get(i).name() + " png=" + tiles.get(i).png().length + "B");
        }

        List<String> names = tiles.stream().map(EmojiTile::name).toList();
        check("中文名可用", names.contains("猫猫"));
        check("中文子目录名可用", names.contains("鲸鱼娘"));
        check("webp 可解码", names.contains("webp表情"));
        check("gif 可解码（取首帧）", names.contains("动图"));
        check("大小写不同的重名只保留一个", names.stream().filter(n -> n.equalsIgnoreCase("dog")).count() == 1);
        check("带空格的名字被跳过", !names.contains("带 空格"));
        check("超长名字被跳过", names.stream().noneMatch(n -> n.length() > 32));
        check("顺序稳定可复现（两端按同一顺序分配码位）",
                names.equals(List.of("AAA", "DOG", "webp表情", "动图", "大图", "鲸鱼娘", "猫", "猫猫")));

        List<EmojiTile> second = TileStore.load(emojiDir, cacheDir, MemeEmoji.CELL, 32);
        check("第二次载入走缓存且结果一致",
                second.size() == tiles.size() && second.get(0).name().equals(tiles.get(0).name()));
        System.out.println("缓存索引存在 = " + Files.isRegularFile(cacheDir.resolve("index.json")));

        Path packDir = work.resolve("generated");
        EmojiPack.write(packDir, tiles);
        check("pack.mcmeta 已生成", Files.isRegularFile(packDir.resolve("pack.mcmeta")));
        Path fontJsonPath = packDir.resolve("assets/memeemoji/font/emoji.json");
        Path texturePath = packDir.resolve("assets/memeemoji/textures/font/emoji.png");
        check("font json 已生成", Files.isRegularFile(fontJsonPath));
        check("图集贴图已生成", Files.isRegularFile(texturePath));
        BufferedImage sheet = ImageIO.read(texturePath.toFile());
        int expectedRows = (tiles.size() + MemeEmoji.COLUMNS - 1) / MemeEmoji.COLUMNS;
        System.out.println("图集 = " + sheet.getWidth() + "x" + sheet.getHeight()
                + "（期望 " + (MemeEmoji.COLUMNS * MemeEmoji.CELL) + "x" + (expectedRows * MemeEmoji.CELL) + "）");
        check("图集尺寸正确", sheet.getWidth() == MemeEmoji.COLUMNS * MemeEmoji.CELL
                && sheet.getHeight() == expectedRows * MemeEmoji.CELL);
        check("索引 0 与索引 1 的图案不同（没画错位置）",
                sheet.getRGB(MemeEmoji.CELL / 2, MemeEmoji.CELL / 2)
                        != sheet.getRGB(MemeEmoji.CELL + MemeEmoji.CELL / 2, MemeEmoji.CELL / 2));

        // 真的按资源包的方式打开，交给 MC 自己的字体加载器解析
        PackLocationInfo info = new PackLocationInfo("memeemoji_probe", Component.literal("probe"),
                PackSource.DEFAULT, Optional.empty());
        Pack pack = Pack.readMetaAndCreate(info, new PathPackResources.PathResourcesSupplier(packDir),
                PackType.CLIENT_RESOURCES, new PackSelectionConfig(true, Pack.Position.BOTTOM, false));
        check("MC 能读取生成的资源包", pack != null);
        if (pack == null) {
            return;
        }
        try (PackResources resources = pack.open()) {
            ResourceManager manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(resources));
            Optional<Resource> fontResource = manager.getResource(
                    ResourceLocation.fromNamespaceAndPath("memeemoji", "font/emoji.json"));
            check("资源管理器能找到字体定义", fontResource.isPresent());
            JsonObject root = JsonParser.parseString(readString(fontResource.get())).getAsJsonObject();
            JsonArray providers = root.getAsJsonArray("providers");
            check("字体定义至少含一个 provider", providers != null && !providers.isEmpty());
            JsonElement providerJson = providers.get(0);
            GlyphProviderDefinition definition = GlyphProviderDefinition.MAP_CODEC
                    .codec().parse(JsonOps.INSTANCE, providerJson).getOrThrow();
            GlyphProvider provider = definition.unpack().left()
                    .orElseThrow(() -> new IllegalStateException("provider 不是内联定义")).load(manager);
            check("贴图能按字体定义加载", provider != null);
            IntSet supported = provider.getSupportedGlyphs();
            System.out.println("可用码位数 = " + supported.size());
            check("码位数与表情数一致", supported.size() == tiles.size());
            check("首格码位是 U+E000", supported.contains(MemeEmoji.PUA_BASE));
            GlyphInfo glyph = provider.getGlyph(MemeEmoji.PUA_BASE);
            check("U+E000 有字形", glyph != null);
            if (glyph != null) {
                java.lang.reflect.Method width = glyph.getClass().getDeclaredMethod("width");
                java.lang.reflect.Method height = glyph.getClass().getDeclaredMethod("height");
                width.setAccessible(true);
                height.setAccessible(true);
                int glyphWidth = (int) width.invoke(glyph);
                int glyphHeight = (int) height.invoke(glyph);
                System.out.println("U+E000 字形 advance=" + glyph.getAdvance()
                        + " 像素=" + glyphWidth + "x" + glyphHeight);
                check("字形非空", glyphWidth > 0 && glyphHeight > 0 && glyph.getAdvance() > 0);
            }
        }

        // 文本替换
        EmojiRegistry.INSTANCE.apply(tiles, true);
        check("表情数量统计正确", EmojiRegistry.INSTANCE.size() == tiles.size());
        String text = "你好 :猫猫: 和 :dog: 与 :webp表情: 以及 :unknown: 结束";
        List<Integer> codepoints = new ArrayList<>();
        List<ResourceLocation> fonts = new ArrayList<>();
        FormattedCharSink collector = (position, style, codepoint) -> {
            codepoints.add(codepoint);
            fonts.add(style.getFont());
            return true;
        };
        ShapingSink sink = ShapingSink.acquire(collector, EmojiRegistry.INSTANCE.shapingTable());
        boolean ok = StringDecomposer.iterate(text, Style.EMPTY, sink);
        ok = ok && sink.finish();
        sink.release();
        check("整形过程返回成功", ok);
        StringBuilder actual = new StringBuilder();
        codepoints.forEach(c -> actual.appendCodePoint(c));
        String expected = "你好 " + (char) (MemeEmoji.PUA_BASE + names.indexOf("猫猫"))
                + " 和 " + (char) (MemeEmoji.PUA_BASE + names.indexOf("DOG"))
                + " 与 " + (char) (MemeEmoji.PUA_BASE + names.indexOf("webp表情"))
                + " 以及 :unknown: 结束";
        System.out.println("整形结果 = " + escape(actual.toString()));
        check("文字内容与原文一致，只有表情名被换成对应码位", actual.toString().equals(expected));
        check("只有 3 个表情位置切到了表情字体", fonts.stream().filter(MemeEmoji.EMOJI_FONT::equals).count() == 3);
        check("普通文字仍是默认字体",
                fonts.stream().filter(f -> f == null || f.toString().equals("minecraft:default")).count() == fonts.size() - 3);

        String longest = ":猫猫:x";
        List<String> longestOut = new ArrayList<>();
        ShapingSink sink2 = ShapingSink.acquire((p, s, c) -> {
            longestOut.add(new String(Character.toChars(c)));
            return true;
        }, EmojiRegistry.INSTANCE.shapingTable());
        StringDecomposer.iterate(longest, Style.EMPTY, sink2);
        sink2.finish();
        sink2.release();
        check("最长匹配优先（:猫猫: 不被 :猫: 抢走）",
                String.join("", longestOut).equals(String.valueOf((char) (MemeEmoji.PUA_BASE + names.indexOf("猫猫"))) + "x"));

        String split = ":猫";
        List<String> splitOut = new ArrayList<>();
        ShapingSink sink3 = ShapingSink.acquire((p, s, c) -> {
            splitOut.add(new String(Character.toChars(c)));
            return true;
        }, EmojiRegistry.INSTANCE.shapingTable());
        StringDecomposer.iterate(split, Style.EMPTY, sink3);
        sink3.finish();
        sink3.release();
        check("半截表情不会吞掉文字", String.join("", splitOut).equals(":猫"));

        System.out.println(failures == 0 ? "\n全部检查通过" : "\n有 " + failures + " 项检查失败");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static String readString(Resource resource) throws Exception {
        try (var in = resource.open()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** 私用区字符在控制台看不见，转成 U+XXXX 方便比对日志。 */
    private static String escape(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int codepoint = text.codePointAt(i);
            i += Character.charCount(codepoint);
            if (Character.isISOControl(codepoint) || codepoint >= 0xE000 && codepoint <= 0xF8FF) {
                out.append(String.format("U+%04X", codepoint));
            } else {
                out.appendCodePoint(codepoint);
            }
        }
        return out.toString();
    }

    private static void check(String label, boolean condition) {
        System.out.println((condition ? "[通过] " : "[失败] ") + label);
        if (!condition) {
            failures++;
        }
    }

    private static void writeSolid(Path file, String format, int width, int height) throws Exception {
        int type = "jpg".equals(format) || "jpeg".equals(format)
                ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB;
        BufferedImage image = new BufferedImage(width, height, type);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(Math.abs(file.getFileName().toString().hashCode()) % 0xFFFFFF | 0xFF000000, true));
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, Math.max(1, width / 3), Math.max(1, height / 3));
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, format, file.toFile());
    }

    private static Path firstWithExtension(Path root, String extension) throws Exception {
        List<Path> found = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith("." + extension)) {
                    found.add(file);
                }
                return found.size() >= 1 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
        });
        return found.isEmpty() ? null : found.get(0);
    }

    private static void deleteTree(Path root) throws Exception {
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
