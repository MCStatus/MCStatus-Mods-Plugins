package me.mcstatus.selfhosted;

import net.md_5.bungee.api.plugin.Plugin;

public final class BungeePlugin extends Plugin {

    private MCStatus mc;

    @Override
    @SuppressWarnings("deprecation") // getHost() works on every BungeeCord version
    public void onEnable() {
        int port = getProxy().getConfig().getListeners().iterator().next().getHost().getPort();
        mc = MCStatus.start(getDataFolder().toPath(), port, getLogger());
    }

    @Override
    public void onDisable() {
        if (mc != null) {
            mc.stop();
        }
    }
}
