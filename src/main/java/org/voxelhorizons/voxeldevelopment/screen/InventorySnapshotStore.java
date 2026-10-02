package org.voxelhorizons.voxeldevelopment.screen;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.voxelhorizons.voxeldevelopment.VoxelDevelopmentPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Crash-safe, authoritative inventory snapshots for development overlay sessions.
 *
 * The snapshot is written to disk BEFORE the live inventory is cleared. Restoration
 * always clears the live inventory first, then applies the snapshot, then deletes the
 * recovery file. That ordering makes repeated restoration idempotent and prevents
 * duplicate stacks if a disconnect/restart happens mid-session.
 */
public final class InventorySnapshotStore {

    private final VoxelDevelopmentPlugin plugin;
    private final File directory;

    public InventorySnapshotStore(VoxelDevelopmentPlugin plugin) {
        this.plugin = plugin;
        this.directory = new File(plugin.getDataFolder(), "inventory-recovery");
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("Unable to create inventory recovery directory " + directory);
        }
    }

    public InventorySnapshot capture(Player player) throws IOException {
        PlayerInventory inventory = player.getInventory();
        InventorySnapshot snapshot = new InventorySnapshot(
                cloneItems(inventory.getContents()),
                cloneItem(player.getItemOnCursor()),
                inventory.getHeldItemSlot());

        writeAtomic(player.getUniqueId(), snapshot);
        return snapshot;
    }

    public InventorySnapshot load(UUID uuid) {
        File file = file(uuid);
        if (!file.isFile()) return null;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<?> raw = yaml.getList("contents");
        if (raw == null) {
            plugin.getLogger().warning("Inventory recovery file " + file.getName()
                    + " has no contents list; leaving it untouched for manual recovery.");
            return null;
        }

        ItemStack[] contents = new ItemStack[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            Object value = raw.get(i);
            if (value == null) {
                contents[i] = null;
            } else if (value instanceof ItemStack) {
                contents[i] = ((ItemStack) value).clone();
            } else {
                plugin.getLogger().warning("Inventory recovery file " + file.getName()
                        + " contains an invalid item at slot " + i + "; leaving it untouched.");
                return null;
            }
        }

        ItemStack cursor = yaml.getItemStack("cursor");
        int heldSlot = yaml.getInt("held-slot", 0);
        return new InventorySnapshot(contents, cloneItem(cursor), heldSlot);
    }

    public boolean hasSnapshot(UUID uuid) {
        return file(uuid).isFile();
    }

    public void restore(Player player, InventorySnapshot snapshot) {
        if (snapshot == null) return;

        PlayerInventory inventory = player.getInventory();

        // The saved snapshot is authoritative. Anything injected into the inventory
        // while the overlay is active is discarded rather than merged, preventing
        // accidental duplication or slot-conflict behaviour.
        inventory.clear();
        inventory.setArmorContents(new ItemStack[inventory.getArmorContents().length]);
        try {
            inventory.setExtraContents(new ItemStack[inventory.getExtraContents().length]);
        } catch (NoSuchMethodError ignored) {
            // Very old API fallback; getContents/setContents still covers normal slots.
        }

        ItemStack[] currentShape = inventory.getContents();
        ItemStack[] restored = new ItemStack[currentShape.length];
        int copy = Math.min(restored.length, snapshot.contents.length);
        for (int i = 0; i < copy; i++) restored[i] = cloneItem(snapshot.contents[i]);
        inventory.setContents(restored);

        player.setItemOnCursor(cloneItem(snapshot.cursor));
        int held = Math.max(0, Math.min(8, snapshot.heldSlot));
        inventory.setHeldItemSlot(held);
        player.updateInventory();
    }

    public void delete(UUID uuid) {
        File file = file(uuid);
        if (file.exists() && !file.delete()) {
            plugin.getLogger().warning("Could not delete inventory recovery file " + file.getAbsolutePath());
        }
    }

    public void restoreIfPresent(Player player) {
        InventorySnapshot snapshot = load(player.getUniqueId());
        if (snapshot == null) return;

        plugin.getLogger().warning("Recovering saved inventory for " + player.getName()
                + " from an interrupted development overlay session.");
        restore(player, snapshot);
        delete(player.getUniqueId());
    }

    public void clearLiveInventory(Player player) {
        PlayerInventory inventory = player.getInventory();
        inventory.clear();
        inventory.setArmorContents(new ItemStack[inventory.getArmorContents().length]);
        try {
            inventory.setExtraContents(new ItemStack[inventory.getExtraContents().length]);
        } catch (NoSuchMethodError ignored) {
            // See restore().
        }
        player.setItemOnCursor(null);
        player.updateInventory();
    }

    private void writeAtomic(UUID uuid, InventorySnapshot snapshot) throws IOException {
        File target = file(uuid);
        File temp = new File(directory, uuid.toString() + ".yml.tmp");

        YamlConfiguration yaml = new YamlConfiguration();
        List<ItemStack> items = new ArrayList<ItemStack>(snapshot.contents.length);
        for (ItemStack item : snapshot.contents) items.add(cloneItem(item));
        yaml.set("contents", items);
        yaml.set("cursor", cloneItem(snapshot.cursor));
        yaml.set("held-slot", snapshot.heldSlot);
        yaml.save(temp);

        try {
            Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE,
                    "Unable to atomically save inventory recovery snapshot for " + uuid, exception);
            throw exception;
        }
    }

    private File file(UUID uuid) {
        return new File(directory, uuid.toString() + ".yml");
    }

    private static ItemStack[] cloneItems(ItemStack[] source) {
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) copy[i] = cloneItem(source[i]);
        return copy;
    }

    private static ItemStack cloneItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return null;
        return item.clone();
    }

    public static final class InventorySnapshot {
        private final ItemStack[] contents;
        private final ItemStack cursor;
        private final int heldSlot;

        private InventorySnapshot(ItemStack[] contents, ItemStack cursor, int heldSlot) {
            this.contents = contents;
            this.cursor = cursor;
            this.heldSlot = heldSlot;
        }
    }
}
