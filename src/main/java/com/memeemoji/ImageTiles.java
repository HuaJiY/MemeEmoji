package com.memeemoji;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

public final class ImageTiles {
    private static final Set<String> EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "gif", "bmp");

    private ImageTiles() {
    }

    public static boolean isImageFile(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** 快速读取图片尺寸而不解码完整像素数据。 */
    public static Dimension readDimension(Path file) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) throw new IOException("无法打开图片流");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IOException("没有可用的解码器");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) throw new IOException("图片尺寸无效：" + width + "x" + height);
                return new Dimension(width, height);
            } finally {
                reader.dispose();
            }
        }
    }

    /** 只取第一帧：BitmapProvider 读的是静态 NativeImage，动图在字体里没法逐帧播放。 */
    public static BufferedImage read(Path file) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) throw new IOException("无法打开图片流");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IOException("没有可用的解码器（可能是缺少 WebP 支持）");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                BufferedImage image = reader.read(0);
                if (image == null) throw new IOException("解码结果为空");
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    public static BufferedImage read(byte[] png) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null) throw new IOException("PNG 解码失败");
        return image;
    }

    /**
     * 只缩小超过 maxSize 的大图，小图保持原始尺寸（不补白、不放大）。
     * 返回 FitResult 包含处理后的图片及其逻辑尺寸。
     */
    public static FitResult fitToMax(BufferedImage source, int maxSize) {
        int w = source.getWidth();
        int h = source.getHeight();

        if (w <= maxSize && h <= maxSize) {
            return new FitResult(source, w, h);
        }

        double scale = (double) maxSize / Math.max(w, h);
        int tw = Math.max(1, (int) Math.round(w * scale));
        int th = Math.max(1, (int) Math.round(h * scale));
        BufferedImage scaled = scale(source, tw, th);
        return new FitResult(scaled, tw, th);
    }

    /**
     * 把图片缩放到 targetSize×targetSize 画布内（居中+ bicubic），
     * 用于表情选择器统一尺寸展示。
     */
    public static BufferedImage scaleToFill(BufferedImage source, int targetSize) {
        BufferedImage canvas = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            int w = source.getWidth();
            int h = source.getHeight();
            double scale = (double) targetSize / Math.max(w, h);
            int tw = Math.max(1, (int) Math.round(w * scale));
            int th = Math.max(1, (int) Math.round(h * scale));
            BufferedImage scaled = scale(source, tw, th);
            g.drawImage(scaled, (targetSize - tw) / 2, (targetSize - th) / 2, null);
        } finally {
            g.dispose();
        }
        return canvas;
    }

    public static byte[] toPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("没有 PNG 编码器");
        }
        return out.toByteArray();
    }

    static BufferedImage scale(BufferedImage source, int targetWidth, int targetHeight) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (width == targetWidth && height == targetHeight) {
            return source;
        }
        BufferedImage current = source;
        while (current.getWidth() > targetWidth * 2 && current.getHeight() > targetHeight * 2) {
            current = drawScaled(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        return drawScaled(current, targetWidth, targetHeight);
    }

    private static BufferedImage drawScaled(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    /**
     * 处理后的图片及其逻辑尺寸。
     */
    public record FitResult(BufferedImage image, int width, int height) {
    }
}
