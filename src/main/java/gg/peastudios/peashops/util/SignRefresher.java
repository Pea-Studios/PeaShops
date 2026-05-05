package gg.peastudios.peashops.util;

import gg.peastudios.peashops.PeaShops;
import gg.peastudios.peashops.shop.Shop;
import gg.peastudios.peashops.shop.ShopSignFormat;
import gg.peastudios.peashops.shop.ShopType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

// finds signs attached to a shop's chest and re-decorates them with the
// CURRENT effective bundle price (post tier + dynamic + sale). called after
// any state-changing event so the visual stays honest:
//   - trade complete (stock changed → dynamic price changed)
//   - /shop sale / /shop dynamic toggled
//   - /shop debugowner changed the owner
//
// the sign's per-click qty is the source of truth for "how many per click."
// we read it back from the existing line, recompute the bundle, and rewrite.
public final class SignRefresher {

    private static final BlockFace[] SIDES = {
            BlockFace.UP, BlockFace.DOWN,
            BlockFace.NORTH, BlockFace.SOUTH,
            BlockFace.EAST, BlockFace.WEST
    };

    private final PeaShops plugin;

    public SignRefresher(PeaShops plugin) {
        this.plugin = plugin;
    }

    /** redecorate every sign attached to this shop's chest. safe to call
     *  from any thread — schedules the actual block-state mutation on main. */
    public void refresh(Shop shop) {
        if (Bukkit.isPrimaryThread()) {
            doRefresh(shop);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> doRefresh(shop));
        }
    }

    private void doRefresh(Shop shop) {
        Block chest = shop.chest().getBlock();
        if (!(chest.getState() instanceof InventoryHolder)) return;

        // current stock — admin shops are infinite for pricing purposes
        int currentStock = shop.isAdmin() ? Integer.MAX_VALUE : countStock(chest, shop.itemSpec());

        for (BlockFace face : SIDES) {
            Block neighbor = chest.getRelative(face);
            if (!(neighbor.getState() instanceof Sign sign)) continue;
            if (!signPointsAt(sign, chest)) continue;

            int qty = readQty(sign);
            if (qty <= 0) continue;

            double basePrice = shop.type() == ShopType.SELL ? shop.sellPrice() : shop.buyPrice();
            double tieredBase = shop.resolveTierPrice(qty, basePrice);
            double effectiveUnit = plugin.getTransactionExecutor().pricingEngine()
                    .resolveUnitPriceFull(shop, currentStock, tieredBase);
            double bundle = effectiveUnit * qty;

            String ownerName;
            if (shop.isAdmin()) {
                ownerName = "[server]";
            } else {
                OfflinePlayer op = Bukkit.getOfflinePlayer(shop.owner());
                ownerName = op.getName() != null ? op.getName() : shop.owner().toString().substring(0, 8);
            }

            String[] decorated = ShopSignFormat.decorate(shop.type(), qty, bundle,
                    shop.itemSpec().getType().name().toLowerCase().replace('_', ' '),
                    ownerName, shop.isOnSale(), shop.dynamicPricing());
            for (int i = 0; i < 4; i++) sign.setLine(i, decorated[i]);
            sign.update();
        }
    }

    /** is the given sign attached to (or sitting on top of) this chest? */
    private static boolean signPointsAt(Sign sign, Block chest) {
        Block signBlock = sign.getBlock();
        if (sign.getBlockData() instanceof WallSign wall) {
            Block attached = signBlock.getRelative(wall.getFacing().getOppositeFace());
            return attached.equals(chest);
        }
        // standing sign — chest is below
        return signBlock.getRelative(BlockFace.DOWN).equals(chest);
    }

    /** mirror of SignInteractListener.parseAmountFromSign — scan line 1 then 2
     *  for the first valid 1-2304 int. */
    private static int readQty(Sign sign) {
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

    private static int countStock(Block chest, ItemStack template) {
        if (!(chest.getState() instanceof InventoryHolder holder)) return 0;
        Inventory inv = holder.getInventory();
        int total = 0;
        for (ItemStack s : inv.getContents()) {
            if (s == null) continue;
            if (s.isSimilar(template)) total += s.getAmount();
        }
        return total;
    }
}
