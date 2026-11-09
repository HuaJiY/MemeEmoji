package com.memeemoji.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.memeemoji.MemeEmoji;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class PickerPreferences {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Set<UUID> favorites = new LinkedHashSet<>();
    private final List<UUID> recent = new ArrayList<>();

    static PickerPreferences load() {
        PickerPreferences preferences = new PickerPreferences();
        Path path = path();
        if (!Files.isRegularFile(path)) return preferences;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root != null) {
                preferences.favorites.addAll(readIds(root.getAsJsonArray("favorites")));
                preferences.recent.addAll(readIds(root.getAsJsonArray("recent")).stream().limit(60).toList());
            }
        } catch (IOException | RuntimeException exception) {
            MemeEmoji.LOGGER.warn("Cannot read emoji library preferences", exception);
        }
        return preferences;
    }

    private static List<UUID> readIds(JsonArray array) {
        if (array == null) return List.of();
        Set<UUID> ids = new LinkedHashSet<>();
        for (JsonElement element : array) ids.add(UUID.fromString(element.getAsString()));
        return List.copyOf(ids);
    }

    boolean isFavorite(UUID id) { return favorites.contains(id); }
    List<UUID> recent() { return List.copyOf(recent); }
    void toggleFavorite(UUID id) { if (!favorites.remove(id)) favorites.add(id); }
    void favorite(UUID id) { favorites.add(id); }
    void remove(Set<UUID> ids) { favorites.removeAll(ids); recent.removeAll(ids); }
    void use(UUID id) {
        recent.remove(id);
        recent.addFirst(id);
        if (recent.size() > 60) recent.removeLast();
    }

    void save() throws IOException {
        JsonObject root = new JsonObject();
        root.add("favorites", GSON.toJsonTree(favorites.stream().map(UUID::toString).toList()));
        root.add("recent", GSON.toJsonTree(recent.stream().map(UUID::toString).toList()));
        Files.createDirectories(path().getParent());
        try (Writer writer = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) { GSON.toJson(root, writer); }
    }

    private static Path path() { return MemeEmoji.configDir().resolve("library-ui.json"); }
}
