package gg.peastudios.peashops.gui;

import gg.peastudios.peashops.PeaShops;
import gg.peastudios.peashops.shop.Shop;
import gg.peastudios.peashops.shop.ShopSignFormat;
import gg.peastudios.peashops.shop.ShopType;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// gui-based shop creation: type pick → item pick → anvil price input.
// typed signs (line 1 = [BUY]/[SELL]/[ADMIN]) skip this — see SignCreationListener.
// placeholder paper is PDC-tagged so we can find/clean any copy vanilla
// drops into the player inv on anvil close.
public final class CreationFlow implements Listener {

    private final PeaShops plugin;
    private final NamespacedKey placeholderKey;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public CreationFlow(PeaShops plugin) {
        this.plugin = plugin;
        this.placeholderKey = new NamespacedKey(plugin, "creation_placeholder");
    }

    public void start(Player player, Block sign, Block chest) {
        Session s = new Session(sign, chest);
        sessions.put(player.getUniqueId(), s);
        // next tick — can't openInventory mid-SignChangeEvent
        Bukkit.getScheduler().runTask(plugin, () -> openTypePicker(player, s));
    }

    // ---- phase openers ----

    private void openTypePicker(Player player, Session s) {
        Inventory inv = Bukkit.createInventory(null, InventoryType.HOPPER, "shop type");
        inv.setItem(0, named(Material.GOLD_INGOT, "§a§lBUY",
                "§7players buy from your chest", "§7you stock the items"));
        if (player.hasPermission("peashops.admin")) {
            inv.setItem(2, named(Material.NETHER_STAR, "§c§lADMIN",
                    "§7infinite stock + payout", "§7admin only"));
        }
        inv.setItem(4, named(Material.IRON_INGOT, "§e§lSELL",
                "§7chest pays players for their items", "§7you stock the money"));
        s.phase = Phase.TYPE_PICK;
        markTransitioning(s);
        player.openInventory(inv);
    }

    private void openItemPicker(Player player, Session s) {
        Inventory inv = Bukkit.createInventory(null, InventoryType.HOPPER, "click item in your inv");
        ItemStack hint = named(Material.PAPER, "§ehow to pick",
                "§7click any item in YOUR inventory below.",
                "§7it becomes the item this shop trades.",
                "§7press Esc to cancel.");
        for (int i = 0; i < 5; i++) inv.setItem(i, hint);
        s.phase = Phase.ITEM_PICK;
        markTransitioning(s);
        player.openInventory(inv);
    }

    private void openPricePrompt(Player player, Session s) {
        s.phase = Phase.PRICE_INPUT;
        // openAnvil fires onClose on the prior gui — flag prevents the session
        // from tearing down before the anvil's actually open
        markTransitioning(s);
        InventoryView view = player.openAnvil(player.getLocation(), true);
        if (view == null) {
            player.sendMessage("§ccouldn't open anvil — start over");
            sessions.remove(player.getUniqueId());
            return;
        }
        AnvilInventory anvil = (AnvilInventory) view.getTopInventory();
        anvil.setItem(0, makePlaceholderPaper());
        anvil.setRepairCost(0);
        player.sendMessage("§ein the anvil: type qty + bundle price (e.g. §f32 5§e), then click the result on the right.");
    }

    // ---- click router ----

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        HumanEntity who = event.getWhoClicked();
        Session s = sessions.get(who.getUniqueId());
        if (s == null) return;
        event.setCancelled(true);

        Inventory clicked = event.getClickedInventory();
        if (clicked == null) return;
        InventoryView view = event.getView();
        Player p = (Player) who;

