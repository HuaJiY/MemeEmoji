package com.memeemoji.gui;

import com.memeemoji.MemeEmoji;
import com.memeemoji.MemeEmojiConfig;
import com.google.gson.JsonSyntaxException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;

public final class GlassStyle {
    private static PostChain blur;
    private static int blurWidth, blurHeight;
    private static boolean blurFailed;
    final int text;
    final int muted;
    final int accent;
    final int surface;
    final int control;
    final int hover;
    final int selected;
    final int border;
    private final MemeEmojiConfig config;

    GlassStyle(MemeEmojiConfig config) {
        this.config = config;
        boolean light = "light".equals(config.uiTheme);
        surface = rgb(light ? config.lightSurfaceColor : config.darkSurfaceColor);
        accent = rgb(config.accentColor);
        text = light ? 0xFF182B27 : 0xFFF0F5F3;
        muted = light ? 0xFF4D635C : 0xFFB4C5BF;
        control = mix(surface, light ? 0xFFFFFFFF : 0xFF000000, 0.20f);
        hover = mix(surface, accent, light ? 0.22f : 0.16f);
        selected = mix(surface, accent, 0.32f);
        border = mix(surface, light ? 0xFF46685B : 0xFFE3FFF3, light ? 0.22f : 0.18f);
    }

    static GlassStyle current() { return new GlassStyle(MemeEmoji.config()); }

    void background(GuiGraphics graphics, int width, int height, float delta) {
        Minecraft mc = Minecraft.getInstance();
        if (config.blurRadius > 0 && !blurFailed) {
            if (blur == null) {
                try {
                    // ModernUI may remove GameRenderer's blur chain; share vanilla shaders, not its instance.
                    blur = new PostChain(mc.getTextureManager(), mc.getResourceManager(), mc.getMainRenderTarget(),
                            ResourceLocation.withDefaultNamespace("shaders/post/blur.json"));
                } catch (IOException | JsonSyntaxException failure) {
                    blurFailed = true;
                    MemeEmoji.LOGGER.error("Cannot load menu background blur", failure);
                }
            }
            if (blur != null) {
                graphics.flush();
                int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
                if (w != blurWidth || h != blurHeight) {
                    blur.resize(w, h); blurWidth = w; blurHeight = h;
                }
                blur.setUniform("Radius", config.blurRadius);
                blur.process(delta);
                mc.getMainRenderTarget().bindWrite(false);
            }
        }
        graphics.fill(0, 0, width, height, "light".equals(config.uiTheme) ? 0x30121A18 : 0x55101715);
    }

    public static void release() {
        if (blur != null) { blur.close(); blur = null; }
        blurWidth = 0; blurHeight = 0; blurFailed = false;
    }

    void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        rounded(graphics, x, y + 3, width, height, 8, 0x30000000);
        rounded(graphics, x, y, width, height, 8, alpha(border, 60));
        rounded(graphics, x + 1, y + 1, width - 2, height - 2, 7, alpha(surface, config.panelOpacity));
    }

    static void rounded(GuiGraphics graphics, int x, int y, int width, int height, int radius, int color) {
        if (width <= 0 || height <= 0) return;
        int r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
        graphics.fill(x, y + r, x + width, y + height - r, color);
        for (int row = 0; row < r; row++) {
            int inset = (int) Math.ceil(r - Math.sqrt(r * r - (r - row - 0.5) * (r - row - 0.5)));
            graphics.fill(x + inset, y + row, x + width - inset, y + row + 1, color);
            graphics.fill(x + inset, y + height - row - 1, x + width - inset, y + height - row, color);
        }
    }

    static int rgb(String color) { return 0xFF000000 | Integer.parseInt(color.substring(1), 16); }
    static int alpha(int color, int alpha) { return (color & 0xFFFFFF) | (alpha << 24); }
    private static int mix(int a, int b, float amount) {
        int r = (int) (((a >> 16) & 255) * (1 - amount) + ((b >> 16) & 255) * amount);
        int g = (int) (((a >> 8) & 255) * (1 - amount) + ((b >> 8) & 255) * amount);
        int blue = (int) ((a & 255) * (1 - amount) + (b & 255) * amount);
        return 0xFF000000 | r << 16 | g << 8 | blue;
    }
}
