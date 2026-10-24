package me.mcstatus.selfhosted;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;

import java.nio.file.Path;
import java.util.logging.Logger;

/** Declared in velocity-plugin.json. */
public final class VelocityPlugin {

    private final ProxyServer proxy;
    private final Path dir;
    private MCStatus mc;

    @Inject
    public VelocityPlugin(ProxyServer proxy, @DataDirectory Path dir) {
        this.proxy = proxy;
        this.dir = dir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        mc = MCStatus.start(dir, proxy.getBoundAddress().getPort(), Logger.getLogger("MCStatus")); // Velocity routes JUL to its log
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (mc != null) {
            mc.stop();
        }
    }
}
