package org.voxelhorizons.voxeldevelopment;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.voxelhorizons.voxeldevelopment.command.DevelopmentCommand;
import org.voxelhorizons.voxeldevelopment.screen.JoinOverlayController;

public final class VoxelDevelopmentPlugin extends JavaPlugin {

    private JoinOverlayController overlayController;
    private boolean developmentEnabled;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        developmentEnabled = getConfig().getBoolean("enabled", false);

        overlayController = new JoinOverlayController(this);
        getServer().getPluginManager().registerEvents(overlayController, this);

        DevelopmentCommand command = new DevelopmentCommand(this, overlayController);
        PluginCommand pluginCommand = getCommand("vdev");
        if (pluginCommand == null) {
            getLogger().severe("Command 'vdev' is missing from plugin.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);

        getLogger().info("VoxelDevelopment enabled. Development mode is "
                + (developmentEnabled ? "ON" : "OFF") + ".");
    }

    @Override
    public void onDisable() {
        if (overlayController != null) overlayController.clearAll();
    }

    public boolean isDevelopmentEnabled() {
        return developmentEnabled;
    }

    public void setDevelopmentEnabled(boolean enabled) {
        developmentEnabled = enabled;
        getConfig().set("enabled", enabled);
        saveConfig();
        if (!enabled && overlayController != null) overlayController.clearAll();
    }

    public void reloadRuntimeConfig() {
        reloadConfig();
        developmentEnabled = getConfig().getBoolean("enabled", developmentEnabled);
        if (!developmentEnabled && overlayController != null) overlayController.clearAll();
    }

    public String message(String key) {
        String prefix = getConfig().getString("messages.prefix", "");
        String value = getConfig().getString("messages." + key, key);
        return color(prefix + value);
    }

    public static String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    public Player findOnlinePlayer(String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) return exact;
        return Bukkit.getPlayer(name);
    }
}
