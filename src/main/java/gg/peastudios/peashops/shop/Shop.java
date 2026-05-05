package gg.peastudios.peashops.shop;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// the shop spec — immutable identity (uuid + chest location) with mutable
// trade params (price, type, etc.). identity is bound to a PDC marker on the
// chest block, NOT to sign text or display name. this is the lesson from
// quickshop-hikari's lore-deletion bug — never identify a logical entity by
// serialisable metadata that other plugins can mess with.
public final class Shop {

    private final UUID id;
    private UUID owner;              // player uuid; admin = sentinel zero-uuid. mutable for debug/transfer.
    private final Location chest;    // primary stock chest
    private ShopType type;
    private ItemStack itemSpec;      // canonical item template — what's bought/sold
    private double buyPrice;         // price/item when type allows BUY
    private double sellPrice;        // price/item when type allows SELL
    private boolean dynamicPricing;  // per-shop opt-in
    private UUID networkCentral;     // null for stand-alone, otherwise the central shop's uuid
    private Long saleEndsAt;         // null = no active sale; else ms epoch
    private Double saleMult;         // null = no sale; else multiplier (e.g. 0.5 = 50% off)
    private String description = ""; // owner-set vanity blurb shown in the info gui. empty by default.

    // bulk pricing tiers: minQty -> price/unit at that tier. populated from yaml at create.
    // example: {1: 10.0, 10: 9.0, 64: 8.0} means buy 1@$10, 10+@$9, 64+@$8.
    // looked up by largest minQty <= requested amount.
    private final Map<Integer, Double> bulkTiers = new HashMap<>();

    // granular acl. owner has implicit ALL.
    private final Map<UUID, EnumMap<ShopPermission, Boolean>> acl = new HashMap<>();

    public Shop(UUID id, UUID owner, Location chest, ShopType type, ItemStack itemSpec,
                double buyPrice, double sellPrice) {
        this.id = id;
        this.owner = owner;
        this.chest = chest;
        this.type = type;
        this.itemSpec = itemSpec.clone();
        this.buyPrice = buyPrice;
        this.sellPrice = sellPrice;
    }

    // --- accessors ---
    public UUID id() { return id; }
    public UUID owner() { return owner; }
    public Location chest() { return chest.clone(); }
    public ShopType type() { return type; }
    public ItemStack itemSpec() { return itemSpec.clone(); }
    public double buyPrice() { return buyPrice; }
    public double sellPrice() { return sellPrice; }
    public boolean dynamicPricing() { return dynamicPricing; }
    public UUID networkCentral() { return networkCentral; }

    public boolean isAdmin() { return type == ShopType.ADMIN; }

    public boolean isOnSale() {
        return saleEndsAt != null && saleEndsAt > System.currentTimeMillis() && saleMult != null;
    }

    public double saleMult() {
        return saleMult == null ? 1.0 : saleMult;
    }

    public Long saleEndsAt() { return saleEndsAt; }
    public String description() { return description; }
    public void setDescription(String d) { this.description = d == null ? "" : d; }

    // --- mutators (must go through ShopManager.update() so persistence stays in sync) ---
    public void setOwner(UUID o) { this.owner = o; }
    void setType(ShopType t) { this.type = t; }
    void setItemSpec(ItemStack s) { this.itemSpec = s.clone(); }
    public void setBuyPrice(double p) { this.buyPrice = p; }
    public void setSellPrice(double p) { this.sellPrice = p; }
    public void setDynamicPricing(boolean b) { this.dynamicPricing = b; }
    public void setNetworkCentral(UUID central) { this.networkCentral = central; }
    public void setSale(long endsAtMs, double mult) { this.saleEndsAt = endsAtMs; this.saleMult = mult; }
    public void clearSale() { this.saleEndsAt = null; this.saleMult = null; }

    // --- bulk tiers ---
    public Map<Integer, Double> bulkTiers() {
        return new HashMap<>(bulkTiers); // defensive copy
    }
    public void addTier(int minQty, double pricePerUnit) {
        bulkTiers.put(minQty, pricePerUnit);
    }
    public void clearTiers() {
        bulkTiers.clear();
    }

    /** find best (lowest) per-unit price for the given amount across configured tiers. */
    public double resolveTierPrice(int amount, double fallbackPrice) {
        double best = fallbackPrice;
        int bestQty = 0;
        for (Map.Entry<Integer, Double> tier : bulkTiers.entrySet()) {
            if (tier.getKey() <= amount && tier.getKey() >= bestQty) {
                bestQty = tier.getKey();
                best = tier.getValue();
            }
        }
        return best;
    }

    // --- acl ---
    public boolean canDo(UUID who, ShopPermission what) {
        if (who.equals(owner)) return true;
        EnumMap<ShopPermission, Boolean> grants = acl.get(who);
        return grants != null && Boolean.TRUE.equals(grants.get(what));
    }

    public void grant(UUID who, ShopPermission what) {
        acl.computeIfAbsent(who, k -> new EnumMap<>(ShopPermission.class)).put(what, true);
    }

    public void revoke(UUID who, ShopPermission what) {
        EnumMap<ShopPermission, Boolean> grants = acl.get(who);
        if (grants != null) {
            grants.remove(what);
            if (grants.isEmpty()) acl.remove(who);
        }
    }

    /** snapshot of the per-player perm grants. for storage + UI display. */
    public Map<UUID, EnumMap<ShopPermission, Boolean>> aclSnapshot() {
        Map<UUID, EnumMap<ShopPermission, Boolean>> copy = new HashMap<>();
        for (Map.Entry<UUID, EnumMap<ShopPermission, Boolean>> e : acl.entrySet()) {
            copy.put(e.getKey(), new EnumMap<>(e.getValue()));
        }
        return copy;
    }
}
