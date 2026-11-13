package com.memeemoji.platform;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import java.nio.file.Path;

public final class Platform {
    public static Path configDir() { return FMLPaths.CONFIGDIR.get(); }
    public static boolean isModLoaded(String id) { return ModList.get().isLoaded(id); }
}
