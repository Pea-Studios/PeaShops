package gg.peastudios.peashops.command;

import gg.peastudios.peashops.PeaShops;
import gg.peastudios.peashops.shop.Shop;
import gg.peastudios.peashops.shop.ShopPermission;
import gg.peastudios.peashops.shop.ShopType;
import gg.peastudios.peashops.transaction.TransactionResult;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

// /shop entry point. routes the few subcommands 1.0 supports.
// /shop find / /shop sale / /shop trust ship in subsequent 1.x.
public final class ShopCommand implements CommandExecutor, TabCompleter {

    private final PeaShops plugin;

    public ShopCommand(PeaShops plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§aPeaShops §7v" + plugin.getDescription().getVersion()
                    + "  §8| §7" + plugin.getShopManager().total() + " shops loaded");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload":
                if (!sender.hasPermission("peashops.admin")) {
                    plugin.getMessages().send(sender, "no-permission");
                    return true;
                }
                plugin.reloadConfig();
                plugin.getMessages().reload();
                sender.sendMessage("§areloaded peashops config + messages");
                return true;

            case "find":
                return handleFind(sender, args);

            case "sale":
                return handleSale(sender, args);

            case "tier":
                return handleTier(sender, args);

            case "trust":
                return handleTrust(sender, args, true);

            case "untrust":
                return handleTrust(sender, args, false);

            case "trustlist":
                return handleTrustList(sender);

            case "dynamic":
                return handleDynamic(sender, args);

            case "buy":
                return handleBulkTrade(sender, args, true);

            case "sell":
                return handleBulkTrade(sender, args, false);

            case "info":
                sender.sendMessage("§7shift+right-click any shop sign to open the info gui");
                return true;

            case "debugowner":
                return handleDebugOwner(sender, args);

