package com.memeemoji.qa;

import net.fabricmc.api.ClientModInitializer;
import java.nio.file.Path;

public final class FabricGameChecks implements ClientModInitializer {
    @Override public void onInitializeClient() {
        var checks = new EmojiGameChecks();
        if (System.getProperty("memeemoji.qa.role", "").equals("bubbles")
                && System.getProperty("memeemoji.qa.focus", "full").equals("full")) {
            checks.prepare();
            new TalkBubblesGameChecks(Path.of(System.getProperty("memeemoji.qa.root")).resolve("build/qa-evidence")).start();
        } else checks.start();
    }
}
