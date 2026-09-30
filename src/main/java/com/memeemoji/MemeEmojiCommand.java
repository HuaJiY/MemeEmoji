package com.memeemoji;

import com.memeemoji.net.EmojiSync;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class MemeEmojiCommand {
    private MemeEmojiCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("memeemoji")
                        // 单人游戏的房主默认没有管理员权限，这里放行单人，联机服务器仍需管理员。
                        .requires(source -> source.getServer().isSingleplayer() || source.hasPermission(2))
                        .then(Commands.literal("reload").executes(context -> reload(context.getSource())))));
    }

    private static int reload(CommandSourceStack source) {
        List<EmojiTile> tiles = EmojiSync.rescanServerTiles();
        EmojiSync.sendToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "MemeEmoji：重新扫描到 " + tiles.size() + " 个表情，已同步给在线玩家"), true);
        return tiles.size();
    }
}
