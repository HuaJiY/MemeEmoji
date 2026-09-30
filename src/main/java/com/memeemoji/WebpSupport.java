package com.memeemoji;

import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ImageWriterSpi;

/**
 * WebP 解码靠 JNI 原生库，加载失败时只是没有 WebP 能力，不能让整个 mod 挂掉，所以这里吞掉异常并降级。
 */
public final class WebpSupport {
    private static volatile boolean registered;
    private static volatile boolean available;

    private WebpSupport() {
    }

    public static void ensureRegistered() {
        if (registered) {
            return;
        }
        synchronized (WebpSupport.class) {
            if (registered) {
                return;
            }
            try {
                Class<?> readerSpi = Class.forName("com.luciad.imageio.webp.WebPImageReaderSpi");
                Class<?> writerSpi = Class.forName("com.luciad.imageio.webp.WebPImageWriterSpi");
                IIORegistry registry = IIORegistry.getDefaultInstance();
                ImageReaderSpi reader = (ImageReaderSpi) readerSpi.getDeclaredConstructor().newInstance();
                ImageWriterSpi writer = (ImageWriterSpi) writerSpi.getDeclaredConstructor().newInstance();
                registry.registerServiceProvider(reader, ImageReaderSpi.class);
                registry.registerServiceProvider(writer, ImageWriterSpi.class);
                available = true;
            } catch (Throwable t) {
                available = false;
                MemeEmoji.LOGGER.warn("WebP 解码库不可用，.webp 表情会被跳过", t);
            }
            registered = true;
        }
    }

    public static boolean isAvailable() {
        return available;
    }
}
