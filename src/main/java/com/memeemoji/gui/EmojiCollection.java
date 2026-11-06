package com.memeemoji.gui;

import com.memeemoji.media.EmojiAsset;
import com.memeemoji.media.EmojiLibrary;
import com.memeemoji.net.EmojiNetworkClient;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class EmojiCollection {
    private static final ConcurrentMap<UUID, CompletableFuture<EmojiAsset>> IN_PROGRESS = new ConcurrentHashMap<>();

    private EmojiCollection() { }

    public static CompletableFuture<EmojiAsset> collect(UUID id) {
        if (id == null) return CompletableFuture.failedFuture(new IllegalArgumentException("图片 UUID 无效"));
        CompletableFuture<EmojiAsset> collection = new CompletableFuture<>();
        CompletableFuture<EmojiAsset> existing = IN_PROGRESS.putIfAbsent(id, collection);
        if (existing != null) return existing;
        collection.whenComplete((asset, failure) -> IN_PROGRESS.remove(id, collection));
        EmojiAsset personalCopy = EmojiLibrary.personalCopy(id);
        CompletableFuture<EmojiAsset> ready = personalCopy != null
                ? CompletableFuture.completedFuture(personalCopy)
                : EmojiNetworkClient.fetch(id).thenCompose(ignored -> EmojiLibrary.collect(id));
        ready
                .thenCompose(EmojiCollection::favoriteOnClient)
                .whenComplete((asset, failure) -> {
                    if (failure == null) collection.complete(asset);
                    else collection.completeExceptionally(failure);
                });
        return collection;
    }

    private static CompletableFuture<EmojiAsset> favoriteOnClient(EmojiAsset asset) {
        CompletableFuture<EmojiAsset> result = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                PickerPreferences preferences = PickerPreferences.load();
                preferences.favorite(asset.id());
                preferences.save();
                result.complete(asset);
            } catch (IOException | RuntimeException exception) {
                result.completeExceptionally(exception instanceof CompletionException ? exception : new CompletionException(exception));
            }
        });
        return result;
    }
}
