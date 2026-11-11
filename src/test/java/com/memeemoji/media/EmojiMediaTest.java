package com.memeemoji.media;

import com.memeemoji.EmojiNames;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class EmojiMediaTest {
    // Lossless fixtures encoded with Pillow/libwebp; the animation uses optimized partial frames.
    private static final byte[] RED_WEBP = Base64.getDecoder().decode("UklGRhwAAABXRUJQVlA4TA8AAAAvA8AAAAcQ/Y/+ByKi/wEA");
    private static final byte[] GREEN_WEBP = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4TBYAAAAvA8AAEA8w/xHzHwwCSVu87Y/of8AB");
    private static final byte[] LOSSY_WEBP = Base64.getDecoder().decode("UklGRkAAAABXRUJQVlA4IDQAAAAQAgCdASoEAAQAAIAIJaACdLoB+AH6AAPIAP79V7P/65a0E9s/9cu//7rPnjF3n//uoQAA");
    private static final byte[] ANIMATED_WEBP = Base64.getDecoder().decode("UklGRrQAAABXRUJQVlA4WAoAAAACAAAAAwAAAwAAQU5JTQYAAAAAAAAAAABBTk1GKAAAAAAAAAAAAAMAAAMAAFAAAANWUDhMDwAAAC8DwAAABxD9j/4HIqL/AQBBTk1GKAAAAAEAAAEAAAAAAAAAAHgAAABWUDhMDwAAAC8AAAAAB9D/iP4HIqL/AQBBTk1GKAAAAAAAAAAAAAMAAAMAAMgAAABWUDhMDwAAAC8DwAAABxDR//4HIqL/AQA=");

    public static void main(String[] args) throws Exception {
        pngPreservesPixels();
        transparentGif();
        indexedTimeline();
        staticAndAnimatedWebp();
        webpBlendAndDisposal();
        webpRawAlpha();
        rejectOversizedImages();
        collectionIndexCompatibility();
        defaultNames();
        recursiveWatcher();
        System.out.println("Media tests passed: image format retention, PNG pixels, transparent GIF disposal/timing, static/animated WebP, WebP blend/disposal/raw alpha, decode limits, collection index compatibility, recursive watcher/debounce/config order.");
    }

    private static void indexedTimeline() {
        var frames = new java.util.ArrayList<EmojiDecoder.Frame>();
        for (int i = 0; i < 1000; i++) frames.add(new EmojiDecoder.Frame(new int[]{0}, 10 + i));
        var decoded = new EmojiDecoder.Decoded(1, 1, frames, 2);
        long elapsed = 0;
        for (int loop = 0; loop < 2; loop++) for (int i = 0; i < frames.size(); i++) {
            check(decoded.frameAt(elapsed) == i, "Indexed frame start");
            elapsed += frames.get(i).durationMillis();
            check(decoded.frameAt(elapsed - 1) == i, "Indexed frame end");
        }
        check(decoded.frameAt(elapsed) == 999 && decoded.frameAt(-10) == 0, "Finite loops and negative elapsed");
    }

    private static void defaultNames() {
        check(EmojiNames.defaultName("开心", 32).equals("开心"), "Keep usable source names");
        check(EmojiNames.defaultName("my meme:今天", 32).equals("my_meme_今天"), "Clean spaces and punctuation");
        check(EmojiNames.defaultName("\u00a0\t", 32).equals("表情"), "Blank names get a default");
        check(EmojiNames.defaultName("CON", 32).equals("_CON"), "Windows reserved filenames");
        check(EmojiNames.defaultName("abcdef", 3).equals("abc"), "Truncate long names");
        check(EmojiNames.defaultName("a\ud83d\ude00", 2).equals("a"), "Do not split surrogate pairs");
        check(EmojiNames.defaultName("\ud83d\ude00", 1).equals("表"), "Tiny limit still gets a usable name");
        check(!EmojiNames.isUsable("a\u00a0b", 32) && !EmojiNames.isUsable("a\u0000b", 32), "Reject invisible separators and controls");
    }

    private static void collectionIndexCompatibility() {
        UUID source = UUID.fromString("150e8400-e29b-41d4-a716-446655440000");
        UUID copy = UUID.fromString("150e8400-e29b-41d4-a716-446655440001");
        check(EmojiLibrary.readCollected("{\"version\":1,\"sources\":[]}").isEmpty(), "Legacy library index without collection map");
        check(EmojiLibrary.readCollected("{\"version\":1,\"sources\":[],\"collected\":{\"" + source + "\":\"" + copy + "\"}}")
                .equals(Map.of(source, copy)), "Collected mapping survives index reload");
    }

    private static void pngPreservesPixels() throws Exception {
        BufferedImage image = new BufferedImage(640, 320, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(639, 319, 0x8044cc88);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        EmojiDecoder.Decoded decoded = EmojiDecoder.decode(out.toByteArray());
        check(decoded.width() == 640 && decoded.height() == 320, "Original resolution must be preserved");
        check(pixel(decoded, 0, 639, 319) == 0x8044cc88, "PNG RGBA must be preserved");
    }

    private static void transparentGif() throws Exception {
        byte[] bytes = gif();
        check(EmojiDecoder.extension(bytes).equals("gif"), "GIF source format");
        EmojiDecoder.Decoded decoded = EmojiDecoder.decode(bytes);
        check(decoded.width() == 4 && decoded.height() == 4 && decoded.frames().size() == 4, "GIF frame count/canvas");
        check(pixel(decoded, 0, 0, 0) == 0xffff0000 && pixel(decoded, 0, 3, 3) == 0, "GIF transparency");
        check(pixel(decoded, 1, 0, 0) == 0xffff0000 && pixel(decoded, 1, 2, 0) == 0xff00ff00, "GIF overlay");
        check(pixel(decoded, 2, 2, 0) == 0 && pixel(decoded, 2, 0, 2) == 0xff0000ff, "GIF restoreToPrevious");
        check(pixel(decoded, 3, 0, 2) == 0 && pixel(decoded, 3, 2, 2) == 0xffffff00, "GIF restoreToBackgroundColor");
        check(decoded.frames().get(0).durationMillis() == 80 && decoded.frames().get(1).durationMillis() == 120, "GIF delays");
        check(decoded.frameAt(79) == 0 && decoded.frameAt(80) == 1 && decoded.frameAt(200) == 2, "GIF playback timing");
        check(decoded.loops() == 0 && decoded.frameAt(600) == 0, "GIF infinite loop");
    }

    private static void staticAndAnimatedWebp() throws Exception {
        check(EmojiDecoder.extension(RED_WEBP).equals("webp") && EmojiDecoder.extension(ANIMATED_WEBP).equals("webp"), "WebP source format");
        EmojiDecoder.Decoded still = EmojiDecoder.decode(RED_WEBP);
        check(still.width() == 4 && pixel(still, 0, 3, 3) == 0xffff0000, "Static WebP");
        EmojiDecoder.Decoded decoded = EmojiDecoder.decode(ANIMATED_WEBP);
        check(decoded.frames().size() == 3 && decoded.width() == 4 && decoded.height() == 4, "Animated WebP canvas");
        check(pixel(decoded, 1, 2, 2) == 0xff00ff00 && pixel(decoded, 1, 0, 0) == 0, "WebP optimized frame offset/disposal");
        check(pixel(decoded, 2, 0, 0) == 0xff0000ff, "WebP third frame");
        check(decoded.frames().get(0).durationMillis() == 80 && decoded.frames().get(1).durationMillis() == 120
                && decoded.frames().get(2).durationMillis() == 200, "WebP frame durations");
        check(decoded.frameAt(80) == 1 && decoded.frameAt(200) == 2 && decoded.frameAt(400) == 0, "WebP looping timeline");
    }

    private static void webpBlendAndDisposal() throws Exception {
        byte[] bytes = webp(new byte[][]{RED_WEBP, GREEN_WEBP, RED_WEBP, GREEN_WEBP}, new int[]{0, 2, 1, 0});
        EmojiDecoder.Decoded decoded = EmojiDecoder.decode(bytes);
        check(pixel(decoded, 1, 0, 0) == 0 && pixel(decoded, 1, 2, 2) == 0xff00ff00, "WebP no-blend replaces transparent pixels");
        check(pixel(decoded, 3, 0, 0) == 0 && pixel(decoded, 3, 2, 2) == 0xff00ff00, "WebP dispose-to-background");
        check(decoded.loops() == 1 && decoded.frameAt(100000) == 3, "WebP finite playback");
    }

    private static void rejectOversizedImages() throws Exception {
        BufferedImage image = new BufferedImage(2049, 1, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        rejects(() -> EmojiDecoder.inspect(out.toByteArray()), "Dimension limit");
        rejects(() -> EmojiDecoder.inspect(new byte[EmojiAsset.MAX_FILE_BYTES + 1]), "File size limit");
        byte[] oversizedAnimation = ANIMATED_WEBP.clone();
        // VP8X canvas enlarged to 2048x2048: three frames fit, repeat six times to exceed the pixel budget.
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(oversizedAnimation, 12, 18);
        byte[] header = body.toByteArray();
        header[12] = (byte) 255; header[13] = 7; header[15] = (byte) 255; header[16] = 7;
        body.reset(); body.write(header);
        body.write(oversizedAnimation, 30, 14);
        for (int i = 0; i < 6; i++) body.write(oversizedAnimation, 44, 48);
        rejects(() -> EmojiDecoder.inspect(riff(body.toByteArray())), "Animation total-pixel limit");
        rejects(() -> EmojiDecoder.decode(Arrays.copyOf(ANIMATED_WEBP, 80)), "Truncated WebP");
    }

    private static void webpRawAlpha() throws Exception {
        int[] alpha = {0, 20, 180, 255, 10, 70, 100, 190, 250, 220, 70, 15, 0, 90, 255, 128};
        for (int filter = 0; filter < 4; filter++) {
            ByteArrayOutputStream chunks = new ByteArrayOutputStream();
            chunk(chunks, "VP8X", new byte[]{16, 0, 0, 0, 3, 0, 0, 3, 0, 0});
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            data.write(filter << 2);
            for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
                int index = y * 4 + x;
                int left = x == 0 ? 0 : alpha[index - 1], above = y == 0 ? 0 : alpha[index - 4];
                int predictor = switch (filter) {
                    case 1 -> x == 0 ? above : left;
                    case 2 -> y == 0 ? left : above;
                    case 3 -> y == 0 ? left : x == 0 ? above : Math.max(0, Math.min(255, left + above - alpha[index - 5]));
                    default -> 0;
                };
                data.write((alpha[index] - predictor) & 255);
            }
            chunk(chunks, "ALPH", data.toByteArray());
            chunks.write(LOSSY_WEBP, 12, LOSSY_WEBP.length - 12);
            byte[] bytes = riff(chunks.toByteArray());
            EmojiDecoder.Decoded decoded = EmojiDecoder.decode(bytes);
            for (int i = 0; i < alpha.length; i++) check(decoded.frames().getFirst().argb(i) >>> 24 == alpha[i], "WebP raw alpha filter " + filter);
        }
    }

    private static void recursiveWatcher() throws Exception {
        Path temporary = Files.createTempDirectory("memeemoji-watcher-");
        AtomicInteger reloads = new AtomicInteger(), invalidations = new AtomicInteger();
        Path emoji = temporary.resolve("emoji"), config = temporary.resolve("config.json");
        Files.writeString(config, "{}");
        LibraryWatcher watcher = new LibraryWatcher(emoji, config, reloads::incrementAndGet, invalidations::incrementAndGet);
        try {
            watcher.open();
            Path nested = Files.createDirectories(emoji.resolve("中文分类").resolve("sub"));
            Path file = nested.resolve("表情.png");
            Files.write(file, new byte[]{1});
            await(() -> reloads.get() == 1, "New nested directory is watched");
            for (int i = 0; i < 3; i++) { Files.write(file, new byte[]{(byte) i}); Thread.sleep(60); }
            Thread.sleep(200);
            check(reloads.get() == 1, "Changes must be debounced for 500ms");
            await(() -> reloads.get() == 2, "Changes inside new nested directory trigger reload");
            Files.writeString(config, "{\"maxNameLength\":64}");
            await(() -> reloads.get() == 3, "Config update triggers reload");
            check(invalidations.get() == 1, "Config invalidated before reload");
            Files.writeString(config, "{\"maxNameLength\":64}");
            Thread.sleep(700);
            check(reloads.get() == 3 && invalidations.get() == 1, "Unchanged config does not self-trigger reload");
        } finally {
            watcher.stop();
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void await(java.util.function.BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        check(condition.getAsBoolean(), message);
    }

    private static byte[] gif() throws Exception {
        byte[] r = {0, (byte) 255, 0, 0, (byte) 255}, g = {0, 0, (byte) 255, 0, (byte) 255}, b = {0, 0, 0, (byte) 255, 0};
        IndexColorModel palette = new IndexColorModel(8, 5, r, g, b, 0);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            IIOMetadata stream = writer.getDefaultStreamMetadata(param);
            IIOMetadataNode streamTree = (IIOMetadataNode) stream.getAsTree("javax_imageio_gif_stream_1.0");
            IIOMetadataNode screen = (IIOMetadataNode) streamTree.getElementsByTagName("LogicalScreenDescriptor").item(0);
            screen.setAttribute("logicalScreenWidth", "4"); screen.setAttribute("logicalScreenHeight", "4");
            stream.setFromTree("javax_imageio_gif_stream_1.0", streamTree);
            writer.prepareWriteSequence(stream);
            for (int i = 0; i < 4; i++) {
                int side = i < 2 ? 4 : 2;
                BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_BYTE_INDEXED, palette);
                image.getRaster().setSample(i == 1 ? 2 : 0, 0, 0, i + 1);
                IIOMetadata metadata = writer.getDefaultImageMetadata(new javax.imageio.ImageTypeSpecifier(image), param);
                IIOMetadataNode tree = (IIOMetadataNode) metadata.getAsTree("javax_imageio_gif_image_1.0");
                IIOMetadataNode descriptor = (IIOMetadataNode) tree.getElementsByTagName("ImageDescriptor").item(0);
                descriptor.setAttribute("imageLeftPosition", i == 3 ? "2" : "0");
                descriptor.setAttribute("imageTopPosition", i >= 2 ? "2" : "0");
                IIOMetadataNode control = (IIOMetadataNode) tree.getElementsByTagName("GraphicControlExtension").item(0);
                control.setAttribute("disposalMethod", i == 1 ? "restoreToPrevious" : i == 2 ? "restoreToBackgroundColor" : "doNotDispose");
                control.setAttribute("transparentColorFlag", "TRUE"); control.setAttribute("transparentColorIndex", "0");
                control.setAttribute("delayTime", new String[]{"8", "12", "20", "20"}[i]);
                if (i == 0) {
                    IIOMetadataNode extensions = new IIOMetadataNode("ApplicationExtensions");
                    IIOMetadataNode extension = new IIOMetadataNode("ApplicationExtension");
                    extension.setAttribute("applicationID", "NETSCAPE"); extension.setAttribute("authenticationCode", "2.0");
                    extension.setUserObject(new byte[]{1, 0, 0}); extensions.appendChild(extension); tree.appendChild(extensions);
                }
                metadata.setFromTree("javax_imageio_gif_image_1.0", tree);
                writer.writeToSequence(new IIOImage(image, null, metadata), param);
            }
            writer.endWriteSequence();
        } finally { writer.dispose(); }
        return bytes.toByteArray();
    }

    private static byte[] webp(byte[][] images, int[] flags) throws IOException {
        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(new byte[]{18, 0, 0, 0}); little(header, 3, 3); little(header, 3, 3); chunk(chunks, "VP8X", header.toByteArray());
        chunk(chunks, "ANIM", new byte[]{0, 0, 0, 0, 1, 0});
        for (int i = 0; i < images.length; i++) {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            little(frame, 0, 3); little(frame, 0, 3); little(frame, 3, 3); little(frame, 3, 3);
            little(frame, 100, 3); frame.write(flags[i]); frame.write(images[i], 12, images[i].length - 12);
            chunk(chunks, "ANMF", frame.toByteArray());
        }
        return riff(chunks.toByteArray());
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        out.write(type.getBytes(StandardCharsets.US_ASCII)); little(out, data.length, 4); out.write(data);
        if ((data.length & 1) != 0) out.write(0);
    }

    private static byte[] riff(byte[] chunks) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("RIFF".getBytes(StandardCharsets.US_ASCII)); little(out, chunks.length + 4, 4);
        out.write("WEBP".getBytes(StandardCharsets.US_ASCII)); out.write(chunks); return out.toByteArray();
    }
    private static void little(ByteArrayOutputStream out, int value, int size) { for (int i = 0; i < size; i++) out.write(value >>> (i * 8) & 255); }
    private static int pixel(EmojiDecoder.Decoded image, int frame, int x, int y) { return image.frames().get(frame).argb(y * image.width() + x); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void rejects(Checked action, String message) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError(message);
    }
    private interface Checked { void run() throws Exception; }
}
