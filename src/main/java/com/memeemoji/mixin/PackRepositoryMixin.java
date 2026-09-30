package com.memeemoji.mixin;

import com.google.common.collect.ImmutableMap;
import com.memeemoji.MemeEmoji;
import com.memeemoji.MemeEmojiClient;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.FolderRepositorySource;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 把生成的资源包塞进客户端的资源包列表。
 *
 * <p>生成目录在 resourcepacks 之外，Fabric 的 registerBuiltinResourcePack 只能指向 mod jar 内部，所以只能在这里挂。
 * 资源包标记为 required，rebuildSelected 会自动把它加进已选列表，玩家也没法关掉。
 * 服务端的 PackRepository（数据包）也走同一个类，用 sources 里的 FolderRepositorySource 类型区分。
 */
@Mixin(PackRepository.class)
public abstract class PackRepositoryMixin {

    @Shadow
    @Final
    private Set<RepositorySource> sources;

    @Inject(method = "discoverAvailable", at = @At("RETURN"), cancellable = true)
    private void memeemoji$addGeneratedPack(CallbackInfoReturnable<Map<String, Pack>> cir) {
        if (!MemeEmoji.config().enabled || !memeemoji$isClientResourceRepository()) {
            return;
        }
        // 图集必须在这一步之前写好；客户端启动时资源包列表先于入口点初始化，所以生成动作挂在这里做。
        if (!MemeEmojiClient.ensurePackOnDisk()) {
            return;
        }
        Pack pack = memeemoji$createPack();
        if (pack == null) {
            return;
        }
        Map<String, Pack> available = new TreeMap<>(cir.getReturnValue());
        available.put(pack.getId(), pack);
        cir.setReturnValue(ImmutableMap.copyOf(available));
    }

    private boolean memeemoji$isClientResourceRepository() {
        for (RepositorySource source : this.sources) {
            if (source instanceof FolderRepositorySource folder
                    && ((FolderRepositorySourceAccessor) folder).memeemoji$packType() == PackType.CLIENT_RESOURCES) {
                return true;
            }
        }
        return false;
    }

    private Pack memeemoji$createPack() {
        Path directory = MemeEmoji.generatedPackDir();
        if (!Files.isRegularFile(directory.resolve("pack.mcmeta"))) {
            return null;
        }
        try {
            PackLocationInfo location = new PackLocationInfo(MemeEmoji.GENERATED_PACK_ID,
                    Component.literal("MemeEmoji"), PackSource.DEFAULT, Optional.empty());
            PackSelectionConfig selection = new PackSelectionConfig(true, Pack.Position.BOTTOM, false);
            return Pack.readMetaAndCreate(location, new PathPackResources.PathResourcesSupplier(directory),
                    PackType.CLIENT_RESOURCES, selection);
        } catch (RuntimeException e) {
            MemeEmoji.LOGGER.warn("读取生成的资源包失败，本次不加载表情图集", e);
            return null;
        }
    }
}
