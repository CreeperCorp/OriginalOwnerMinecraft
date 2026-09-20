package com.qyllz.originalowner;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class OriginalOwnerPlugin extends JavaPlugin implements Listener {
    private NamespacedKey ownerKey;
    private NamespacedKey originalLoreKey;
    private NamespacedKey originalLoreStoredKey;

    private static final String ORIGINAL_TEXT = "Original";
    private static final String STOLEN_TEXT = "Stolen";
    private static final String RETURN_PREFIX = "Return to ";
    private static final String LORE_SEPARATOR = "\u0000";

    @Override
    public void onEnable() {
        ownerKey = new NamespacedKey(this, "original_owner");
        originalLoreKey = new NamespacedKey(this, "original_lore");
        originalLoreStoredKey = new NamespacedKey(this, "original_lore_stored");

        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("OriginalOwner enabled for Paper 1.21.11.");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        ItemStack stack = event.getItem().getItemStack();
        if (!isEligible(stack)) return;

        if (!hasOwner(stack)) {
            assignOwner(stack, player.getUniqueId());
        }

        event.getItem().setItemStack(stack);
        // The pickup event fires before the item is fully inserted into the player's
        // inventory. Refresh on the next tick so armor/off-hand slots are handled too.
        scheduleRefresh(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        scheduleRefresh(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        scheduleRefresh(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        scheduleRefresh(event.getPlayer());
    }

    private void scheduleRefresh(Player player) {
        Bukkit.getScheduler().runTask(this, () -> refreshPlayerInventory(player));
    }

    private void refreshPlayerInventory(Player player) {
        PlayerInventory inventory = player.getInventory();
        boolean changed = false;

        ItemStack[] contents = inventory.getContents();
        for (ItemStack stack : contents) {
            if (refreshStack(stack, player.getUniqueId())) changed = true;
        }

        // PlayerInventory exposes armor and extra slots separately. Scan them explicitly
        // so helmets, chestplates, leggings, boots, and any future equipment slots are
        // never missed by a tooltip refresh.
        ItemStack[] armor = inventory.getArmorContents();
        for (ItemStack stack : armor) {
            if (refreshStack(stack, player.getUniqueId())) changed = true;
        }

        ItemStack[] extra = inventory.getExtraContents();
        for (ItemStack stack : extra) {
            if (refreshStack(stack, player.getUniqueId())) changed = true;
        }

        ItemStack offHand = inventory.getItemInOffHand();
        if (refreshStack(offHand, player.getUniqueId())) changed = true;

        // ItemStack metadata is a live mirror for player inventory stacks on Paper, so
        // no wholesale inventory replacement is normally necessary. The boolean is kept
        // to make the intent explicit and for compatibility with implementations where
        // an inventory refresh may be useful.
        if (changed) {
            inventory.setContents(contents);
            inventory.setArmorContents(armor);
            inventory.setExtraContents(extra);
            inventory.setItemInOffHand(offHand);
        }
    }

    private boolean refreshStack(ItemStack stack, UUID currentHolder) {
        if (!isEligible(stack) || !hasOwner(stack)) return false;
        String before = stack.toString();
        updateTooltip(stack, currentHolder);
        return !before.equals(stack.toString());
    }

    private boolean isEligible(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return false;

        String name = stack.getType().name();

        // Vanilla tool/weapon material names are intentionally checked by suffix so
        // every material tier is covered (including copper and future tiers).
        if (name.endsWith("_SWORD") || name.endsWith("_PICKAXE")
                || name.endsWith("_AXE") || name.endsWith("_SHOVEL")
                || name.endsWith("_HOE") || name.endsWith("_SPEAR")) {
            return true;
        }

        // Equipment whose material name does not follow the tiered naming pattern.
        return name.equals("SHIELD")
                || name.equals("ELYTRA")
                || name.equals("BOW")
                || name.equals("CROSSBOW")
                || name.equals("TRIDENT")
                || name.equals("MACE")
                || name.endsWith("_HELMET")
                || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS")
                || name.endsWith("_BOOTS");
    }

    private boolean hasOwner(ItemStack stack) {
        if (!stack.hasItemMeta()) return false;
        ItemMeta meta = stack.getItemMeta();
        return meta.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING);
    }

    private UUID getOwner(ItemStack stack) {
        if (!stack.hasItemMeta()) return null;
        String raw = stack.getItemMeta().getPersistentDataContainer()
                .get(ownerKey, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void assignOwner(ItemStack stack, UUID owner) {
        stack.editMeta(meta -> {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(ownerKey, PersistentDataType.STRING, owner.toString());
            storeOriginalLore(meta);
        });
    }

    private void storeOriginalLore(ItemMeta meta) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.has(originalLoreStoredKey, PersistentDataType.BYTE)) return;

        List<String> lore = meta.getLore();
        String encoded = lore == null ? "" : String.join(LORE_SEPARATOR, lore);
        pdc.set(originalLoreKey, PersistentDataType.STRING, encoded);
        pdc.set(originalLoreStoredKey, PersistentDataType.BYTE, (byte) 1);
    }

    private List<String> getOriginalLore(ItemMeta meta) {
        String encoded = meta.getPersistentDataContainer()
                .get(originalLoreKey, PersistentDataType.STRING);
        if (encoded == null || encoded.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(List.of(encoded.split(LORE_SEPARATOR, -1)));
    }

    private void updateTooltip(ItemStack stack, UUID currentHolder) {
        UUID owner = getOwner(stack);
        if (owner == null) return;

        stack.editMeta(meta -> {
            List<String> lore = getOriginalLore(meta);
            lore.add("");

            if (owner.equals(currentHolder)) {
                lore.add(ChatColor.GREEN + ORIGINAL_TEXT);
            } else {
                lore.add(ChatColor.RED + STOLEN_TEXT);
                lore.add(ChatColor.RED + RETURN_PREFIX + getPlayerName(owner));
            }

            meta.setLore(lore);
        });
    }

    private String getPlayerName(UUID uuid) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        String name = player.getName();
        return name != null ? name : uuid.toString();
    }
}
