package gg.peastudios.peashops.transaction;

import gg.peastudios.peashops.PeaShops;
import gg.peastudios.peashops.economy.EconomyBridge;
import gg.peastudios.peashops.shop.Shop;
import gg.peastudios.peashops.shop.ShopType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

// runs the actual trade under a per-shop lock with snapshot+restore.
// either the trade fully commits or nothing moves — no partial state.
public final class TransactionExecutor {

    private final PeaShops plugin;
    private final PricingEngine pricing;
    // onDisable polls this so it can wait for trades to finish before tearing down
    private final AtomicInteger inFlight = new AtomicInteger();

    public TransactionExecutor(PeaShops plugin) {
        this.plugin = plugin;
        this.pricing = new PricingEngine(plugin);
    }

    public int inFlightCount() { return inFlight.get(); }
    public PricingEngine pricingEngine() { return pricing; }

    public TransactionResult buy(Player player, Shop shop, int amount, Double priceQuoted) {
        return runLocked(player, shop, () -> doBuy(player, shop, amount, priceQuoted));
    }

    public TransactionResult sell(Player player, Shop shop, int amount, Double priceQuoted) {
        return runLocked(player, shop, () -> doSell(player, shop, amount, priceQuoted));
    }

    private TransactionResult runLocked(Player player, Shop shop, TradeFn trade) {
        // unloaded-chunk container access is a known dupe class
        if (!shop.chest().getChunk().isLoaded()) {
            return TransactionResult.NO_SHOP;
        }

        long timeoutMs = plugin.getConfig().getLong("transaction.lock-timeout-ms", 250);
        ReentrantLock lock = plugin.getTransactionLock().acquireShop(shop.id(), timeoutMs);
        if (lock == null) return TransactionResult.SHOP_BUSY;

        inFlight.incrementAndGet();
        try {
            return trade.run();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "transaction crashed for shop " + shop.id(), t);
            return TransactionResult.INTERNAL_ERROR;
        } finally {
            plugin.getTransactionLock().release(lock);
            inFlight.decrementAndGet();
        }
    }

    // ---- buy ----

    private TransactionResult doBuy(Player player, Shop shop, int amount, Double priceQuoted) {
        if (shop.type() != ShopType.BUY && shop.type() != ShopType.TRADE && shop.type() != ShopType.ADMIN) {
            return TransactionResult.NO_SHOP;
        }

        // re-resolve the chest. don't trust the shop object — re-read the block
        // in case the chunk reloaded or someone replaced it between events.
        Block chestBlock = shop.chest().getBlock();
        BlockState state = chestBlock.getState();
        if (!(state instanceof InventoryHolder holder)) return TransactionResult.NO_SHOP;
        Inventory chestInv = holder.getInventory();

        EconomyBridge eco = plugin.getEconomyBridge();
        if (!eco.isReady()) return TransactionResult.VAULT_FAIL;

        double balanceSnapshot = eco.balance(player);

        // base -> tier -> dynamic -> sale (sale last so % reads as final discount)
        int currentStock = shop.isAdmin() ? Integer.MAX_VALUE : countMatching(chestInv, shop.itemSpec());
        double basePrice = shop.resolveTierPrice(amount, shop.buyPrice());
        double unitPrice = pricing.resolveUnitPrice(shop, currentStock, basePrice);
        if (shop.isOnSale()) unitPrice *= shop.saleMult();
        double total = unitPrice * amount;

        if (priceQuoted != null) {
            double maxDrift = plugin.getConfig().getDouble("transaction.max-price-drift", 0.05);
            double base = Math.max(priceQuoted, total);
            if (base > 0 && Math.abs(priceQuoted - total) / base > maxDrift) {
                return TransactionResult.PRICE_DRIFT;
            }
        }

        if (!eco.has(player, total)) return TransactionResult.NOT_ENOUGH_FUNDS;

        ItemStack want = shop.itemSpec();
        want.setAmount(amount);
        if (!shop.isAdmin() && !chestHasEnough(chestInv, shop.itemSpec(), amount)) {
            return TransactionResult.NOT_ENOUGH_STOCK;
        }

        // space check via simulated addItem
        ItemStack[] simInv = clone(player.getInventory().getContents());
        Inventory sim = Bukkit.createInventory(null, 36);
        sim.setContents(java.util.Arrays.copyOf(simInv, 36));
        Map<Integer, ItemStack> leftover = sim.addItem(want.clone());
        if (!leftover.isEmpty()) return TransactionResult.NOT_ENOUGH_SPACE;

        TransactionGuard guard = TransactionGuard.snapshot(player, chestInv, balanceSnapshot);
        try {
            if (!shop.isAdmin()) {
                Map<Integer, ItemStack> notRemoved = chestInv.removeItem(stackOf(shop.itemSpec(), amount));
                if (!notRemoved.isEmpty()) {
                    return TransactionResult.INVENTORY_FAIL;
                }
            }

            if (player.isDead()) {
                // died in the same tick as the click — drop, don't give
                player.getWorld().dropItemNaturally(player.getLocation(), want.clone());
            } else {
                Map<Integer, ItemStack> notAdded = player.getInventory().addItem(want.clone());
                if (!notAdded.isEmpty()) {
                    return TransactionResult.INVENTORY_FAIL;
                }
            }

            if (!eco.withdraw(player, total)) {
                return TransactionResult.VAULT_FAIL;
            }

            if (!shop.isAdmin()) {
                OfflinePlayer ownerOff = Bukkit.getOfflinePlayer(shop.owner());
                if (!eco.deposit(ownerOff, total)) {
                    eco.deposit(player, total);
                    return TransactionResult.VAULT_FAIL;
                }
            }

            guard.commit();
            plugin.getSignRefresher().refresh(shop);
            return TransactionResult.OK;
        } finally {
            if (!guard.committed()) {
                guard.restore();
                eco.restoreBalance(player, balanceSnapshot);
            }
        }
    }

    // ---- sell ----

    private TransactionResult doSell(Player player, Shop shop, int amount, Double priceQuoted) {
        if (shop.type() != ShopType.SELL && shop.type() != ShopType.TRADE && shop.type() != ShopType.ADMIN) {
            return TransactionResult.NO_SHOP;
        }

        Block chestBlock = shop.chest().getBlock();
        BlockState state = chestBlock.getState();
        if (!(state instanceof InventoryHolder holder)) return TransactionResult.NO_SHOP;
        Inventory chestInv = holder.getInventory();

        EconomyBridge eco = plugin.getEconomyBridge();
        if (!eco.isReady()) return TransactionResult.VAULT_FAIL;
        double balanceSnapshot = eco.balance(player);

        int currentStockSell = shop.isAdmin() ? Integer.MAX_VALUE : countMatching(chestInv, shop.itemSpec());
        double basePriceSell = shop.resolveTierPrice(amount, shop.sellPrice());
        double unitPrice = pricing.resolveUnitPrice(shop, currentStockSell, basePriceSell);
        if (shop.isOnSale()) unitPrice *= shop.saleMult();
        double total = unitPrice * amount;

        if (priceQuoted != null) {
            double maxDrift = plugin.getConfig().getDouble("transaction.max-price-drift", 0.05);
            double base = Math.max(priceQuoted, total);
            if (base > 0 && Math.abs(priceQuoted - total) / base > maxDrift) {
                return TransactionResult.PRICE_DRIFT;
            }
        }

        // player must actually have the items to sell
        if (!playerHasEnough(player, shop.itemSpec(), amount)) {
            return TransactionResult.NOT_ENOUGH_STOCK;
        }

        // chest must have space (unless admin)
        if (!shop.isAdmin()) {
            ItemStack[] simChest = clone(chestInv.getContents());
            Inventory sim = Bukkit.createInventory(null, simChest.length);
            sim.setContents(simChest);
            Map<Integer, ItemStack> leftover = sim.addItem(stackOf(shop.itemSpec(), amount));
            if (!leftover.isEmpty()) return TransactionResult.NOT_ENOUGH_SPACE;
        }

        // shop must have money to pay (unless admin shop, which has infinite)
        if (!shop.isAdmin()) {
            OfflinePlayer ownerOff = Bukkit.getOfflinePlayer(shop.owner());
            if (!eco.has(ownerOff, total)) return TransactionResult.NOT_ENOUGH_FUNDS;
            // also cap admin payout if we ever get here as admin (already excluded)
        } else {
            long maxPayout = plugin.getConfig().getLong("admin-shops.max-payout-per-transaction", 1_000_000);
            if (total > maxPayout) return TransactionResult.INTERNAL_ERROR;
        }

        TransactionGuard guard = TransactionGuard.snapshot(player, chestInv, balanceSnapshot);
        try {
            // remove from player first
            Map<Integer, ItemStack> notRemoved = player.getInventory().removeItem(stackOf(shop.itemSpec(), amount));
            if (!notRemoved.isEmpty()) {
                return TransactionResult.INVENTORY_FAIL;
            }

            // add to chest (skip for admin shops — items dissolve)
            if (!shop.isAdmin()) {
                Map<Integer, ItemStack> notAdded = chestInv.addItem(stackOf(shop.itemSpec(), amount));
                if (!notAdded.isEmpty()) {
                    return TransactionResult.INVENTORY_FAIL;
                }
            }

            // settle vault
            if (!shop.isAdmin()) {
                OfflinePlayer ownerOff = Bukkit.getOfflinePlayer(shop.owner());
                if (!eco.withdraw(ownerOff, total)) {
                    return TransactionResult.VAULT_FAIL;
                }
            }
            if (!eco.deposit(player, total)) {
                // player deposit failed — refund the owner side if applicable
                if (!shop.isAdmin()) {
                    OfflinePlayer ownerOff = Bukkit.getOfflinePlayer(shop.owner());
                    eco.deposit(ownerOff, total);
                }
                return TransactionResult.VAULT_FAIL;
            }

            guard.commit();
            plugin.getSignRefresher().refresh(shop);
            return TransactionResult.OK;
        } finally {
            if (!guard.committed()) {
                guard.restore();
                eco.restoreBalance(player, balanceSnapshot);
            }
        }
    }

    // ---- helpers ----

    private static ItemStack stackOf(ItemStack template, int amount) {
        ItemStack s = template.clone();
        s.setAmount(amount);
        return s;
    }

    private static ItemStack[] clone(ItemStack[] src) {
        ItemStack[] out = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) {
            out[i] = src[i] == null ? null : src[i].clone();
        }
        return out;
    }

    private static boolean chestHasEnough(Inventory chest, ItemStack template, int amount) {
        return countMatching(chest, template) >= amount;
    }

    private static boolean playerHasEnough(Player p, ItemStack template, int amount) {
        return countMatching(p.getInventory(), template) >= amount;
    }

    private static int countMatching(Inventory inv, ItemStack template) {
        int total = 0;
        for (ItemStack s : inv.getContents()) {
            if (s == null) continue;
            if (s.isSimilar(template)) total += s.getAmount();
        }
        return total;
    }

    @FunctionalInterface
    private interface TradeFn {
        TransactionResult run();
    }
}
