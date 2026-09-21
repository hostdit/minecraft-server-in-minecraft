package dev.hostd.mcinmc;

import net.fabricmc.api.ClientModInitializer;

public class McInMcClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Hosting.init();
        Hud.init();
    }
}
