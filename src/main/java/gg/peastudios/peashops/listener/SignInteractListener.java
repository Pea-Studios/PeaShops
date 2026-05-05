package gg.peastudios.peashops.listener;

import gg.peastudios.peashops.PeaShops;
import gg.peastudios.peashops.shop.Shop;
import gg.peastudios.peashops.shop.ShopType;
import gg.peastudios.peashops.transaction.TransactionResult;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;

// shop sign right-click → trade flow.
// dedupes off-hand, cancels-and-denies the click, defers the actual mutation
// to next tick so we never touch inventory inside an interact handler.
public final class SignInteractListener implements Listener {

    private final PeaShops plugin;

    public SignInteractListener(PeaShops plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        // off-hand dedup
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block clicked = event.getClickedBlock();
        if (clicked == null) return;
        if (!(clicked.getState() instanceof Sign sign)) return;

        Block chest = findChest(clicked);
        if (chest == null) return;

        Shop shop = plugin.getShopManager().fromBlock(chest);
        if (shop == null) return;

        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);

        Player player = event.getPlayer();

        if (player.isSneaking()) {
            plugin.getInfoGUI().open(player, shop);
            return;
        }

        if (shop.owner().equals(player.getUniqueId())) {
            player.sendMessage("§7this is your shop — break the sign to remove it. shift+right-click for info.");
            return;
        }

        if (!player.hasPermission("peashops.use")) {
            player.sendMessage("§cno permission to use shops");
            return;
        }

        int amount = parseAmountFromSign(sign);
        if (amount <= 0) {
            player.sendMessage("§cmalformed shop sign");
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            // re-resolve in case the shop was removed between the click and now
            Shop liveShop = plugin.getShopManager().byId(shop.id());
            if (liveShop == null) {
                player.sendMessage("§cthat shop no longer exists");
                return;
            }
            TransactionResult result;
            if (liveShop.type() == ShopType.BUY || liveShop.type() == ShopType.ADMIN) {
                result = plugin.getTransactionExecutor().buy(player, liveShop, amount, null);
            } else {
                result = plugin.getTransactionExecutor().sell(player, liveShop, amount, null);
            }
            tellResult(player, result, liveShop, amount);
        });
    }

    private void tellResult(Player p, TransactionResult r, Shop shop, int amount) {
        String item = shop.itemSpec().getType().name().toLowerCase().replace('_', ' ');
        switch (r) {
            case OK:
                boolean buying = (shop.type() == ShopType.BUY || shop.type() == ShopType.ADMIN);
                p.sendMessage("§a" + (buying ? "Bought " : "Sold ") + amount + "x §f" + item);
                break;
            case SHOP_BUSY:        p.sendMessage("§eshop busy, try again"); break;
            case NOT_ENOUGH_FUNDS: p.sendMessage("§cyou can't afford that"); break;
            case NOT_ENOUGH_STOCK: p.sendMessage(buyingFor(shop) ? "§cshop out of stock" : "§cyou don't have those items"); break;
            case NOT_ENOUGH_SPACE: p.sendMessage(buyingFor(shop) ? "§cyour inventory is full" : "§cshop chest is full"); break;
            case PRICE_DRIFT:      p.sendMessage("§eprice changed — click again to confirm"); break;
            case VAULT_FAIL:       p.sendMessage("§ceconomy error, transaction rolled back"); break;
            case INVENTORY_FAIL:   p.sendMessage("§cinventory error, transaction rolled back"); break;
            case NO_SHOP:          p.sendMessage("§cnot a valid shop"); break;
            case NO_PERMISSION:    p.sendMessage("§cno permission"); break;
            default:               p.sendMessage("§ctransaction error — see console");
        }
    }

    private static boolean buyingFor(Shop shop) {
        return shop.type() == ShopType.BUY || shop.type() == ShopType.ADMIN;
    }

    // qty is always the leading int on either line 0 or line 1.
    // scans line 0 first so the new "32 for $5" layout wins over older
    // decorated formats which had qty on line 1.
    private static int parseAmountFromSign(Sign sign) {
        for (int idx : new int[]{0, 1}) {
            String line = sign.getLine(idx);
            if (line == null) continue;
            String stripped = line.replaceAll("§.", "").trim();
            if (stripped.isEmpty()) continue;
            String first = stripped.split("\\s+")[0];
            try {
                int v = Integer.parseInt(first);
                if (v >= 1 && v <= 64 * 36) return v;
            } catch (NumberFormatException ignore) {}
        }
        return -1;
    }

    private static Block findChest(Block sign) {
        BlockData data = sign.getBlockData();
        if (data instanceof WallSign wall) {
            Block attached = sign.getRelative(wall.getFacing().getOppositeFace());
            if (attached.getState() instanceof InventoryHolder) return attached;
        }
        Block below = sign.getRelative(BlockFace.DOWN);
        if (below.getState() instanceof InventoryHolder) return below;
        return null;
    }
}
