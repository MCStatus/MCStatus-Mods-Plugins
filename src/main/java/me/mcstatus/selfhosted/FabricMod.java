package me.mcstatus.selfhosted;

import net.fabricmc.api.DedicatedServerModInitializer;

public final class FabricMod implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        ModEntry.start();
    }
}
