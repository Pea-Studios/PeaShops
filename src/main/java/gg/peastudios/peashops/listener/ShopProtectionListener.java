package gg.peastudios.peashops.listener;

import gg.peastudios.peashops.PeaShops;
import gg.peastudios.peashops.shop.Shop;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import java.util.UUID;

// protects the shop "cluster" — chest + attached signs + adjacent hoppers —
// from non-owner break / explode / piston / open / hopper-grief.
//
// shop identity lives on the chest (pdc marker). signs and feeder hoppers
// are derived — we resolve them back to the owning shop and apply the same
// owner check.
public final class ShopProtectionListener implements Listener {

    private static final BlockFace[] SIDES = {
            BlockFace.UP, BlockFace.DOWN,
            BlockFace.NORTH, BlockFace.SOUTH,
            BlockFace.EAST, BlockFace.WEST
    };

    private final PeaShops plugin;

    public ShopProtectionListener(PeaShops plugin) {
        this.plugin = plugin;
    }

    // ---- break ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block broken = event.getBlock();
        Player p = event.getPlayer();

        // 1. is the broken block the shop chest itself?
        Shop shop = plugin.getShopManager().fromBlock(broken);

        // 2. or is it a sign attached to a shop chest?
        boolean isSign = (broken.getState() instanceof Sign);
        if (shop == null && isSign) {
            Block chest = chestForSign(broken);
            if (chest != null) shop = plugin.getShopManager().fromBlock(chest);
        }

        if (shop == null) return;

        if (!canManage(p, shop)) {
            event.setCancelled(true);
            p.sendMessage(isSign ? "§ccan't break that — it belongs to a shop"
                                 : "§cthat's not your shop");
            return;
        }

        // owner / bypass breaking the chest -> tear down the shop
        // owner / bypass breaking just the sign -> leave the chest's shop entry alone
        // (placing a new sign on the same chest will still find the existing shop —
        //  but the shop is currently un-tradeable until a new sign is added)
        if (!isSign) {
            plugin.getShopManager().remove(shop.id());
            plugin.getShopStorage().delete(shop.id());
            p.sendMessage("§ashop removed");
        } else {
            p.sendMessage("§7sign removed (shop data still on the chest)");
        }
    }

    // ---- place — block griefer hoppers next to other people's shops ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Material type = event.getBlock().getType();
        // any container that could push or pull from a shop chest
        if (type != Material.HOPPER && type != Material.DROPPER) return;

        Block placed = event.getBlock();
        Player p = event.getPlayer();

        for (BlockFace face : SIDES) {
            Block neighbor = placed.getRelative(face);
            Shop shop = plugin.getShopManager().fromBlock(neighbor);
            if (shop == null) continue;
            if (!canManage(p, shop)) {
                event.setCancelled(true);
                p.sendMessage("§ccan't place hoppers next to someone else's shop");
                return;
            }
        }
    }

    // ---- inventory open — block non-owner peeking into shop chests
    //                      AND adjacent hoppers (the feeder-grief surface) ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        Inventory inv = event.getInventory();
        InventoryHolder holder = inv.getHolder();
        if (holder == null) return;
        if (inv.getLocation() == null) return;
        Block block = inv.getLocation().getBlock();

        // direct hit: the chest itself is a shop
        Shop shop = plugin.getShopManager().fromBlock(block);

        // adjacency hit: a hopper/dropper next to a shop chest is treated as
        // part of that shop. otherwise a non-owner could right-click the
        // owner's feeder hopper and drop junk in, which the hopper then
        // pushes into the shop chest.
        boolean isAdjacentFeeder = false;
        if (shop == null && (block.getType() == Material.HOPPER || block.getType() == Material.DROPPER)) {
            for (BlockFace face : SIDES) {
                Shop adj = plugin.getShopManager().fromBlock(block.getRelative(face));
                if (adj != null) { shop = adj; isAdjacentFeeder = true; break; }
            }
        }

        if (shop == null) return;

        HumanEntity opener = event.getPlayer();
        if (!canManage(opener, shop)) {
            event.setCancelled(true);
            opener.sendMessage(isAdjacentFeeder
                    ? "§cthat hopper feeds someone else's shop"
                    : "§cthat chest belongs to a shop — right-click the sign to trade");
        }
    }

    // ---- explosions ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isShopOrShopSign);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isShopOrShopSign);
    }

    // ---- pistons ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block b : event.getBlocks()) {
            if (isShopOrShopSign(b)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block b : event.getBlocks()) {
            if (isShopOrShopSign(b)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    // ---- helpers ----

    private boolean isShopOrShopSign(Block b) {
        if (plugin.getShopManager().fromBlock(b) != null) return true;
        if (b.getState() instanceof Sign) {
            Block chest = chestForSign(b);
            if (chest != null && plugin.getShopManager().fromBlock(chest) != null) return true;
        }
        return false;
    }

    /** mirror of SignCreationListener.findChest — given a sign, find the chest it refers to. */
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

    /** admin shops use the zero-uuid as a sentinel owner. */
    private static boolean isAdminShop(Shop shop) {
        return shop.owner().equals(new UUID(0L, 0L));
    }

    /**
     * unified "can the actor manage this shop" check used by every protection
     * surface (break / open / hopper-place). returns true if any of:
     *   - actor has peashops.bypass (admin override of everything)
     *   - actor is the shop's owner uuid
     *   - shop is an admin shop AND actor has peashops.admin
     * otherwise false → cancel the action.
     */
    private static boolean canManage(HumanEntity actor, Shop shop) {
        if (actor.hasPermission("peashops.bypass")) return true;
        if (shop.owner().equals(actor.getUniqueId())) return true;
        if (isAdminShop(shop) && actor.hasPermission("peashops.admin")) return true;
        return false;
    }
}
