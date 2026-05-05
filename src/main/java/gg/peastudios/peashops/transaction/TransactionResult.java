package gg.peastudios.peashops.transaction;

// outcome of a transaction attempt. used by TransactionExecutor to tell
// the caller exactly why a trade succeeded/failed so the right user-facing
// message can fire.
public enum TransactionResult {
    OK,
    SHOP_BUSY,            // lock acquisition timeout — try again
    NO_SHOP,              // sign click landed on something that isn't a shop
    NO_PERMISSION,        // player lacks peashops.use or shop ACL deny
    NOT_ENOUGH_FUNDS,
    NOT_ENOUGH_STOCK,
    NOT_ENOUGH_SPACE,     // player inventory can't fit the items
    PRICE_DRIFT,          // recomputed price drifted too far from quoted
    VAULT_FAIL,           // vault provider failed or lied — funds rolled back
    INVENTORY_FAIL,       // mid-mutation inventory error — rolled back
    INTERNAL_ERROR;       // anything else — rolled back, console-logged
}
