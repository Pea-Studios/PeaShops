package gg.peastudios.peashops.listener;

import gg.peastudios.peashops.PeaShops;
import gg.peastudios.peashops.shop.Shop;
import gg.peastudios.peashops.shop.ShopManager;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import java.util.UUID;

// stops hopper-mediated dupes during a transaction lock window.
//
// the dupe surface this prevents:
//   - hopper restocks the chest mid-buy → snapshot becomes stale → trade
//     mutates a state that already changed → items dupe
//   - hopper-minecart pickup from the chest (lockette/lwc bypass class)
//   - hopper PULLS from a shop chest (we only allow PUSH-in restock; pulls
//     are a withdraw vector that bypasses ownership checks entirely)
//
// 1.0+ will check whether the affected chest is currently inside an active
// transaction-lock window. for the scaffold this listener is wired and
// guards the basics.
public final class HopperGuardListener implements Listener {

    private final PeaShops plugin;

    public HopperGuardListener(PeaShops plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        if (!plugin.getConfig().getBoolean("hopper-guard.enabled", true)) return;

        Inventory src = event.getSource();
        Inventory dst = event.getDestination();

        // hopper PULLING from a shop chest — always cancel
        if (isShopInventory(src) && isHopperLike(dst)) {
            event.setCancelled(true);
            return;
        }

        // hopper PUSHING into a shop chest — allowed in 1.0+ as the auto-restock
        // feature, but during a transaction lock we cancel. lock check stub.
        if (isShopInventory(dst) && isHopperLike(src)) {
            // TODO 1.0: check plugin.getTransactionLock().isLocked(shop.id())
            // for now we let pushes through so admins can pre-stock chests.
        }
    }

    // a hopper picking up a dropped item only counts as grief if the hopper
    // is feeding a shop chest AND the item came from someone other than the
    // shop owner. otherwise legit feeders (owner Q-drops to restock) still
    // work. mob/dispenser/cart drops have no thrower → cancel by default
    // (better to over-restrict than to leak).
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(InventoryPickupItemEvent event) {
        Inventory dst = event.getInventory();
        if (dst.getType() != InventoryType.HOPPER) return;
        if (dst.getLocation() == null) return;

        Shop adj = adjacentShop(dst.getLocation().getBlock());
        if (adj == null) return;

        UUID thrower = event.getItem().getThrower();
        if (thrower == null || !thrower.equals(adj.owner())) {
            event.setCancelled(true);
        }
    }

    private static final BlockFace[] SIDES = {
            BlockFace.UP, BlockFace.DOWN,
            BlockFace.NORTH, BlockFace.SOUTH,
            BlockFace.EAST, BlockFace.WEST
    };

    /** is any of the 6 neighbours of `block` a registered shop chest? */
    private Shop adjacentShop(Block block) {
        ShopManager mgr = plugin.getShopManager();
        if (mgr == null) return null;
        for (BlockFace face : SIDES) {
            Shop s = mgr.fromBlock(block.getRelative(face));
            if (s != null) return s;
        }
        return null;
    }

    private boolean isShopInventory(Inventory inv) {
        if (inv == null) return false;
        if (inv.getLocation() == null) return false;
        Block block = inv.getLocation().getBlock();
        ShopManager mgr = plugin.getShopManager();
        if (mgr == null) return false;
        Shop shop = mgr.fromBlock(block);
        return shop != null;
    }

    private boolean isHopperLike(Inventory inv) {
        if (inv == null) return false;
        // covers HOPPER block, HOPPER_MINECART entity inventory, and DROPPER
        // (some servers use droppers as a dupe vector — block them too)
        switch (inv.getType()) {
            case HOPPER:
            case DROPPER:
                return true;
            default:
                return false;
        }
    }
}
