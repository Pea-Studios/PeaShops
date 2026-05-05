package gg.peastudios.peashops.gui;

import gg.peastudios.peashops.PeaShops;
import gg.peastudios.peashops.shop.Shop;
import gg.peastudios.peashops.shop.ShopType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// /shop find — browse aggregated by material, click into per-material shop list.
// caches stock/price reads only for the lifetime of the open inventory; if this
// becomes hot we'll need a real cache.
public final class FindGUI implements Listener {

    private static final int PAGE_SIZE = 36;

    private final PeaShops plugin;
    private final Map<UUID, BrowseState> browseStates = new HashMap<>();

    public FindGUI(PeaShops plugin) {
        this.plugin = plugin;
    }

    public void openBrowse(Player p) {
        BrowseState st = browseStates.computeIfAbsent(p.getUniqueId(), k -> new BrowseState());
        renderBrowse(p, st);
    }

    private void renderBrowse(Player p, BrowseState st) {
        List<MaterialAgg> aggs = aggregateAll(p.getLocation());
        sortAggs(aggs, st.sort);

        int totalPages = Math.max(1, (aggs.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (st.page >= totalPages) st.page = totalPages - 1;
        int start = st.page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, aggs.size());

        Inventory inv = Bukkit.createInventory(new BrowseHolder(), 45, "shop find — browse");

        // top row: sort button + page nav
        inv.setItem(0, sortButton(st.sort));
        if (st.page > 0) inv.setItem(7, named(Material.ARROW, "§e< prev page"));
        if (st.page + 1 < totalPages) inv.setItem(8, named(Material.ARROW, "§enext page >"));
        // page indicator center
        ItemStack info = named(Material.PAPER, "§fpage " + (st.page + 1) + " / " + totalPages,
                "§7" + aggs.size() + " unique items across all shops");
        inv.setItem(4, info);

        for (int i = 0; i < end - start; i++) {
            MaterialAgg agg = aggs.get(start + i);
            inv.setItem(9 + i, agg.toIcon());
        }

        p.openInventory(inv);
    }

    public void openDetail(Player p, Material mat) {
        DetailState st = new DetailState();
        st.material = mat;
        renderDetail(p, st);
    }

    private void renderDetail(Player p, DetailState st) {
        List<ShopRow> rows = new ArrayList<>();
        for (UUID id : plugin.getShopManager().getSearchIndex().findByMaterial(st.material)) {
            Shop s = plugin.getShopManager().byId(id);
            if (s != null) rows.add(new ShopRow(s, p.getLocation()));
        }
        sortRows(rows, st.sort);

        int totalPages = Math.max(1, (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (st.page >= totalPages) st.page = totalPages - 1;
        int start = st.page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, rows.size());

        boolean tpAllowed = plugin.getConfig().getBoolean("shop-find.allow-tp", false)
                && p.hasPermission("peashops.find.tp");

        Inventory inv = Bukkit.createInventory(new DetailHolder(st.material, st.sort, st.page, tpAllowed),
                45, "shops trading " + st.material.name().toLowerCase());

        inv.setItem(0, sortButton(st.sort));
        inv.setItem(4, named(Material.PAPER, "§f" + rows.size() + " shop(s)",
                "§7sort: §f" + st.sort.name().toLowerCase().replace('_', ' ')));
        inv.setItem(3, named(Material.BARRIER, "§c< back to browse"));
        if (st.page > 0) inv.setItem(7, named(Material.ARROW, "§e< prev page"));
        if (st.page + 1 < totalPages) inv.setItem(8, named(Material.ARROW, "§enext page >"));

        for (int i = 0; i < end - start; i++) {
            inv.setItem(9 + i, rows.get(start + i).toIcon(tpAllowed));
        }
        p.openInventory(inv);
    }

    // ---- aggregation ----

    private List<MaterialAgg> aggregateAll(Location origin) {
        Map<Material, MaterialAgg> map = new HashMap<>();
        for (Shop s : plugin.getShopManager().snapshot().values()) {
            Material mat = s.itemSpec().getType();
            MaterialAgg agg = map.computeIfAbsent(mat, MaterialAgg::new);
            agg.shopCount++;
            int stock = countStock(s);
            agg.totalStock += stock;
            double unit = effectiveUnitPriceForDisplay(s);
            if (unit > 0 && unit < agg.cheapestUnit) agg.cheapestUnit = unit;
            // most-stock: track running max
            if (stock > agg.mostStock) agg.mostStock = stock;
            // distance to nearest of this material
            double d = squaredDistance(origin, s.chest());
            if (d < agg.nearestSqDist) agg.nearestSqDist = d;
        }
        return new ArrayList<>(map.values());
    }

    private double effectiveUnitPriceForDisplay(Shop s) {
        double base = s.type() == ShopType.SELL ? s.sellPrice() : s.buyPrice();
        int stock = s.isAdmin() ? Integer.MAX_VALUE : countStock(s);
        return plugin.getTransactionExecutor().pricingEngine()
                .resolveUnitPriceFull(s, stock, base);
    }

    private int countStock(Shop shop) {
        if (shop.isAdmin()) return Integer.MAX_VALUE; // displayed as ∞
        Block chestBlock = shop.chest().getBlock();
        if (!(chestBlock.getState() instanceof InventoryHolder holder)) return 0;
        Inventory inv = holder.getInventory();
        int total = 0;
        ItemStack template = shop.itemSpec();
        for (ItemStack s : inv.getContents()) {
            if (s == null) continue;
            if (s.isSimilar(template)) total += s.getAmount();
        }
        return total;
    }

    private static void sortAggs(List<MaterialAgg> aggs, Sort sort) {
        switch (sort) {
            case MOST_STOCK -> aggs.sort(Comparator.comparingInt((MaterialAgg a) -> a.totalStock).reversed());
            case CHEAPEST   -> aggs.sort(Comparator.comparingDouble(a -> a.cheapestUnit));
            case NEAREST    -> aggs.sort(Comparator.comparingDouble(a -> a.nearestSqDist));
        }
    }

    private static void sortRows(List<ShopRow> rows, Sort sort) {
        switch (sort) {
            case MOST_STOCK -> rows.sort(Comparator.comparingInt((ShopRow r) -> r.stock).reversed());
            case CHEAPEST   -> rows.sort(Comparator.comparingDouble(r -> r.unitPrice));
            case NEAREST    -> rows.sort(Comparator.comparingDouble(r -> r.sqDist));
        }
    }

    private static double squaredDistance(Location a, Location b) {
        if (a.getWorld() == null || b.getWorld() == null || !a.getWorld().equals(b.getWorld())) return Double.MAX_VALUE;
        double dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    // ---- click router ----

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        if (holder instanceof BrowseHolder) {
            event.setCancelled(true);
            handleBrowseClick(event);
        } else if (holder instanceof DetailHolder dh) {
            event.setCancelled(true);
            handleDetailClick(event, dh);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof BrowseHolder
                || event.getView().getTopInventory().getHolder() instanceof DetailHolder) {
            event.setCancelled(true);
        }
    }

    private void handleBrowseClick(InventoryClickEvent event) {
        Player p = (Player) event.getWhoClicked();
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        BrowseState st = browseStates.computeIfAbsent(p.getUniqueId(), k -> new BrowseState());

        if (slot == 0) { // sort cycle
            st.sort = nextSort(st.sort);
            renderBrowse(p, st);
            return;
        }
        if (slot == 7) { // prev
            if (st.page > 0) { st.page--; renderBrowse(p, st); }
            return;
        }
        if (slot == 8) { // next
            st.page++;
            renderBrowse(p, st);
            return;
        }
        if (slot >= 9 && slot < 45) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null) return;
            // material is the item's type
            openDetail(p, clicked.getType());
        }
    }

