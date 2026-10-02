package org.voxelhorizons.voxeldevelopment.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.voxelhorizons.voxeldevelopment.VoxelDevelopmentPlugin;
import org.voxelhorizons.voxeldevelopment.screen.JoinOverlayController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class DevelopmentCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ROOT = Arrays.asList("on", "off", "status", "show", "clear", "reload");

    private final VoxelDevelopmentPlugin plugin;
    private final JoinOverlayController overlays;

    public DevelopmentCommand(VoxelDevelopmentPlugin plugin, JoinOverlayController overlays) {
        this.plugin = plugin;
        this.overlays = overlays;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("voxeldevelopment.admin")) {
            sender.sendMessage(VoxelDevelopmentPlugin.color("&cYou do not have permission."));
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if ("on".equals(sub)) {
            plugin.setDevelopmentEnabled(true);
            sender.sendMessage(plugin.message("enabled"));
            return true;
        }
        if ("off".equals(sub)) {
            plugin.setDevelopmentEnabled(false);
            sender.sendMessage(plugin.message("disabled"));
            return true;
        }
        if ("status".equals(sub)) {
            sender.sendMessage(plugin.message(plugin.isDevelopmentEnabled() ? "status-on" : "status-off"));
            sender.sendMessage(VoxelDevelopmentPlugin.color("&7Active locked screens: &f" + overlays.activeCount()));
            return true;
        }
        if ("reload".equals(sub)) {
            plugin.reloadRuntimeConfig();
            sender.sendMessage(plugin.message("reloaded"));
            return true;
        }
        if ("show".equals(sub)) {
            if (args.length < 2) {
                sender.sendMessage(VoxelDevelopmentPlugin.color("&cUsage: /" + label + " show <player>"));
                return true;
            }
            Player player = plugin.findOnlinePlayer(args[1]);
            if (player == null) {
                sender.sendMessage(plugin.message("no-player"));
                return true;
            }
            overlays.show(player, true);
            sender.sendMessage(plugin.message("shown").replace("%player%", player.getName()));
            return true;
        }
        if ("clear".equals(sub)) {
            if (args.length < 2) {
                sender.sendMessage(VoxelDevelopmentPlugin.color("&cUsage: /" + label + " clear <player|*>"));
                return true;
            }
            if ("*".equals(args[1])) {
                int count = overlays.activeCount();
                overlays.clearAll();
                sender.sendMessage(VoxelDevelopmentPlugin.color("&aCleared &f" + count + "&a development screen(s)."));
                return true;
            }
            Player player = plugin.findOnlinePlayer(args[1]);
            if (player == null) {
                sender.sendMessage(plugin.message("no-player"));
                return true;
            }
            overlays.clear(player);
            sender.sendMessage(plugin.message("cleared").replace("%player%", player.getName()));
            return true;
        }

        sendUsage(sender, label);
        return true;
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(VoxelDevelopmentPlugin.color(
                "&b/" + label + " &7<on|off|status|show <player>|clear <player|*>|reload>"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("voxeldevelopment.admin")) return Collections.emptyList();
        if (args.length == 1) return match(args[0], ROOT);
        if (args.length == 2 && ("show".equalsIgnoreCase(args[0]) || "clear".equalsIgnoreCase(args[0]))) {
            List<String> names = new ArrayList<String>();
            if ("clear".equalsIgnoreCase(args[0])) names.add("*");
            for (Player player : Bukkit.getOnlinePlayers()) names.add(player.getName());
            return match(args[1], names);
        }
        return Collections.emptyList();
    }

    private static List<String> match(String prefix, List<String> values) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<String>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) out.add(value);
        }
        return out;
    }
}
