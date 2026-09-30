package com.memeemoji;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
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

    /** 只取第一帧：BitmapProvider 读的是静态 NativeImage，动图在字体里没法逐帧播放。 */
    public static BufferedImage read(Path file) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) {
                throw new IOException("无法打开图片流");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("没有可用的解码器（可能是缺少 WebP 支持）");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new IOException("解码结果为空");
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    public static BufferedImage read(byte[] png) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null) {
            throw new IOException("PNG 解码失败");
        }
        return image;
    }

    /** 缩放图片适配 cell×cell 画布，小图上采样到大尺寸以避免模糊。 */
    public static BufferedImage fit(BufferedImage source, int cell) {
        int width = source.getWidth();
        int height = source.getHeight();
        double scale = (double) cell / Math.max(width, height);
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));

        BufferedImage canvas = new BufferedImage(cell, cell, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = canvas.createGraphics();
        try {
            graphics.drawImage(scale(source, targetWidth, targetHeight), (cell - targetWidth) / 2, (cell - targetHeight) / 2, null);
        } finally {
            graphics.dispose();
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

    private static BufferedImage scale(BufferedImage source, int targetWidth, int targetHeight) {
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
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }
}
