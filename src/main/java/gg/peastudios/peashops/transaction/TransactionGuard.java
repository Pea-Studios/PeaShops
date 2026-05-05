package gg.peastudios.peashops.transaction;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

// snapshot + rollback for a single transaction. before any mutation we deep
// clone the inventories and the vault balance into this; on any failure
// step we restore the snapshot byte-for-byte.
//
// snapshot-restore is what quickshop-hikari moved to after their count-based
// rollback turned out to dupe items when metadata changed mid-transaction
// (https://github.com/QuickShop-Community/QuickShop-Hikari).
//
// usage:
//   TransactionGuard guard = TransactionGuard.snapshot(player, chestInv);
//   try {
//       // ... compute, mutate inventories, settle vault ...
//       guard.commit();   // marks success, no restore needed
//   } finally {
//       if (!guard.committed()) guard.restore();
//   }
//
// the "restore vs commit" boolean is what makes this safe under exceptions —
// any thrown error inside the try block leaves committed=false, so the
// finally restores. exceptions can come from anywhere: chunk unload mid-trade,
// vault hangup, even an OOM. fail closed.
public final class TransactionGuard {

    private final PlayerInventory playerInv;
    private final Inventory chestInv;
    private final ItemStack[] playerSnapshot;
    private final ItemStack[] chestSnapshot;
    private final Player player;
    private final double balanceSnapshot;

    private boolean committed = false;

    private TransactionGuard(Player player, PlayerInventory playerInv, Inventory chestInv,
                             ItemStack[] playerSnapshot, ItemStack[] chestSnapshot,
                             double balanceSnapshot) {
        this.player = player;
        this.playerInv = playerInv;
        this.chestInv = chestInv;
        this.playerSnapshot = playerSnapshot;
        this.chestSnapshot = chestSnapshot;
        this.balanceSnapshot = balanceSnapshot;
    }

    /**
     * snapshot the player's full inventory + the chest's full contents +
     * the player's vault balance (caller passes it in — this class doesn't
     * know about vault).
     *
     * deep-clones every itemstack so subsequent mutations to the live
     * inventory don't bleed into the snapshot.
     */
    public static TransactionGuard snapshot(Player player, Inventory chestInv, double currentBalance) {
        PlayerInventory pi = player.getInventory();
        ItemStack[] pSnap = deepClone(pi.getContents());
        ItemStack[] cSnap = deepClone(chestInv.getContents());
        return new TransactionGuard(player, pi, chestInv, pSnap, cSnap, currentBalance);
    }

    public void commit() {
        committed = true;
    }

    public boolean committed() {
        return committed;
    }

    public double balanceSnapshot() {
        return balanceSnapshot;
    }

    /**
     * restore both inventories. caller is responsible for restoring vault
     * balance via EconomyBridge.restoreBalance(player, balanceSnapshot).
     *
     * note: setContents writes into the live inventory in one call. if the
     * server crashes between this method starting and finishing, the
     * inventory is left in a torn state. that's a known limitation of
     * bukkit's inventory API — there's no atomic transaction primitive.
     */
    public void restore() {
        if (committed) return; // shouldn't happen, but guard anyway
        try {
            playerInv.setContents(playerSnapshot);
        } catch (Exception e) {
            // log? caller's job — this class stays simple. but leak this up
            // so transaction handler can incident-log it.
            throw new RuntimeException("failed to restore player inventory", e);
        }
        try {
            chestInv.setContents(chestSnapshot);
        } catch (Exception e) {
            throw new RuntimeException("failed to restore chest inventory", e);
        }
    }

    private static ItemStack[] deepClone(ItemStack[] src) {
        ItemStack[] out = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) {
            out[i] = src[i] == null ? null : src[i].clone();
        }
        return out;
    }
}
