package org.voxelhorizons.voxeldevelopment.screen;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.voxelhorizons.voxeldevelopment.VoxelDevelopmentPlugin;
import org.voxelhorizons.voxeldevelopment.text.TextResolver;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public final class HelpItemFactory {

    private final VoxelDevelopmentPlugin plugin;
    private final TextResolver textResolver;
    private boolean warnedVoxelCore;

    public HelpItemFactory(VoxelDevelopmentPlugin plugin, TextResolver textResolver) {
        this.plugin = plugin;
        this.textResolver = textResolver;
    }

    public ItemStack create(Player player) {
        if (!plugin.getConfig().getBoolean("features.join-overlay.help-item.enabled", false)) {
            return null;
        }

        String id = plugin.getConfig().getString("features.join-overlay.help-item.item", "").trim();
        if (id.isEmpty()) return null;

        ItemStack stack = createVoxelCoreItem(id);
        if (stack == null) return null;

        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            String displayName = plugin.getConfig().getString(
                    "features.join-overlay.help-item.display-name", "");
            if (displayName != null && !displayName.trim().isEmpty()) {
                meta.setDisplayName(textResolver.resolve(player, displayName));
            }

            List<String> configuredLore = plugin.getConfig().getStringList(
                    "features.join-overlay.help-item.lore");
            if (!configuredLore.isEmpty()) {
                List<String> lore = new ArrayList<String>(configuredLore.size());
                for (String line : configuredLore) {
                    lore.add(textResolver.resolve(player, line));
                }
                meta.setLore(lore);
            }

            stack.setItemMeta(meta);
        }

        stack.setAmount(1);
        return stack;
    }

    private ItemStack createVoxelCoreItem(String input) {
        Plugin voxelCore = plugin.getServer().getPluginManager().getPlugin("VoxelCore");
        if (voxelCore == null || !voxelCore.isEnabled()) {
            warn("VoxelCore is not enabled; join-overlay help item cannot be created.", null);
            return null;
        }

        try {
            Class<?> contentIdClass = Class.forName("org.voxelhorizons.content.ContentID");
            Method parse = contentIdClass.getMethod("parse", String.class, String.class);
            Object contentId = parse.invoke(null, input, "voxel");

            Method managerGetter = voxelCore.getClass().getMethod("getItemManager");
            Object itemManager = managerGetter.invoke(voxelCore);
            if (itemManager == null) return null;

            Method createItem = itemManager.getClass().getMethod("createItem", contentIdClass);
            Object result = createItem.invoke(itemManager, contentId);
            return result instanceof ItemStack ? ((ItemStack) result).clone() : null;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            warn("Unable to create VoxelCore help item '" + input + "'.", exception);
            return null;
        }
    }

    private void warn(String message, Throwable throwable) {
        if (warnedVoxelCore) return;
        warnedVoxelCore = true;
        if (throwable == null) plugin.getLogger().warning(message);
        else plugin.getLogger().log(Level.WARNING, message, throwable);
    }
}
