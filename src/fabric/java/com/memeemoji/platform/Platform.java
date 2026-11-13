package com.memeemoji.platform;

import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Path;

public final class Platform {
    public static Path configDir() { return FabricLoader.getInstance().getConfigDir(); }
    public static boolean isModLoaded(String id) { return FabricLoader.getInstance().isModLoaded(id); }
}
