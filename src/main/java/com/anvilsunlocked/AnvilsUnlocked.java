package com.anvilsunlocked;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class AnvilsUnlocked extends JavaPlugin {

    private static AnvilsUnlocked instance;

    public static AnvilsUnlocked getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        Bukkit.getPluginManager().registerEvents(new UncappedAnvilListener(), this);
        getLogger().info("AnvilsUnlocked enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("AnvilsUnlocked disabled");
    }
}