            default:
                sender.sendMessage("§cunknown subcommand: " + args[0]);
                return true;
        }
    }

    /**
     * /shop debugowner <name>
     * sets the owner uuid of the shop the player is currently looking at to the
     * uuid resolved from <name>. lets a single tester flip ownership and trade
     * with their own shops without a second account.
     *
     * gated behind peashops.admin.
     */
    private boolean handleDebugOwner(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cplayer-only");
            return true;
        }
        if (!p.hasPermission("peashops.admin")) {
            plugin.getMessages().send(sender, "no-permission");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§cusage: /shop debugowner <playerName>");
            return true;
        }

        Block target = p.getTargetBlockExact(6);
        if (target == null) {
            sender.sendMessage("§clook at a shop sign or chest within 6 blocks");
            return true;
        }

        Shop shop = plugin.getShopManager().fromBlock(target);
        if (shop == null && target.getState() instanceof Sign) {
            Block chest = chestForSign(target);
            if (chest != null) shop = plugin.getShopManager().fromBlock(chest);
        }
        if (shop == null) {
            sender.sendMessage("§cthat block isn't a shop");
            return true;
        }

        String name = args[1];
        // Bukkit.getOfflinePlayer(name) returns a UUID for any name —
        // online-mode does a mojang lookup, offline-mode hashes the name.
        // either way we get a consistent UUID we can use for tests.
        @SuppressWarnings("deprecation")
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        UUID newOwner = offline.getUniqueId();

        UUID oldOwner = shop.owner();
        shop.setOwner(newOwner);
        plugin.getShopStorage().save(shop);
        plugin.getSignRefresher().refresh(shop);

        sender.sendMessage("§ashop " + shop.id() + " owner: §7" + oldOwner + " §a→ §f" + name + " §7(" + newOwner + ")");
        return true;
    }

    /**
     * /shop find <item>
     * lists shops trading the given item, sorted by distance from the player.
     * caps at 10 results to keep chat readable.
     */
    private boolean handleFind(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cplayer-only");
            return true;
        }
        if (args.length < 2) {
            // no arg → open the browse gui
            plugin.getFindGUI().openBrowse(p);
            return true;
        }
        String itemName = args[1].trim().toUpperCase().replace(' ', '_');
        Material mat = Material.matchMaterial(itemName);
        if (mat == null || mat.isAir()) {
            sender.sendMessage("§cunknown item: " + args[1]);
            return true;
        }

        List<UUID> ids = plugin.getShopManager().getSearchIndex().findByMaterial(mat);
        if (ids.isEmpty()) {
            sender.sendMessage("§7no shops trading " + mat.name().toLowerCase());
            return true;
        }

        // resolve to live shops + sort by squared distance from player
        Location origin = p.getLocation();
        List<Shop> shops = new ArrayList<>();
        for (UUID id : ids) {
            Shop s = plugin.getShopManager().byId(id);
            if (s != null) shops.add(s);
        }
        shops.sort((a, b) -> Double.compare(squaredDistance(origin, a.chest()), squaredDistance(origin, b.chest())));

        int limit = Math.min(10, shops.size());
        sender.sendMessage("§ashops trading §f" + mat.name().toLowerCase() + "§a: §7(" + shops.size() + " total, top " + limit + " nearest)");
        for (int i = 0; i < limit; i++) {
            Shop s = shops.get(i);
            Location loc = s.chest();
            String dist = sameWorld(origin, loc) ? String.format("%.0fm", Math.sqrt(squaredDistance(origin, loc))) : "(other world)";
            String type = s.isOnSale() ? "§6" + s.type() + " ON SALE" : "§7" + s.type();
            sender.sendMessage(" §8• " + type + " §7at §f" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ()
                    + " §8(" + loc.getWorld().getName() + ", " + dist + ")");
        }
        return true;
    }

    private static double squaredDistance(Location a, Location b) {
        if (!sameWorld(a, b)) return Double.MAX_VALUE;
        double dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean sameWorld(Location a, Location b) {
        return a.getWorld() != null && b.getWorld() != null && a.getWorld().equals(b.getWorld());
    }

    /**
     * /shop sale <hours> <percentOff>   — start a sale on the shop you're looking at
     * /shop sale clear                  — end any active sale
     * percentOff is 1-99. internally stored as a multiplier (1 - pct/100).
     */
    private boolean handleSale(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cplayer-only");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§cusage: /shop sale <hours> <%off>  •  /shop sale clear");
            return true;
        }
        Block target = p.getTargetBlockExact(6);
        if (target == null) {
            sender.sendMessage("§clook at a shop sign or chest within 6 blocks");
            return true;
        }
        Shop shop = plugin.getShopManager().fromBlock(target);
        if (shop == null && target.getState() instanceof Sign) {
            Block chest = chestForSign(target);
            if (chest != null) shop = plugin.getShopManager().fromBlock(chest);
        }
        if (shop == null) {
            sender.sendMessage("§cthat block isn't a shop");
            return true;
        }
        if (!shop.owner().equals(p.getUniqueId()) && !p.hasPermission("peashops.admin")) {
            sender.sendMessage("§cnot your shop");
            return true;
        }

        if (args[1].equalsIgnoreCase("clear")) {
            shop.clearSale();
            plugin.getShopStorage().save(shop);
            plugin.getSignRefresher().refresh(shop);
            sender.sendMessage("§7sale cleared");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("§cusage: /shop sale <hours> <%off>");
            return true;
        }
        double hours;
        int pct;
        try {
            hours = Double.parseDouble(args[1]);
            pct = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cnumbers please: /shop sale <hours> <%off>");
            return true;
        }
        if (hours <= 0 || hours > 24 * 30) {
            sender.sendMessage("§chours must be 0-720 (30 days max)");
            return true;
        }
        if (pct < 1 || pct > 99) {
            sender.sendMessage("§c%off must be 1-99");
            return true;
        }
        long endsAt = System.currentTimeMillis() + (long) (hours * 3600_000);
        double mult = 1.0 - (pct / 100.0);
        shop.setSale(endsAt, mult);
        plugin.getShopStorage().save(shop);
        plugin.getSignRefresher().refresh(shop);
        sender.sendMessage("§asale active: §f" + pct + "%§a off for §f" + hours + "h");
        return true;
    }

    /**
     * /shop tier <minQty> <pricePerUnit>     — owner-only, add/replace a tier
     * /shop tier clear                       — owner-only, wipe all tiers
     * tiers are looked up by largest-min-qty-not-exceeding-amount; per-unit
     * price is what the tier charges.
     */
    private boolean handleTier(CommandSender sender, String[] args) {
        Shop shop = lookedAtShop(sender);
        if (shop == null) return true;
        if (!isOwnerOrAdmin(sender, shop)) {
            sender.sendMessage("§cnot your shop");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§cusage: /shop tier <minQty> <pricePerUnit>  •  /shop tier clear");
            return true;
        }
        if (args[1].equalsIgnoreCase("clear")) {
            shop.clearTiers();
            plugin.getShopStorage().save(shop);
            plugin.getSignRefresher().refresh(shop);
            sender.sendMessage("§7tiers cleared");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("§cusage: /shop tier <minQty> <pricePerUnit>");
            return true;
        }
        int minQty;
        double price;
        try {
            minQty = Integer.parseInt(args[1]);
            price = Double.parseDouble(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cnumbers please");
            return true;
        }
        if (minQty < 1 || price < 0) {
            sender.sendMessage("§cminQty>=1, price>=0");
            return true;
        }
        shop.addTier(minQty, price);
        plugin.getShopStorage().save(shop);
        plugin.getSignRefresher().refresh(shop);
        sender.sendMessage("§atier set: §7buy " + minQty + "+ at §f$" + price + "/unit");
        return true;
    }

    /**
     * /shop trust <player> <perm>      — owner-only, grant a perm
     * /shop untrust <player> [<perm>]  — owner-only, revoke one or all perms
     * perm is one of: edit | restock | withdraw | delete | view_analytics
     */
    private boolean handleTrust(CommandSender sender, String[] args, boolean grant) {
        Shop shop = lookedAtShop(sender);
        if (shop == null) return true;
        if (!isOwnerOrAdmin(sender, shop)) {
            sender.sendMessage("§cnot your shop");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§cusage: /shop " + (grant ? "trust" : "untrust") + " <player>" + (grant ? " <perm>" : " [<perm>]"));
            return true;
        }
        @SuppressWarnings("deprecation")
        OfflinePlayer who = Bukkit.getOfflinePlayer(args[1]);

        if (!grant && args.length == 2) {
            // untrust without perm name -> revoke ALL
            for (ShopPermission p : ShopPermission.values()) shop.revoke(who.getUniqueId(), p);
            plugin.getShopStorage().save(shop);
            sender.sendMessage("§7" + args[1] + " no longer has any perms on this shop");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage("§cusage: /shop " + (grant ? "trust" : "untrust") + " <player> <perm>");
            return true;
        }
        ShopPermission perm;
        try { perm = ShopPermission.valueOf(args[2].toUpperCase()); }
        catch (IllegalArgumentException e) {
            sender.sendMessage("§cunknown perm — try: edit, restock, withdraw, delete, view_analytics");
            return true;
        }
        if (grant) shop.grant(who.getUniqueId(), perm);
        else shop.revoke(who.getUniqueId(), perm);
        plugin.getShopStorage().save(shop);
        sender.sendMessage("§a" + (grant ? "granted" : "revoked") + " §f" + perm + " §a" + (grant ? "to" : "from") + " §f" + args[1]);
        return true;
    }

    private boolean handleTrustList(CommandSender sender) {
        Shop shop = lookedAtShop(sender);
        if (shop == null) return true;
        var snap = shop.aclSnapshot();
        if (snap.isEmpty()) {
            sender.sendMessage("§7no trusted players on this shop");
            return true;
        }
        sender.sendMessage("§atrusted on this shop:");
        for (var e : snap.entrySet()) {
            String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
            if (name == null) name = e.getKey().toString().substring(0, 8);
            StringBuilder perms = new StringBuilder();
            for (var p : e.getValue().keySet()) {
                if (perms.length() > 0) perms.append(", ");
                perms.append(p.name().toLowerCase());
            }
            sender.sendMessage(" §8• §f" + name + " §7→ §f" + perms);
        }
        return true;
    }

    /**
     * /shop buy <qty>   /   /shop sell <qty>
     * trades the given qty against the shop you're looking at. unlocks
     * bulk-tier pricing — clicking the sign always trades the sign-qty,
     * which means a bulk discount tier never triggers from a click alone.
     * this command is the only way (for 1.0) to actually hit a tier.
     */
    private boolean handleBulkTrade(CommandSender sender, String[] args, boolean buy) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cplayer-only");
            return true;
        }
        Shop shop = lookedAtShop(sender);
        if (shop == null) return true;
        if (args.length < 2) {
            sender.sendMessage("§cusage: /shop " + (buy ? "buy" : "sell") + " <qty>");
            return true;
        }
        int qty;
        try { qty = Integer.parseInt(args[1]); }
        catch (NumberFormatException e) {
            sender.sendMessage("§cqty must be a number");
            return true;
        }
        if (qty <= 0 || qty > 64 * 36) {
            sender.sendMessage("§cqty must be 1-2304");
            return true;
        }

        // sanity check shop type vs requested direction
        if (buy && shop.type() != ShopType.BUY && shop.type() != ShopType.ADMIN && shop.type() != ShopType.TRADE) {
            sender.sendMessage("§cthis shop doesn't sell items");
            return true;
        }
        if (!buy && shop.type() != ShopType.SELL && shop.type() != ShopType.ADMIN && shop.type() != ShopType.TRADE) {
            sender.sendMessage("§cthis shop doesn't buy items");
            return true;
        }

        TransactionResult result = buy
                ? plugin.getTransactionExecutor().buy(p, shop, qty, null)
                : plugin.getTransactionExecutor().sell(p, shop, qty, null);
        String item = shop.itemSpec().getType().name().toLowerCase().replace('_', ' ');
        switch (result) {
            case OK:               p.sendMessage("§a" + (buy ? "bought " : "sold ") + qty + "x §f" + item); break;
            case SHOP_BUSY:        p.sendMessage("§eshop busy, try again"); break;
            case NOT_ENOUGH_FUNDS: p.sendMessage("§cyou can't afford that"); break;
            case NOT_ENOUGH_STOCK: p.sendMessage(buy ? "§cshop out of stock" : "§cyou don't have those items"); break;
            case NOT_ENOUGH_SPACE: p.sendMessage(buy ? "§cyour inventory is full" : "§cshop chest is full"); break;
            case VAULT_FAIL:       p.sendMessage("§ceconomy error, transaction rolled back"); break;
            case INVENTORY_FAIL:   p.sendMessage("§cinventory error, transaction rolled back"); break;
            default:               p.sendMessage("§ctransaction error: " + result);
        }
        return true;
    }

    private boolean handleDynamic(CommandSender sender, String[] args) {
        Shop shop = lookedAtShop(sender);
        if (shop == null) return true;
        if (!isOwnerOrAdmin(sender, shop)) {
            sender.sendMessage("§cnot your shop");
            return true;
        }
        boolean enable;
        if (args.length >= 2 && args[1].equalsIgnoreCase("on"))  enable = true;
        else if (args.length >= 2 && args[1].equalsIgnoreCase("off")) enable = false;
        else {
            sender.sendMessage("§cusage: /shop dynamic on|off  (currently " + (shop.dynamicPricing() ? "on" : "off") + ")");
            return true;
        }
        shop.setDynamicPricing(enable);
        plugin.getShopStorage().save(shop);
        plugin.getSignRefresher().refresh(shop);
        sender.sendMessage("§adynamic pricing " + (enable ? "§aenabled" : "§7disabled"));
        return true;
    }

    /** resolve the shop the player is looking at, or null + send error. */
    private Shop lookedAtShop(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cplayer-only");
            return null;
        }
        Block target = p.getTargetBlockExact(6);
        if (target == null) {
            sender.sendMessage("§clook at a shop sign or chest within 6 blocks");
            return null;
        }
        Shop shop = plugin.getShopManager().fromBlock(target);
        if (shop == null && target.getState() instanceof Sign) {
            Block chest = chestForSign(target);
            if (chest != null) shop = plugin.getShopManager().fromBlock(chest);
        }
        if (shop == null) {
            sender.sendMessage("§cthat block isn't a shop");
            return null;
        }
        return shop;
    }

    private static boolean isOwnerOrAdmin(CommandSender sender, Shop shop) {
        if (sender.hasPermission("peashops.admin")) return true;
        if (sender instanceof Player p) return shop.owner().equals(p.getUniqueId());
        return false;
    }

    private static Block chestForSign(Block sign) {
        BlockData data = sign.getBlockData();
        if (data instanceof WallSign wall) {
            Block attached = sign.getRelative(wall.getFacing().getOppositeFace());
            if (attached.getState() instanceof InventoryHolder) return attached;
        }
        Block below = sign.getRelative(BlockFace.DOWN);
        if (below.getState() instanceof InventoryHolder) return below;
        return null;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return Arrays.asList("find", "info", "sale", "tier", "trust", "untrust",
                    "trustlist", "dynamic", "buy", "sell", "reload", "debugowner");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("sale")) {
            return Arrays.asList("clear", "1", "6", "24");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("dynamic")) {
            return Arrays.asList("on", "off");
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("trust") || args[0].equalsIgnoreCase("untrust"))) {
            return Arrays.asList("edit", "restock", "withdraw", "delete", "view_analytics");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debugowner")) {
            return Arrays.asList("PeaBot", "TestBuyer", sender.getName());
        }
        return Collections.emptyList();
    }
}
