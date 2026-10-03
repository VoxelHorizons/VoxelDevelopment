package org.voxelhorizons.voxeldevelopment.screen;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
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
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.voxelhorizons.voxeldevelopment.VoxelDevelopmentPlugin;
import org.voxelhorizons.voxeldevelopment.screen.InventorySnapshotStore.InventorySnapshot;
import org.voxelhorizons.voxeldevelopment.text.TextResolver;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class JoinOverlayController implements Listener {

    private static final int LONG_EFFECT_DURATION = 20 * 60 * 60 * 24;

    private final VoxelDevelopmentPlugin plugin;
    private final TextResolver textResolver;
    private final InventorySnapshotStore snapshots;
    private final HelpItemFactory helpItems;
    private final Map<UUID, ScreenSession> active = new HashMap<UUID, ScreenSession>();
    private final Set<UUID> pendingJoinScreens = new HashSet<UUID>();

    public JoinOverlayController(VoxelDevelopmentPlugin plugin) {
        this.plugin = plugin;
        this.textResolver = new TextResolver(plugin);
        this.snapshots = new InventorySnapshotStore(plugin);
        this.helpItems = new HelpItemFactory(plugin, textResolver);
    }

    public int activeCount() {
        return active.size();
    }

    public boolean isActive(Player player) {
        return player != null && active.containsKey(player.getUniqueId());
    }

    public void recoverOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            snapshots.restoreIfPresent(player);
        }
    }

    public void show(Player player, boolean forced) {
        showInternal(player, forced);
    }

    private boolean showInternal(Player player, boolean forced) {
        if (player == null || !player.isOnline()) return false;
        if (!forced) {
            if (!plugin.isDevelopmentEnabled()) return false;
            if (!plugin.getConfig().getBoolean("features.join-overlay.enabled", true)) return false;
            if (shouldBypass(player)) return false;
        }

        // Never stack sessions. A replacement first restores the previous authoritative
        // snapshot and closes the old blocker before capturing again.
        clear(player);

        // If a previous server/process interruption left a recovery snapshot, restore it
        // before starting a new overlay. This prevents capturing an already-cleared inventory.
        snapshots.restoreIfPresent(player);

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

        final InventorySnapshot snapshot;
        try {
            // Disk snapshot MUST exist before a single live slot is cleared.
            snapshot = snapshots.capture(player);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "Refusing to open development overlay for " + player.getName()
                            + " because their inventory recovery snapshot could not be saved.", exception);
            return false;
        }

        PotionEffect previousBlindness = player.getPotionEffect(PotionEffectType.BLINDNESS);
        ScreenSession session = new ScreenSession(inventory, previousBlindness, snapshot, System.currentTimeMillis());
        active.put(player.getUniqueId(), session);

        // Once the snapshot is durable, the live inventory becomes intentionally empty
        // so held/armor/custom-model items cannot render over the menu.
        snapshots.clearLiveInventory(player);
        placeHelpItem(player);

        if (plugin.getConfig().getBoolean("features.join-overlay.blindness.enabled", true)) {
            player.removePotionEffect(PotionEffectType.BLINDNESS);
            player.addPotionEffect(createHiddenBlindness(), true);
        }

        player.setVelocity(new Vector(0, 0, 0));
        player.openInventory(inventory);
        return true;
    }

    public void clear(Player player) {
        clear(player, true);
    }

    private void clear(Player player, boolean closeInventory) {
        if (player == null) return;
        ScreenSession session = active.remove(player.getUniqueId());
        if (session == null) return;

        // Restore inventory first. The recovery file is deleted only after restoration
        // succeeds, leaving a durable fallback if anything goes wrong.
        try {
            snapshots.restore(player, session.inventorySnapshot);
            snapshots.delete(player.getUniqueId());
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to restore inventory for " + player.getName()
                            + ". Recovery file has been retained.", exception);
        }

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
        pendingJoinScreens.clear();
        for (UUID uuid : active.keySet().toArray(new UUID[0])) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) clear(player);
            else active.remove(uuid);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        final Player joiningPlayer = event.getPlayer();

        // Recovery is independent of whether development mode is currently enabled.
        // A crash/restart during an old overlay must never strand a cleared inventory.
        snapshots.restoreIfPresent(joiningPlayer);

        if (!plugin.isDevelopmentEnabled()) return;
        if (!plugin.getConfig().getBoolean("features.join-overlay.enabled", true)) return;
        if (shouldBypass(joiningPlayer)) return;

        final UUID uuid = joiningPlayer.getUniqueId();
        pendingJoinScreens.add(uuid);

        long baseDelay = Math.max(0L,
                plugin.getConfig().getLong("features.join-overlay.join-delay-ticks", 20L));

        List<Integer> retries = plugin.getConfig().getIntegerList(
                "features.join-overlay.join-retry-delays-ticks");
        if (retries.isEmpty()) {
            scheduleJoinAttempt(uuid, baseDelay);
            scheduleJoinAttempt(uuid, baseDelay + 20L);
            scheduleJoinAttempt(uuid, baseDelay + 60L);
        } else {
            for (Integer retry : retries) {
                long extra = retry == null ? 0L : Math.max(0, retry.intValue());
                scheduleJoinAttempt(uuid, baseDelay + extra);
            }
        }
    }

    private void scheduleJoinAttempt(final UUID uuid, long delay) {
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                if (!pendingJoinScreens.contains(uuid)) return;

                Player player = Bukkit.getPlayer(uuid);
                if (player == null || !player.isOnline()) {
                    pendingJoinScreens.remove(uuid);
                    return;
                }

                if (!plugin.isDevelopmentEnabled()
                        || !plugin.getConfig().getBoolean("features.join-overlay.enabled", true)
                        || shouldBypass(player)) {
                    pendingJoinScreens.remove(uuid);
                    return;
                }

                if (!isActive(player) && !showInternal(player, false)) return;

                // A join plugin can open/close inventories in the same few ticks. Only mark
                // the join screen as successfully delivered once ours remains the active view.
                Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                    @Override
                    public void run() {
                        Player verified = Bukkit.getPlayer(uuid);
                        ScreenSession session = active.get(uuid);
                        if (verified != null && verified.isOnline() && session != null
                                && verified.getOpenInventory() != null
                                && verified.getOpenInventory().getTopInventory().equals(session.inventory)) {
                            pendingJoinScreens.remove(uuid);
                        }
                    }
                }, Math.max(1L, plugin.getConfig().getLong(
                        "features.join-overlay.join-verification-ticks", 5L)));
            }
        }, delay);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        pendingJoinScreens.remove(event.getPlayer().getUniqueId());
        clear(event.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        ScreenSession session = active.get(player.getUniqueId());
        if (session == null || !session.inventory.equals(event.getInventory())) return;

        long guardMillis = Math.max(0L, plugin.getConfig().getLong(
                "features.join-overlay.join-close-guard-millis", 750L));
        boolean likelyJoinCollision = pendingJoinScreens.contains(player.getUniqueId())
                && (System.currentTimeMillis() - session.openedAtMillis) < guardMillis;

        // Minecraft sends the same close-container packet for Escape and the inventory
        // key. After the short join-collision guard, any close is treated as dismissal.
        if (!likelyJoinCollision) pendingJoinScreens.remove(player.getUniqueId());
        clear(player, false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Player player = event.getWhoClicked() instanceof Player ? (Player) event.getWhoClicked() : null;
        if (!blocked(player, "inventory-clicks")) return;
        event.setCancelled(true);

        if (player != null
                && event.getClickedInventory() != null
                && event.getClickedInventory().equals(player.getInventory())
                && event.getSlot() == configuredHelpSlot()) {
            triggerHelpItem(player);
        }
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
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (blocked(event.getPlayer(), "interactions")) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        if (blocked((Player) event.getEntity(), "interactions")) event.setCancelled(true);
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

    private void placeHelpItem(Player player) {
        ItemStack help = helpItems.create(player);
        if (help == null) return;
        int slot = configuredHelpSlot();
        player.getInventory().setItem(slot, help);
        player.updateInventory();
    }

    private int configuredHelpSlot() {
        int configured = plugin.getConfig().getInt("features.join-overlay.help-item.hotbar-slot", 9);
        return Math.max(1, Math.min(9, configured)) - 1;
    }

    private void triggerHelpItem(final Player player) {
        if (!plugin.getConfig().getBoolean("features.join-overlay.help-item.enabled", false)) return;

        final String command = plugin.getConfig().getString(
                "features.join-overlay.help-item.command", "").trim();
        if (command.isEmpty()) return;

        // Restore the authoritative player inventory before running anything. This keeps
        // the temporary help control completely outside the saved inventory lifecycle.
        pendingJoinScreens.remove(player.getUniqueId());
        clear(player);

        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                String resolved = textResolver.resolve(player, command);
                while (resolved.startsWith("/")) resolved = resolved.substring(1);

                String executor = plugin.getConfig().getString(
                        "features.join-overlay.help-item.executor", "player");
                if ("console".equalsIgnoreCase(executor)) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved);
                } else {
                    player.performCommand(resolved);
                }
            }
        });
    }

    private boolean shouldBypass(Player player) {
        return plugin.getConfig().getBoolean("features.join-overlay.use-bypass-permission", false)
                && player != null
                && player.hasPermission("voxeldevelopment.bypass");
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
        private final InventorySnapshot inventorySnapshot;
        private final long openedAtMillis;

        private ScreenSession(Inventory inventory,
                              PotionEffect previousBlindness,
                              InventorySnapshot inventorySnapshot,
                              long openedAtMillis) {
            this.inventory = inventory;
            this.previousBlindness = previousBlindness;
            this.inventorySnapshot = inventorySnapshot;
            this.openedAtMillis = openedAtMillis;
        }
    }
}
