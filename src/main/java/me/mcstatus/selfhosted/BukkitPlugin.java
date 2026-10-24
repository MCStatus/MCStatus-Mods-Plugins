package me.mcstatus.selfhosted;

import org.bukkit.plugin.java.JavaPlugin;

public final class BukkitPlugin extends JavaPlugin {

    private MCStatus mc;

    @Override
    public void onEnable() {
        mc = MCStatus.start(getDataFolder().toPath(), getServer().getPort(), getLogger());
    }

    @Override
    public void onDisable() {
        if (mc != null) {
            mc.stop();
        }
    }
}
