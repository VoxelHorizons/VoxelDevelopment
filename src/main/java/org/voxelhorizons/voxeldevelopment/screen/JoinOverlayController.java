package org.voxelhorizons.voxeldevelopment.screen;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.voxelhorizons.voxeldevelopment.VoxelDevelopmentPlugin;
import org.voxelhorizons.voxeldevelopment.text.TextResolver;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class JoinOverlayController implements Listener {

    private static final int LONG_EFFECT_DURATION = 20 * 60 * 60 * 24;

    private final VoxelDevelopmentPlugin plugin;
    private final TextResolver textResolver;
    private final Map<UUID, ScreenSession> active = new HashMap<UUID, ScreenSession>();

    public JoinOverlayController(VoxelDevelopmentPlugin plugin) {
        this.plugin = plugin;
        this.textResolver = new TextResolver(plugin);
    }

    public int activeCount() {
        return active.size();
    }

    public boolean isActive(Player player) {
        return player != null && active.containsKey(player.getUniqueId());
    }

    public void show(Player player, boolean forced) {
        if (player == null || !player.isOnline()) return;
        if (!forced) {
            if (!plugin.isDevelopmentEnabled()) return;
            if (!plugin.getConfig().getBoolean("features.join-overlay.enabled", true)) return;
            if (player.hasPermission("voxeldevelopment.bypass")) return;
        }

        clear(player);

        String configuredTitle = plugin.getConfig().getString(
                "features.join-overlay.inventory-title",
                "&0Development Notice");
        String title = textResolver.resolve(player, configuredTitle);

        Inventory inventory;
        try {
            inventory = Bukkit.createInventory(null, 54, title);
        } catch (IllegalArgumentException legacyTitleLimit) {
            plugin.getLogger().warning("Development overlay title is not valid on this server version; "
                    + "using a short fallback title. Ensure VoxelCore resolved the configured font alias.");
            inventory = Bukkit.createInventory(null, 54, "Development Notice");
        }

        PotionEffect previousBlindness = player.getPotionEffect(PotionEffectType.BLINDNESS);
        ScreenSession session = new ScreenSession(inventory, previousBlindness);
        active.put(player.getUniqueId(), session);

        if (plugin.getConfig().getBoolean("features.join-overlay.blindness.enabled", true)) {
            player.removePotionEffect(PotionEffectType.BLINDNESS);
            player.addPotionEffect(createHiddenBlindness(), true);
        }

        player.setVelocity(new Vector(0, 0, 0));
        player.openInventory(inventory);
    }

    public void clear(Player player) {
        clear(player, true);
    }

    private void clear(Player player, boolean closeInventory) {
        if (player == null) return;
        ScreenSession session = active.remove(player.getUniqueId());
        if (session == null) return;

        player.removePotionEffect(PotionEffectType.BLINDNESS);
        if (session.previousBlindness != null) {
            player.addPotionEffect(session.previousBlindness, true);
        }

        if (closeInventory && player.isOnline()
                && player.getOpenInventory() != null
                && player.getOpenInventory().getTopInventory().equals(session.inventory)) {
            player.closeInventory();
        }
    }

    public void clearAll() {
        for (UUID uuid : active.keySet().toArray(new UUID[0])) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) clear(player);
            else active.remove(uuid);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.isDevelopmentEnabled()) return;
        if (!plugin.getConfig().getBoolean("features.join-overlay.enabled", true)) return;
        if (event.getPlayer().hasPermission("voxeldevelopment.bypass")) return;

        long delay = Math.max(0L,
                plugin.getConfig().getLong("features.join-overlay.join-delay-ticks", 10L));
        final UUID uuid = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) show(player, false);
            }
        }, delay);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        ScreenSession session = active.get(player.getUniqueId());
        if (session == null || !session.inventory.equals(event.getInventory())) return;

        // Minecraft sends the same close-container packet for Escape and the inventory
        // key. Treat any client close of this dedicated blocker inventory as dismissal.
        clear(player, false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!blocked(event.getWhoClicked() instanceof Player ? (Player) event.getWhoClicked() : null,
                "inventory-clicks")) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!blocked(event.getWhoClicked() instanceof Player ? (Player) event.getWhoClicked() : null,
                "inventory-drags")) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!blocked(event.getPlayer(), "movement")) return;
        Location to = event.getTo();
        if (to == null) return;
        Location from = event.getFrom();
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()
                || from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch()) {
            event.setTo(from);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (blocked(event.getPlayer(), "interactions")) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (blocked(event.getPlayer(), "interactions")) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (blocked(event.getPlayer(), "interactions")) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        if (blocked(event.getPlayer(), "interactions")) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!blocked(event.getPlayer(), "commands")) return;
        if (isAllowedCommand(event.getMessage())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (blocked(event.getPlayer(), "chat")) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (blocked(event.getPlayer(), "teleports")) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        if (blocked((Player) event.getEntity(), "damage")) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        if (blocked((Player) event.getEntity(), "hunger")) event.setCancelled(true);
    }

    private boolean blocked(Player player, String flag) {
        return player != null
                && active.containsKey(player.getUniqueId())
                && plugin.getConfig().getBoolean("features.join-overlay.block." + flag, true);
    }

    private boolean isAllowedCommand(String raw) {
        String command = raw == null ? "" : raw.trim();
        if (command.startsWith("/")) command = command.substring(1);
        int space = command.indexOf(' ');
        String root = (space >= 0 ? command.substring(0, space) : command).toLowerCase(Locale.ROOT);
        int colon = root.indexOf(':');
        if (colon >= 0) root = root.substring(colon + 1);

        List<String> allowed = plugin.getConfig().getStringList("features.join-overlay.allow-commands");
        for (String candidate : allowed) {
            if (root.equals(candidate.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private PotionEffect createHiddenBlindness() {
        int amplifier = Math.max(0,
                plugin.getConfig().getInt("features.join-overlay.blindness.amplifier", 0));

        // On modern Bukkit the sixth constructor argument controls the HUD icon.
        // Reflection keeps the plugin binary-compatible with the 1.12 API.
        try {
            Constructor<PotionEffect> constructor = PotionEffect.class.getConstructor(
                    PotionEffectType.class,
                    int.class,
                    int.class,
                    boolean.class,
                    boolean.class,
                    boolean.class);
            return constructor.newInstance(
                    PotionEffectType.BLINDNESS,
                    LONG_EFFECT_DURATION,
                    amplifier,
                    false,
                    false,
                    false);
        } catch (ReflectiveOperationException ignored) {
            return new PotionEffect(
                    PotionEffectType.BLINDNESS,
                    LONG_EFFECT_DURATION,
                    amplifier,
                    false,
                    false);
        }
    }

    private static final class ScreenSession {
        private final Inventory inventory;
        private final PotionEffect previousBlindness;

        private ScreenSession(Inventory inventory, PotionEffect previousBlindness) {
            this.inventory = inventory;
            this.previousBlindness = previousBlindness;
        }
    }
}
