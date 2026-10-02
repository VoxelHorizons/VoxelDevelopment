package org.voxelhorizons.voxeldevelopment.text;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.voxelhorizons.voxeldevelopment.VoxelDevelopmentPlugin;

import java.lang.reflect.Method;
import java.util.logging.Level;

public final class TextResolver {

    private final VoxelDevelopmentPlugin plugin;
    private boolean warnedVoxelCore;
    private boolean warnedPlaceholderApi;

    public TextResolver(VoxelDevelopmentPlugin plugin) {
        this.plugin = plugin;
    }

    public String resolve(Player player, String input) {
        String text = input == null ? "" : input;
        text = resolveVoxelCore(text);
        text = resolvePlaceholderApi(player, text);
        return VoxelDevelopmentPlugin.color(text);
    }

    private String resolveVoxelCore(String input) {
        Plugin voxelCore = plugin.getServer().getPluginManager().getPlugin("VoxelCore");
        if (voxelCore == null || !voxelCore.isEnabled()) return input;

        try {
            Method serviceGetter = voxelCore.getClass().getMethod("getTextPlaceholderService");
            Object service = serviceGetter.invoke(voxelCore);
            if (service == null) return input;
            Method resolve = service.getClass().getMethod("resolve", String.class);
            Object result = resolve.invoke(service, input);
            return result instanceof String ? (String) result : input;
        } catch (ReflectiveOperationException exception) {
            if (!warnedVoxelCore) {
                warnedVoxelCore = true;
                plugin.getLogger().log(Level.WARNING,
                        "VoxelCore is installed but its TextPlaceholderService could not be invoked. "
                                + "Banner aliases will remain literal.", exception);
            }
            return input;
        }
    }

    private String resolvePlaceholderApi(Player player, String input) {
        Plugin papi = plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI");
        if (papi == null || !papi.isEnabled()) return input;

        try {
            Class<?> placeholderApi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            Method method = placeholderApi.getMethod("setPlaceholders", Player.class, String.class);
            Object result = method.invoke(null, player, input);
            return result instanceof String ? (String) result : input;
        } catch (ReflectiveOperationException exception) {
            if (!warnedPlaceholderApi) {
                warnedPlaceholderApi = true;
                plugin.getLogger().log(Level.WARNING,
                        "PlaceholderAPI is installed but placeholders could not be resolved.", exception);
            }
            return input;
        }
    }
}
