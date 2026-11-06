package com.memeemoji.media;

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class EmojiDecoder {
    public static final long MAX_TOTAL_PIXELS = 16L * 1024 * 1024;
    private static final int MAX_FRAMES = 1000;

    private EmojiDecoder() {}

    public record Frame(int[] argb, int durationMillis) {}

    public record Decoded(int width, int height, List<Frame> frames, int loops) {
        public long pixels() { return (long) width * height * frames.size(); }

        public int frameAt(long elapsedMillis) {
            long duration = frames.stream().mapToLong(Frame::durationMillis).sum();
            if (loops > 0 && elapsedMillis / duration >= loops) return frames.size() - 1;
            long position = Math.max(0, elapsedMillis) % duration;
            for (int i = 0; i < frames.size(); i++) {
                position -= frames.get(i).durationMillis();
                if (position < 0) return i;
            }
            return frames.size() - 1;
        }
    }

    public record Info(int width, int height, boolean animated) {}

    public static String extension(byte[] bytes) throws IOException {
        if (isWebp(bytes)) return "webp";
        try (MemoryCacheImageInputStream in = input(bytes)) {
            ImageReader reader = reader(bytes, in);
            try {
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                return format.equals("jpeg") ? "jpg" : format;
            } finally { reader.dispose(); }
        }
    }

    public static Info inspect(byte[] bytes) throws IOException {
        checkBytes(bytes);
        if (isWebp(bytes)) {
            Webp webp = parseWebp(bytes);
            if (webp.width > 0) {
                checkBudget(webp.width, webp.height, Math.max(1, webp.frames.size()));
                return new Info(webp.width, webp.height, webp.frames.size() > 1);
            }
        }
        try (MemoryCacheImageInputStream in = input(bytes)) {
            ImageReader reader = reader(bytes, in);
            try {
                reader.setInput(in, false, false);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                boolean gif = reader.getFormatName().equalsIgnoreCase("gif");
                int count = gif ? reader.getNumImages(true) : 1;
                if (gif) {
                    Node descriptor = child(tree(reader.getStreamMetadata(), "javax_imageio_gif_stream_1.0"), "LogicalScreenDescriptor");
                    width = attrInt(descriptor, "logicalScreenWidth", width);
                    height = attrInt(descriptor, "logicalScreenHeight", height);
                }
                checkBudget(width, height, count);
                return new Info(width, height, count > 1);
            } finally { reader.dispose(); }
        }
    }

    public static Decoded decode(byte[] bytes) throws IOException {
        Info info = inspect(bytes);
        if (isWebp(bytes)) {
            Webp webp = parseWebp(bytes);
            if (!webp.frames.isEmpty()) return decodeWebp(bytes, webp);
        }
        try (MemoryCacheImageInputStream in = input(bytes)) {
            ImageReader reader = reader(bytes, in);
            try {
                reader.setInput(in, false, false);
                if (reader.getFormatName().equalsIgnoreCase("gif")) return decodeGif(reader, info);
                BufferedImage image = reader.read(0);
                if (isWebp(bytes)) applyRawAlpha(bytes, image);
                return new Decoded(info.width, info.height, List.of(frame(image, 100)), 1);
            } finally { reader.dispose(); }
        } catch (RuntimeException e) {
            throw new IOException("图片解码失败", e);
        }
    }

    private static Decoded decodeGif(ImageReader reader, Info info) throws IOException {
        int count = reader.getNumImages(true);
        BufferedImage canvas = canvas(info.width, info.height);
        List<Frame> frames = new ArrayList<>(count);
        int loops = 1;
        Node stream = tree(reader.getStreamMetadata(), "javax_imageio_gif_stream_1.0");
        Node palette = child(stream, "GlobalColorTable");
        int backgroundIndex = attrInt(palette, "backgroundColorIndex", -1);
        int background = 0;
        if (palette != null) {
            for (Node color = palette.getFirstChild(); color != null; color = color.getNextSibling()) {
                if (attrInt(color, "index", -2) == backgroundIndex) {
                    background = 0xff000000 | attrInt(color, "red", 0) << 16
                            | attrInt(color, "green", 0) << 8 | attrInt(color, "blue", 0);
                }
            }
        }
        for (int i = 0; i < count; i++) {
            Node metadata = tree(reader.getImageMetadata(i), "javax_imageio_gif_image_1.0");
            Node descriptor = child(metadata, "ImageDescriptor");
            Node control = child(metadata, "GraphicControlExtension");
            int x = attrInt(descriptor, "imageLeftPosition", 0), y = attrInt(descriptor, "imageTopPosition", 0);
            int w = attrInt(descriptor, "imageWidth", info.width), h = attrInt(descriptor, "imageHeight", info.height);
            checkRect(x, y, w, h, info.width, info.height);
            String disposal = attr(control, "disposalMethod", "none");
            boolean transparent = attr(control, "transparentColorFlag", "FALSE").equalsIgnoreCase("TRUE");
            int clear = transparent ? 0 : background;
            if (i == 0) {
                fill(canvas, 0, 0, info.width, info.height, clear);
                Node extensions = child(metadata, "ApplicationExtensions");
                if (extensions != null) {
                    for (Node extension = extensions.getFirstChild(); extension != null; extension = extension.getNextSibling()) {
                        if (extension instanceof javax.imageio.metadata.IIOMetadataNode n
                                && attr(extension, "applicationID", "").equals("NETSCAPE")
                                && n.getUserObject() instanceof byte[] data && data.length >= 3 && data[0] == 1) {
                            int repeats = (data[1] & 255) | (data[2] & 255) << 8;
                            loops = repeats == 0 ? 0 : repeats + 1;
                        }
                    }
                }
            }
            BufferedImage previous = disposal.equals("restoreToPrevious") ? copy(canvas) : null;
            draw(canvas, reader.read(i), x, y, false);
            int delay = attrInt(control, "delayTime", 10) * 10;
            frames.add(frame(canvas, delay <= 10 ? 100 : delay));
            if (disposal.equals("restoreToBackgroundColor")) fill(canvas, x, y, w, h, clear);
            else if (previous != null) canvas = previous;
        }
        return new Decoded(info.width, info.height, List.copyOf(frames), loops);
    }

    private static Decoded decodeWebp(byte[] bytes, Webp webp) throws IOException {
        BufferedImage canvas = canvas(webp.width, webp.height);
        fill(canvas, 0, 0, webp.width, webp.height, webp.background);
        List<Frame> frames = new ArrayList<>(webp.frames.size());
        for (WebpFrame f : webp.frames) {
            byte[] imageBytes = standaloneWebp(bytes, f);
            BufferedImage image;
            try (MemoryCacheImageInputStream in = input(imageBytes)) {
                ImageReader reader = new WebPImageReaderSpi().createReaderInstance();
                try {
                    reader.setInput(in);
                    if (reader.getWidth(0) != f.width || reader.getHeight(0) != f.height) throw new IOException("WebP 帧尺寸不匹配");
                    image = reader.read(0);
                    applyRawAlpha(imageBytes, image);
                } finally { reader.dispose(); }
            }
            draw(canvas, image, f.x, f.y, (f.flags & 2) != 0);
            frames.add(frame(canvas, Math.max(10, f.duration)));
            if ((f.flags & 1) != 0) fill(canvas, f.x, f.y, f.width, f.height, webp.background);
        }
        return new Decoded(webp.width, webp.height, List.copyOf(frames), webp.loops);
    }

    private static byte[] standaloneWebp(byte[] bytes, WebpFrame frame) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write("WEBP".getBytes(StandardCharsets.US_ASCII));
        // ANMF contains raw ALPH/VP8/VP8L subchunks. A VP8X header preserves separate ALPH data.
        body.write("VP8X".getBytes(StandardCharsets.US_ASCII));
        little(body, 10, 4);
        body.write(16);
        little(body, 0, 3);
        little(body, frame.width - 1, 3);
        little(body, frame.height - 1, 3);
        body.write(bytes, frame.offset, frame.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("RIFF".getBytes(StandardCharsets.US_ASCII));
        little(out, body.size(), 4);
        body.writeTo(out);
        return out.toByteArray();
    }

    // TwelveMonkeys decodes compressed ALPH, but its raw-ALPH branch leaves all pixels opaque.
    private static void applyRawAlpha(byte[] bytes, BufferedImage image) throws IOException {
        for (int pos = 12; pos + 8 <= bytes.length;) {
            long length = unsigned(bytes, pos + 4, 4);
            long next = pos + 8L + length + (length & 1);
            if (next > bytes.length) throw new IOException("WebP 透明通道被截断");
            if (bytes[pos] == 'A' && bytes[pos + 1] == 'L' && bytes[pos + 2] == 'P' && bytes[pos + 3] == 'H') {
                int data = pos + 8;
                if (length < 1) throw new IOException("WebP 透明通道无效");
                int flags = bytes[data] & 255;
                if ((flags & 3) != 0) return;
                int width = image.getWidth(), height = image.getHeight();
                if (length != 1L + width * height) throw new IOException("WebP 透明通道尺寸无效");
                int filter = flags >>> 2 & 3;
                int[] alpha = new int[width * height];
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int index = y * width + x;
                        int left = x > 0 ? alpha[index - 1] : 0;
                        int above = y > 0 ? alpha[index - width] : 0;
                        int predictor = switch (filter) {
                            case 1 -> x == 0 ? above : left;
                            case 2 -> y == 0 ? left : above;
                            case 3 -> y == 0 ? left : x == 0 ? above
                                    : Math.max(0, Math.min(255, left + above - alpha[index - width - 1]));
                            default -> 0;
                        };
                        alpha[index] = ((bytes[data + 1 + index] & 255) + predictor) & 255;
                        image.setRGB(x, y, image.getRGB(x, y) & 0xffffff | alpha[index] << 24);
                    }
                }
                return;
            }
            pos = (int) next;
        }
    }

    private static Webp parseWebp(byte[] bytes) throws IOException {
        int width = 0, height = 0, background = 0, loops = 1;
        boolean animation = false;
        List<WebpFrame> frames = new ArrayList<>();
        long end = 8L + unsigned(bytes, 4, 4);
        if (end != bytes.length) throw new IOException("WebP 文件长度无效");
        for (int pos = 12; pos < bytes.length;) {
            if (bytes.length - pos < 8) throw new IOException("WebP 数据块被截断");
            String tag = new String(bytes, pos, 4, StandardCharsets.US_ASCII);
            long length = unsigned(bytes, pos + 4, 4);
            long next = pos + 8L + length + (length & 1);
            if (next > end) throw new IOException("WebP 数据块长度无效");
            int data = pos + 8;
            if (tag.equals("VP8X")) {
                if (length != 10) throw new IOException("WebP 头部无效");
                animation = (bytes[data] & 2) != 0;
                width = 1 + (int) unsigned(bytes, data + 4, 3);
                height = 1 + (int) unsigned(bytes, data + 7, 3);
                checkBudget(width, height, 1);
            } else if (tag.equals("ANIM")) {
                if (length != 6) throw new IOException("WebP 动画头部无效");
                background = (int) unsigned(bytes, data, 4);
                loops = (int) unsigned(bytes, data + 4, 2);
            } else if (tag.equals("ANMF")) {
                if (!animation || length < 16 || width == 0) throw new IOException("WebP 动画帧无效");
                int x = (int) unsigned(bytes, data, 3) * 2, y = (int) unsigned(bytes, data + 3, 3) * 2;
                int w = 1 + (int) unsigned(bytes, data + 6, 3), h = 1 + (int) unsigned(bytes, data + 9, 3);
                checkRect(x, y, w, h, width, height);
                frames.add(new WebpFrame(x, y, w, h, (int) unsigned(bytes, data + 12, 3), bytes[data + 15] & 255,
                        data + 16, (int) length - 16));
                checkBudget(width, height, frames.size());
            }
            pos = (int) next;
        }
        if (animation && frames.isEmpty()) throw new IOException("WebP 动画没有可播放的帧");
        return new Webp(width, height, background, loops, frames);
    }

    private static long unsigned(byte[] data, int offset, int length) throws IOException {
        if (offset < 0 || offset + length > data.length) throw new IOException("图片数据被截断");
        long value = 0;
        for (int i = 0; i < length; i++) value |= (long) (data[offset + i] & 255) << (i * 8);
        return value;
    }

    private static void little(ByteArrayOutputStream out, int value, int length) {
        for (int i = 0; i < length; i++) out.write(value >>> (i * 8) & 255);
    }

    private static boolean isWebp(byte[] bytes) {
        return bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    private static ImageReader reader(byte[] bytes, MemoryCacheImageInputStream in) throws IOException {
        if (isWebp(bytes)) return new WebPImageReaderSpi().createReaderInstance();
        var readers = ImageIO.getImageReaders(in);
        if (!readers.hasNext()) throw new IOException("不支持的图片格式");
        return readers.next();
    }

    private static void checkBytes(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > EmojiAsset.MAX_FILE_BYTES) throw new IOException("图片须在 8 MB 以内");
    }

    private static void checkBudget(int width, int height, int count) throws IOException {
        if (width < 1 || height < 1 || width > EmojiAsset.MAX_DIMENSION || height > EmojiAsset.MAX_DIMENSION)
            throw new IOException("图片尺寸不能超过 2048 × 2048");
        if (count < 1 || count > MAX_FRAMES || (long) width * height * count > MAX_TOTAL_PIXELS)
            throw new IOException("动图解码后过大，请减少分辨率或帧数");
    }

    private static void checkRect(int x, int y, int w, int h, int width, int height) throws IOException {
        if (x < 0 || y < 0 || w < 1 || h < 1 || (long) x + w > width || (long) y + h > height)
            throw new IOException("动画帧超出画布");
    }

    private static MemoryCacheImageInputStream input(byte[] bytes) { return new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes)); }
    private static BufferedImage canvas(int w, int h) { return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB); }
    private static Frame frame(BufferedImage image, int duration) { return new Frame(image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()), duration); }
    private static BufferedImage copy(BufferedImage source) { BufferedImage copy = canvas(source.getWidth(), source.getHeight()); draw(copy, source, 0, 0, true); return copy; }

    private static void draw(BufferedImage target, BufferedImage source, int x, int y, boolean replace) {
        Graphics2D g = target.createGraphics();
        try { g.setComposite(replace ? AlphaComposite.Src : AlphaComposite.SrcOver); g.drawImage(source, x, y, null); }
        finally { g.dispose(); }
    }

    private static void fill(BufferedImage image, int x, int y, int w, int h, int argb) {
        int[] row = new int[w];
        Arrays.fill(row, argb);
        for (int yy = y; yy < y + h; yy++) image.setRGB(x, yy, w, 1, row, 0, w);
    }

    private static Node tree(IIOMetadata metadata, String format) { return metadata == null ? null : metadata.getAsTree(format); }
    private static Node child(Node node, String name) {
        if (node != null) for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling()) if (n.getNodeName().equals(name)) return n;
        return null;
    }
    private static String attr(Node node, String name, String fallback) {
        Node value = node == null || node.getAttributes() == null ? null : node.getAttributes().getNamedItem(name);
        return value == null ? fallback : value.getNodeValue();
    }
    private static int attrInt(Node node, String name, int fallback) { return Integer.parseInt(attr(node, name, Integer.toString(fallback))); }
    private record Webp(int width, int height, int background, int loops, List<WebpFrame> frames) {}
    private record WebpFrame(int x, int y, int width, int height, int duration, int flags, int offset, int length) {}
}