    private void handleDetailClick(InventoryClickEvent event, DetailHolder dh) {
        Player p = (Player) event.getWhoClicked();
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();

        DetailState st = new DetailState();
        st.material = dh.material;
        st.sort = dh.sort;
        st.page = dh.page;

        if (slot == 0) {
            st.sort = nextSort(st.sort);
            renderDetail(p, st);
            return;
        }
        if (slot == 3) {
            openBrowse(p);
            return;
        }
        if (slot == 7) {
            if (st.page > 0) { st.page--; renderDetail(p, st); }
            return;
        }
        if (slot == 8) {
            st.page++;
            renderDetail(p, st);
            return;
        }
        if (slot >= 9 && slot < 45) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null) return;
            ItemMeta meta = clicked.getItemMeta();
            if (meta == null || !meta.hasLore()) return;
            // shop id is encoded in the last lore line as "§8id:<uuid>"
            UUID shopId = extractShopId(meta.getLore());
            if (shopId == null) return;
            Shop shop = plugin.getShopManager().byId(shopId);
            if (shop == null) {
                p.sendMessage("§cthat shop disappeared");
                return;
            }
            // left click → tp (if allowed). right click → close + show coords in chat
            if (event.getClick() == ClickType.LEFT && dh.tpAllowed) {
                p.closeInventory();
                Location target = shop.chest().clone().add(0.5, 1, 0.5);
                target.setYaw(p.getLocation().getYaw());
                target.setPitch(p.getLocation().getPitch());
                p.teleport(target);
                p.sendMessage("§ateleported to shop");
            } else {
                Location loc = shop.chest();
                p.sendMessage("§7shop @ §f" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ()
                        + " §8(" + loc.getWorld().getName() + ")");
            }
        }
    }

    // ---- helpers ----

    private static UUID extractShopId(List<String> lore) {
        for (String line : lore) {
            String stripped = line.replaceAll("§.", "").trim();
            if (stripped.startsWith("id:")) {
                try { return UUID.fromString(stripped.substring(3).trim()); }
                catch (IllegalArgumentException ignored) {}
            }
        }
        return null;
    }

    private static Sort nextSort(Sort current) {
        return switch (current) {
            case NEAREST    -> Sort.MOST_STOCK;
            case MOST_STOCK -> Sort.CHEAPEST;
            case CHEAPEST   -> Sort.NEAREST;
        };
    }

    private static ItemStack sortButton(Sort current) {
        Material m = switch (current) {
            case NEAREST -> Material.COMPASS;
            case MOST_STOCK -> Material.CHEST;
            case CHEAPEST -> Material.GOLD_NUGGET;
        };
        String name = "§e§lsort: " + current.name().toLowerCase().replace('_', ' ');
        return named(m, name, "§7click to cycle (nearest → most stock → cheapest)");
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

    private static String fmt(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
        return String.format("%.2f", d);
    }

    // ---- value types ----

    private enum Sort { NEAREST, MOST_STOCK, CHEAPEST }

    private static final class BrowseState {
        Sort sort = Sort.NEAREST;
        int page = 0;
    }

    private static final class DetailState {
        Material material;
        Sort sort = Sort.NEAREST;
        int page = 0;
    }

    private static final class MaterialAgg {
        final Material material;
        int shopCount = 0;
        int totalStock = 0;
        int mostStock = 0;
        double cheapestUnit = Double.MAX_VALUE;
        double nearestSqDist = Double.MAX_VALUE;

        MaterialAgg(Material material) { this.material = material; }

        ItemStack toIcon() {
            ItemStack icon = new ItemStack(material, Math.max(1, Math.min(64, shopCount)));
            ItemMeta meta = icon.getItemMeta();
            meta.setDisplayName("§f" + material.name().toLowerCase().replace('_', ' '));
            List<String> lore = new ArrayList<>();
            lore.add("§7shops: §f" + shopCount);
            lore.add("§7total stock: §f" + (totalStock == Integer.MAX_VALUE ? "∞" : totalStock));
            lore.add("§7cheapest /unit: §f$" + (cheapestUnit == Double.MAX_VALUE ? "?" : fmt(cheapestUnit)));
            lore.add("§8click for details");
            meta.setLore(lore);
            icon.setItemMeta(meta);
            return icon;
        }
    }

    private final class ShopRow {
        final Shop shop;
        final int stock;
        final double unitPrice;
        final double sqDist;

        ShopRow(Shop shop, Location origin) {
            this.shop = shop;
            this.stock = countStock(shop);
            double base = shop.type() == ShopType.SELL ? shop.sellPrice() : shop.buyPrice();
            int dynStock = shop.isAdmin() ? Integer.MAX_VALUE : stock;
            this.unitPrice = plugin.getTransactionExecutor().pricingEngine()
                    .resolveUnitPriceFull(shop, dynStock, base);
            this.sqDist = squaredDistance(origin, shop.chest());
        }

        ItemStack toIcon(boolean tpAllowed) {
            // use the shop's actual item as the icon
            ItemStack icon = shop.itemSpec().clone();
            icon.setAmount(1);
            ItemMeta meta = icon.getItemMeta();
            String typeColor = switch (shop.type()) {
                case BUY -> "§a"; case SELL -> "§e"; case ADMIN -> "§c"; default -> "§7";
            };
            meta.setDisplayName(typeColor + shop.type() + " §7— §f" + shop.itemSpec().getType().name().toLowerCase().replace('_', ' '));
            List<String> lore = new ArrayList<>();
            String ownerName;
            if (shop.isAdmin()) ownerName = "[server]";
            else {
                OfflinePlayer op = Bukkit.getOfflinePlayer(shop.owner());
                ownerName = op.getName() != null ? op.getName() : shop.owner().toString().substring(0, 8);
            }
            lore.add("§7owner: §f" + ownerName);
            lore.add("§7stock: §f" + (shop.isAdmin() ? "∞" : stock));
            lore.add("§7price/unit: §f$" + fmt(unitPrice));
            if (shop.isOnSale()) lore.add("§6§l★ ON SALE");
            if (shop.dynamicPricing()) lore.add("§b§o(dynamic)");
            Location loc = shop.chest();
            lore.add("§7at §f" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ());
            if (sqDist < Double.MAX_VALUE) {
                lore.add("§8distance: " + (int) Math.sqrt(sqDist) + "m");
            }
            lore.add("");
            if (tpAllowed) lore.add("§7left-click to teleport, right for chat coords");
            else lore.add("§7right-click for chat coords");
            // hidden id line for click handler
            lore.add("§8id:" + shop.id());
            meta.setLore(lore);
            icon.setItemMeta(meta);
            return icon;
        }
    }

    // ---- holders for inventory recognition ----

    private static final class BrowseHolder implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }

    private static final class DetailHolder implements InventoryHolder {
        final Material material;
        final Sort sort;
        final int page;
        final boolean tpAllowed;
        DetailHolder(Material m, Sort s, int p, boolean tp) {
            this.material = m; this.sort = s; this.page = p; this.tpAllowed = tp;
        }
        @Override public Inventory getInventory() { return null; }
    }
}
