package gg.peastudios.peashops.shop;

// granular shop permissions — owners can grant subsets to specific members
// without giving them full land trust. checked on every mutation, not just
// at gui open (revocation during open gui takes effect on next click).
public enum ShopPermission {
    EDIT,         // change price, item, type
    RESTOCK,      // add or remove stock items
    WITHDRAW,     // pull earnings out
    DELETE,       // tear the shop down
    VIEW_ANALYTICS // see analytics gui (read-only)
}
