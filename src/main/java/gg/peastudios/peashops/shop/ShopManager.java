package gg.peastudios.peashops.shop;

import gg.peastudios.peashops.PeaShops;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.persistence.PersistentDataType;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// shop registry — owns the in-memory map of shops by id and by chest location.
// shop identity is bound to a PDC marker on the chest block. this means even
// if someone replaces the sign or renames the chest, we can still find the
// shop by reading the persistent data. signs are display, not identity.
public final class ShopManager {

    public static final String PDC_KEY_SHOP_ID = "peashops_id";

    private final PeaShops plugin;
    private final NamespacedKey shopIdKey;

    // primary indexes. concurrenthashmap because reads can happen on any thread
    // (analytics gui open, /shop find, etc.) while writes happen on main.
    private final Map<UUID, Shop> byId = new ConcurrentHashMap<>();
    private final Map<Location, UUID> byChest = new ConcurrentHashMap<>();
    private final SearchIndex searchIndex = new SearchIndex();

    public ShopManager(PeaShops plugin) {
        this.plugin = plugin;
        this.shopIdKey = new NamespacedKey(plugin, PDC_KEY_SHOP_ID);
    }

    /**
     * load the shop bound to this block, if any. returns null if the block
     * isn't a tilestate or has no shop marker. this is the canonical lookup
     * — never trust sign text to identify a shop.
     */
    public Shop fromBlock(Block block) {
        if (block == null) return null;
        if (!(block.getState() instanceof TileState ts)) return null;
        String idStr = ts.getPersistentDataContainer().get(shopIdKey, PersistentDataType.STRING);
        if (idStr == null) return null;
        try {
            UUID id = UUID.fromString(idStr);
            return byId.get(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public Shop byLocation(Location loc) {
        UUID id = byChest.get(loc);
        return id == null ? null : byId.get(id);
    }

    public Shop byId(UUID id) {
        return byId.get(id);
    }

    /** register a shop. caller is responsible for placing the PDC marker on the chest block. */
    public void register(Shop shop) {
        byId.put(shop.id(), shop);
        byChest.put(shop.chest(), shop.id());
        searchIndex.add(shop);
    }

    public void remove(UUID id) {
        Shop s = byId.remove(id);
        if (s != null) {
            byChest.remove(s.chest());
            searchIndex.remove(s);
            plugin.getTransactionLock().forget(id);
        }
    }

    public SearchIndex getSearchIndex() { return searchIndex; }

    /** stamp the PDC marker onto a block to bind it to a shop. */
    public void markBlock(Block block, UUID shopId) {
        if (block.getState() instanceof TileState ts) {
            ts.getPersistentDataContainer().set(shopIdKey, PersistentDataType.STRING, shopId.toString());
            ts.update();
        }
    }

    public int total() { return byId.size(); }

    /** snapshot copy — for read-only iteration like /shop find. */
    public Map<UUID, Shop> snapshot() {
        return new HashMap<>(byId);
    }
}
