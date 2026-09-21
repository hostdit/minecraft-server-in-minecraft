package dev.hostd.mcinmc;

import net.fabricmc.api.ModInitializer;

public class McInMc implements ModInitializer {
    @Override
    public void onInitialize() {
        Host.init();
        Table.init();
        ServerSigns.init();
    }
}
