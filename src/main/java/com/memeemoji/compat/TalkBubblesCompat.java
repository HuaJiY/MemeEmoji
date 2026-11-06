package com.memeemoji.compat;

import com.memeemoji.chat.EmojiChatRenderer;
import com.memeemoji.media.EmojiTextures;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntitySelector;
import net.talkbubbles.TalkBubbles;
import net.talkbubbles.accessor.AbstractClientPlayerEntityAccessor;
import net.talkbubbles.mixin.DrawContextAccessor;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.UUID;

public final class TalkBubblesCompat {
    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("talkbubbles");

    private TalkBubblesCompat() { }

    public static void received(UUID sender, String text) {
        if (LOADED && EmojiChatRenderer.hasImages(text)) Bridge.received(sender, text);
    }

    public static boolean render(PoseStack pose, MultiBufferSource buffers, Font font,
                                 EntityRenderDispatcher dispatcher, List<String> text, float playerHeight, int light) {
        if (!LOADED || text == null) return false;
        String content = String.join(" ", text);
        if (!EmojiChatRenderer.hasImages(content)) return false;
        Bridge.render(pose, buffers, font, dispatcher, content, playerHeight, light);
        return true;
    }

    // Keep references to the optional mod behind the loaded check.
    private static final class Bridge {
        private static final ResourceLocation BACKGROUND = ResourceLocation.fromNamespaceAndPath("talkbubbles", "textures/gui/background.png");

        static void received(UUID sender, String text) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;
            var config = TalkBubbles.CONFIG;
            for (AbstractClientPlayer player : mc.level.getEntitiesOfClass(AbstractClientPlayer.class,
                    mc.player.getBoundingBox().inflate(config.chatRange), EntitySelector.NO_SPECTATORS)) {
                if (!player.getUUID().equals(sender) || (player == mc.player && !config.showOwnBubble)) continue;
                // Store the raw body instead of TalkBubbles' text-only wrapping of UUID markers.
                ((AbstractClientPlayerEntityAccessor) player).setChatText(List.of(text), player.tickCount, 0, 0);
                break;
            }
        }

        static void render(PoseStack pose, MultiBufferSource buffers, Font font, EntityRenderDispatcher dispatcher,
                           String content, float playerHeight, int light) {
            Minecraft mc = Minecraft.getInstance();
            var config = TalkBubbles.CONFIG;
            List<EmojiChatRenderer.RichLine> lines = EmojiChatRenderer.messageLayout(Component.literal(content), config.maxChatWidth, 9);
            int width = Math.max(4, lines.stream().mapToInt(EmojiChatRenderer.RichLine::width).max().orElse(1));
            int height = lines.stream().mapToInt(EmojiChatRenderer.RichLine::height).sum();
            pose.pushPose();
            pose.translate(0, playerHeight + .9f + (lines.size() > 5 ? .1f : 0) + config.chatHeight, 0);
            // Match TalkBubbles' cylindrical billboard: yaw follows the camera, pitch stays upright.
            Quaternionf rotation = dispatcher.cameraOrientation();
            float yaw = rotation.getEulerAnglesYXZ(new Vector3f()).y;
            pose.mulPose(Axis.YP.rotation(yaw));
            pose.scale(.025f * config.chatScale, -.025f * config.chatScale, .025f);
            GuiGraphics graphics = DrawContextAccessor.getDrawContext(mc, pose, mc.renderBuffers().bufferSource());
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
            RenderSystem.setShaderColor(config.backgroundRed, config.backgroundGreen, config.backgroundBlue, config.backgroundOpacity);
            RenderSystem.enablePolygonOffset();
            RenderSystem.polygonOffset(3, 3);
            background(graphics, -width / 2 - 3, -height - 3, width + 6, height + 6);
            RenderSystem.polygonOffset(0, 0);
            RenderSystem.disablePolygonOffset();
            RenderSystem.setShaderColor(1, 1, 1, 1);
            int y = -height;
            for (var line : lines) {
                int x = -line.width() / 2;
                int bottom = y + line.height();
                for (var part : line.parts()) {
                    if (part.asset() != null) {
                        EmojiTextures.render(graphics, part.asset(), x + part.x(), bottom - part.height() - 2,
                                part.width(), part.height(), 255);
                    } else {
                        font.drawInBatch(Component.literal(part.text()).withStyle(part.style()), x + part.x(), bottom - 9,
                                config.chatColor, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, light);
                    }
                }
                y = bottom;
            }
            graphics.flush();
            pose.popPose();
            RenderSystem.disableBlend();
            RenderSystem.setShaderColor(1, 1, 1, 1);
        }

        private static void background(GuiGraphics graphics, int x, int y, int width, int height) {
            int[] positionsX = {x, x + 5, x + width - 5};
            int[] positionsY = {y, y + 5, y + height - 5};
            int[] widths = {5, width - 10, 5};
            int[] heights = {5, height - 10, 5};
            int[] uvX = {0, 6, 12};
            int[] uvY = {0, 6, 8};
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    graphics.blit(BACKGROUND, positionsX[col], positionsY[row], widths[col], heights[row],
                            uvX[col], uvY[row], col == 1 ? 1 : 5, row == 1 ? 1 : 5, 32, 32);
                }
            }
        }
    }
}