        switch (s.phase) {
            case TYPE_PICK -> {
                if (clicked != view.getTopInventory()) return;
                int slot = event.getRawSlot();
                ShopType picked = null;
                if (slot == 0) picked = ShopType.BUY;
                else if (slot == 2 && p.hasPermission("peashops.admin")) picked = ShopType.ADMIN;
                else if (slot == 4) picked = ShopType.SELL;
                if (picked == null) return;
                s.type = picked;
                Bukkit.getScheduler().runTask(plugin, () -> openItemPicker(p, s));
            }
            case ITEM_PICK -> {
                // bottom inv = player's own inv
                if (clicked != view.getBottomInventory()) return;
                ItemStack picked = event.getCurrentItem();
                if (picked == null || picked.getType().isAir()) return;
                ItemStack template = picked.clone();
                template.setAmount(1);
                s.item = template;
                Bukkit.getScheduler().runTask(plugin, () -> openPricePrompt(p, s));
            }
            case PRICE_INPUT -> {
                // anvil result slot is rawSlot 2.
                if (event.getRawSlot() != 2) return;
                if (!(view.getTopInventory() instanceof AnvilInventory ai)) return;
                String text = ai.getRenameText();
                if (text == null || text.isBlank()) {
                    p.sendMessage("§ctype qty + bundle price first (e.g. '32 5')");
                    return;
                }
                if (tryFinalize(p, s, text)) {
                    sessions.remove(p.getUniqueId());
                    // wipe before close so vanilla doesn't return placeholder/result
                    ai.setItem(0, null);
                    ai.setItem(1, null);
                    ai.setItem(2, null);
                    Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory());
                    scheduleSweep(p);
                }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (sessions.containsKey(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // populate slot 2; otherwise vanilla treats "no change" and clears the result
    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        HumanEntity who = event.getView().getPlayer();
        Session s = sessions.get(who.getUniqueId());
        if (s == null || s.phase != Phase.PRICE_INPUT) return;
        AnvilInventory anvil = event.getInventory();
        ItemStack input = anvil.getItem(0);
        if (input == null) return;
        String text = anvil.getRenameText();
        ItemStack result = input.clone();
        ItemMeta meta = result.getItemMeta();
        if (text == null || text.isBlank()) {
            meta.setDisplayName("§7(type qty + price)");
        } else {
            meta.setDisplayName("§a" + text);
        }
        result.setItemMeta(meta);
        event.setResult(result);
        anvil.setRepairCost(0);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Session s = sessions.get(id);
        if (s == null || s.transitioning) return;

        // wipe anvil + sweep next tick (vanilla returns input slots on close)
        if (s.phase == Phase.PRICE_INPUT && event.getInventory() instanceof AnvilInventory anvil) {
            anvil.setItem(0, null);
            anvil.setItem(1, null);
            scheduleSweep((Player) event.getPlayer());
        }

        sessions.remove(id);
        event.getPlayer().sendMessage("§7shop creation cancelled");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
        scheduleSweep(event.getPlayer());
    }

    // ---- finalize ----

    private boolean tryFinalize(Player p, Session s, String text) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length != 2) {
            p.sendMessage("§cbad input — type 'qty price' like '32 5'");
            return false;
        }
        int qty;
        double price;
        try {
            qty = Integer.parseInt(parts[0]);
            price = Double.parseDouble(parts[1]);
        } catch (NumberFormatException e) {
            p.sendMessage("§cbad input — type 'qty price' like '32 5'");
            return false;
        }
        if (qty <= 0 || qty > 64 * 36) {
            p.sendMessage("§cqty must be 1-2304");
            return false;
        }
        if (price < 0 || !Double.isFinite(price)) {
            p.sendMessage("§cprice must be non-negative");
            return false;
        }

        Block sign = s.sign;
        Block chest = s.chest;
        if (!(sign.getState() instanceof Sign signState)) {
            p.sendMessage("§csign vanished — start over");
            return false;
        }
        if (plugin.getShopManager().byLocation(chest.getLocation()) != null) {
            p.sendMessage("§cthat chest became a shop while you were typing — start over");
            return false;
        }

        UUID shopId = UUID.randomUUID();
        UUID ownerId = s.type == ShopType.ADMIN ? new UUID(0, 0) : p.getUniqueId();
        // store per-unit internally; bundle math is at the parse boundary.
        double pricePerUnit = price / qty;
        double buy  = (s.type == ShopType.BUY  || s.type == ShopType.ADMIN) ? pricePerUnit : 0;
        double sell = (s.type == ShopType.SELL || s.type == ShopType.ADMIN) ? pricePerUnit : 0;

        ItemStack template = s.item.clone();
        template.setAmount(1);

        Shop shop = new Shop(shopId, ownerId, chest.getLocation(), s.type, template, buy, sell);
        plugin.getShopManager().markBlock(chest, shopId);
        plugin.getShopManager().register(shop);
        plugin.getShopStorage().save(shop);

        String[] decorated = ShopSignFormat.decorate(s.type, qty, price,
                template.getType().name().toLowerCase().replace('_', ' '), p.getName());
        for (int i = 0; i < 4; i++) signState.setLine(i, decorated[i]);
        signState.update();

        p.sendMessage("§ashop created: §f" + s.type + " §7" + qty + " §a" + template.getType()
                + " §7for §f$" + price);
        return true;
    }

