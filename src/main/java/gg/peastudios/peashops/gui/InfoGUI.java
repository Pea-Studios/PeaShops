package gg.peastudios.peashops.gui;

import gg.peastudios.peashops.PeaShops;
import gg.peastudios.peashops.shop.Shop;
import gg.peastudios.peashops.shop.ShopPermission;
import gg.peastudios.peashops.shop.ShopType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InfoGUI implements Listener {

    private final PeaShops plugin;

    // playerUuid -> shopId they're typing a description for
    private final Map<UUID, UUID> awaitingDescription = new ConcurrentHashMap<>();

    public InfoGUI(PeaShops plugin) {
        this.plugin = plugin;
    }

    public void open(Player viewer, Shop shop) {
        Inventory inv = Bukkit.createInventory(new InfoHolder(shop.id()), 27, "shop info");

        ItemStack pane = decorPane();
        for (int i = 0; i < 9; i++) inv.setItem(i, pane);

        inv.setItem(0, typeBadge(shop));
        inv.setItem(4, buildItemDisplay(shop));

        if (!shop.bulkTiers().isEmpty()) {
            inv.setItem(11, bulkTiersDisplay(shop));
        }

        inv.setItem(13, descriptionDisplay(shop));

        if (!shop.aclSnapshot().isEmpty()) {
            inv.setItem(15, trustedPlayersDisplay(shop));
        }

        // bottom row: borders + edit button if owner
        for (int i = 18; i < 27; i++) inv.setItem(i, pane);
        boolean canEdit = shop.owner().equals(viewer.getUniqueId()) || viewer.hasPermission("peashops.admin");
        if (canEdit) {
            inv.setItem(22, named(Material.WRITABLE_BOOK, "§e§ledit description",
                    "§7closes this view and prompts you in chat.",
                    "§7type the new description, or §fcancel§7 / §fclear§7."));
        }

        viewer.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof InfoHolder holder)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != top) return;
        if (event.getRawSlot() != 22) return;

        Player p = (Player) event.getWhoClicked();
        Shop shop = plugin.getShopManager().byId(holder.shopId);
        if (shop == null) return;
        if (!shop.owner().equals(p.getUniqueId()) && !p.hasPermission("peashops.admin")) return;

        p.closeInventory();
        awaitingDescription.put(p.getUniqueId(), shop.id());
        p.sendMessage("§etype the new description in chat (max ~120 chars), or §fcancel§e / §fclear§e.");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (awaitingDescription.remove(p.getUniqueId()) != null) {
                p.sendMessage("§7description prompt timed out.");
            }
        }, 20L * 60);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof InfoHolder) {
            event.setCancelled(true);
        }
    }

    // chat-prompt flow for description input. paper's modern chat event is
    // async; mutating shop state from here needs to bounce back to main.
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        UUID shopId = awaitingDescription.remove(event.getPlayer().getUniqueId());
        if (shopId == null) return;
        event.setCancelled(true); // don't broadcast the input as chat
        String raw = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> applyDescription(p, shopId, raw));
    }

    private void applyDescription(Player p, UUID shopId, String raw) {
        Shop shop = plugin.getShopManager().byId(shopId);
        if (shop == null) {
            p.sendMessage("§cshop is gone");
            return;
        }
        if (raw.equalsIgnoreCase("cancel")) {
            p.sendMessage("§7no change");
            return;
        }
        if (raw.equalsIgnoreCase("clear") || raw.isEmpty()) {
            shop.setDescription("");
            plugin.getShopStorage().save(shop);
            p.sendMessage("§adescription cleared");
            return;
        }
        // strip color codes from input — vanity description should be plain;
        // we control the colors when displaying.
        String cleaned = ChatColor.stripColor(raw);
        if (cleaned.length() > 120) cleaned = cleaned.substring(0, 120);
        shop.setDescription(cleaned);
        plugin.getShopStorage().save(shop);
        p.sendMessage("§adescription saved: §f" + cleaned);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        awaitingDescription.remove(event.getPlayer().getUniqueId());
    }

    // ---- gui content builders ----

    private ItemStack typeBadge(Shop shop) {
        Material mat;
        String name;
        switch (shop.type()) {
            case BUY   -> { mat = Material.GOLD_INGOT;  name = "§a§lBUY shop"; }
            case SELL  -> { mat = Material.IRON_INGOT;  name = "§e§lSELL shop"; }
            case ADMIN -> { mat = Material.NETHER_STAR; name = "§c§lADMIN shop"; }
            default    -> { mat = Material.PAPER;       name = "§7shop"; }
        }
        List<String> lore = new ArrayList<>();
        if (shop.type() != ShopType.ADMIN) {
            OfflinePlayer owner = Bukkit.getOfflinePlayer(shop.owner());
            String ownerName = owner.getName() != null ? owner.getName() : shop.owner().toString();
            lore.add("§7owner: §f" + ownerName);
        } else {
            lore.add("§7owner: §c[server]");
        }
        return named(mat, name, lore.toArray(new String[0]));
    }

    private ItemStack buildItemDisplay(Shop shop) {
        ItemStack display = shop.itemSpec().clone();
        display.setAmount(1);
        ItemMeta meta = display.getItemMeta();

        // qty per click + bundle price are derived from the sign at trade
        // time. for the info gui we don't read the sign — instead reverse
        // the price-per-unit math: unitPrice * <amount-on-sign> = bundle.
        // since we don't have the sign reference here, we show per-unit
        // info plus a hint to look at the sign for the bundle.
        double unit = shop.type() == ShopType.SELL ? shop.sellPrice() : shop.buyPrice();
        double effectiveUnit = shop.isOnSale() ? unit * shop.saleMult() : unit;

        List<String> lore = new ArrayList<>();
        lore.add("§7trades: §f" + shop.itemSpec().getType().name().toLowerCase().replace('_', ' '));

        if (shop.isAdmin()) {
            lore.add("§7stock: §a∞ §7(admin)");
        } else {
            int stock = countStock(shop);
            lore.add("§7stock: §f" + stock);
        }

        lore.add("§7price/unit: §f" + fmt(effectiveUnit));
        if (shop.dynamicPricing()) {
            lore.add("§b§o(dynamic pricing — varies with stock)");
        }
        if (shop.isOnSale()) {
            double saved = unit - effectiveUnit;
            int pct = (int) Math.round((1.0 - shop.saleMult()) * 100);
            lore.add("");
            lore.add("§6§l★ ON SALE — " + pct + "% OFF ★");
            lore.add("§7original: §m$" + fmt(unit) + "§r §7→ §a$" + fmt(effectiveUnit));
            lore.add("§7you save §a$" + fmt(saved) + " §7per unit");
            long secs = Math.max(0, (shop.saleEndsAt() - System.currentTimeMillis()) / 1000);
            lore.add("§7ends in §f" + formatDuration(secs));
        }
        meta.setLore(lore);
        display.setItemMeta(meta);
        return display;
    }

    private ItemStack bulkTiersDisplay(Shop shop) {
        Map<Integer, Double> tiers = shop.bulkTiers();
        // sort tiers by minQty for readable display
        List<Map.Entry<Integer, Double>> sorted = new ArrayList<>(tiers.entrySet());
        sorted.sort(Map.Entry.comparingByKey());
        List<String> lore = new ArrayList<>();
        lore.add("§7buy more, pay less per unit:");
        for (var e : sorted) {
            lore.add(" §8• §fbuy " + e.getKey() + "+ §7@ §f$" + fmt(e.getValue()) + "§7/unit");
        }
        return named(Material.GOLD_NUGGET, "§e§lbulk tiers", lore.toArray(new String[0]));
    }

    private ItemStack trustedPlayersDisplay(Shop shop) {
        List<String> lore = new ArrayList<>();
        lore.add("§7players the owner has granted perms to:");
        for (var e : shop.aclSnapshot().entrySet()) {
            String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
            if (name == null) name = e.getKey().toString().substring(0, 8);
            StringBuilder perms = new StringBuilder();
            for (ShopPermission p : e.getValue().keySet()) {
                if (perms.length() > 0) perms.append(", ");
                perms.append(p.name().toLowerCase());
            }
            lore.add(" §8• §f" + name + " §7→ §f" + perms);
        }
        return named(Material.PLAYER_HEAD, "§e§ltrusted players", lore.toArray(new String[0]));
    }

    private ItemStack descriptionDisplay(Shop shop) {
        Material mat = shop.description().isEmpty() ? Material.MAP : Material.FILLED_MAP;
        String name = shop.description().isEmpty() ? "§7§odescription: (none)" : "§e§ldescription";
        if (shop.description().isEmpty()) {
            return named(mat, name, "§8the owner hasn't set one");
        }
        // wrap long descriptions across lore lines (~30 char per line).
        List<String> lore = wrap("§f" + shop.description(), 30);
        return named(mat, name, lore.toArray(new String[0]));
    }

    private static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (cur.length() + word.length() + 1 > width && cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
                cur.append("§f");
            }
            if (cur.length() > 2) cur.append(' ');
            cur.append(word);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static int countStock(Shop shop) {
        Block chestBlock = shop.chest().getBlock();
        BlockState state = chestBlock.getState();
        if (!(state instanceof InventoryHolder holder)) return 0;
        Inventory inv = holder.getInventory();
        int total = 0;
        ItemStack template = shop.itemSpec();
        for (ItemStack s : inv.getContents()) {
            if (s == null) continue;
            if (s.isSimilar(template)) total += s.getAmount();
        }
        return total;
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.format("%.2f", d);
    }

    private static String formatDuration(long secs) {
        if (secs < 60) return secs + "s";
        long m = secs / 60;
        if (m < 60) return m + "m";
        long h = m / 60;
        if (h < 24) return h + "h" + (m % 60 == 0 ? "" : " " + (m % 60) + "m");
        return (h / 24) + "d" + (h % 24 == 0 ? "" : " " + (h % 24) + "h");
    }

    private static ItemStack decorPane() {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.setDisplayName(" ");
        pane.setItemMeta(meta);
        return pane;
    }

    private static ItemStack named(Material mat, String name, String... lore) {
        ItemStack s = new ItemStack(mat);
        ItemMeta meta = s.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) {
            List<String> list = new ArrayList<>();
            for (String l : lore) list.add(l);
            meta.setLore(list);
        }
        s.setItemMeta(meta);
        return s;
    }

    // ---- holder ----

    /** stamps the inventory so onClick can recognize "this is one of ours"
     *  AND recover which shop it belongs to. avoids fragile title matching. */
    private static final class InfoHolder implements InventoryHolder {
        final UUID shopId;
        InfoHolder(UUID shopId) { this.shopId = shopId; }
        @Override public Inventory getInventory() { return null; }
    }
}