    // ---- helpers ----

    /** transitions briefly suppress onClose so close+open between phases
     *  doesn't auto-cancel the session. flag clears on the next tick. */
    private void markTransitioning(Session s) {
        s.transitioning = true;
        Bukkit.getScheduler().runTask(plugin, () -> s.transitioning = false);
    }

    /** placeholder paper carries a PDC byte we can scan for to clean up
     *  any copy that vanilla sends back to the player. */
    private ItemStack makePlaceholderPaper() {
        ItemStack p = new ItemStack(Material.PAPER);
        ItemMeta meta = p.getItemMeta();
        // empty displayName keeps the anvil rename field blank.
        meta.setDisplayName("");
        meta.setLore(List.of(
                "§7type qty + bundle price",
                "§7example: §f32 5",
                "§7click the result item on the right"));
        meta.getPersistentDataContainer().set(placeholderKey, PersistentDataType.BYTE, (byte) 1);
        p.setItemMeta(meta);
        return p;
    }

    /** next-tick sweep: remove any of our marker papers from the player's inv.
     *  catches the case where vanilla returned the placeholder before our
     *  setItem(null) took effect. */
    private void scheduleSweep(Player p) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            ItemStack[] contents = p.getInventory().getContents();
            for (int i = 0; i < contents.length; i++) {
                ItemStack stack = contents[i];
                if (stack == null) continue;
                ItemMeta m = stack.getItemMeta();
                if (m != null && m.getPersistentDataContainer().has(placeholderKey, PersistentDataType.BYTE)) {
                    p.getInventory().setItem(i, null);
                }
            }
            // also clear the cursor if it's holding our placeholder
            ItemStack cursor = p.getItemOnCursor();
            if (cursor != null && cursor.getType() == Material.PAPER) {
                ItemMeta m = cursor.getItemMeta();
                if (m != null && m.getPersistentDataContainer().has(placeholderKey, PersistentDataType.BYTE)) {
                    p.setItemOnCursor(null);
                }
            }
        });
    }

    private static ItemStack named(Material mat, String name, String... lore) {
        ItemStack s = new ItemStack(mat);
        ItemMeta meta = s.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) meta.setLore(Arrays.asList(lore));
        s.setItemMeta(meta);
        return s;
    }

    // ---- session ----

    private enum Phase { TYPE_PICK, ITEM_PICK, PRICE_INPUT }

    private static final class Session {
        final Block sign;
        final Block chest;
        Phase phase = Phase.TYPE_PICK;
        ShopType type;
        ItemStack item;
        boolean transitioning = false;

        Session(Block sign, Block chest) {
            this.sign = sign;
            this.chest = chest;
        }
    }
}
