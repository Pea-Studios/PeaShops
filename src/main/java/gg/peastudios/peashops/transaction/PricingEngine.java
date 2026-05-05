package gg.peastudios.peashops.transaction;

import gg.peastudios.peashops.PeaShops;
import gg.peastudios.peashops.shop.Shop;

// dynamic pricing curve. opt-in per shop (shop.dynamicPricing()).
//
// price = base * (target / max(currentStock, 1))^elasticity
// then clamped to [base * floor, base * ceiling].
//
// intuition: when stock is low, price climbs (scarcity). when high, it
// drops (surplus). target is the "comfortable" stock level — we hardcode
// it at 64 (one stack) for 1.0; later versions can let owners tune it.
//
// elasticity controls steepness:
//   0.0 = no change (flat)
//   0.5 = sqrt response (gentle)
//   1.0 = linear inverse (sharp)
//
// floor + ceiling come from config so server admins can keep prices sane
// even if a shop empties out or floods.
public final class PricingEngine {

    private static final double TARGET_STOCK = 64.0;
    private static final double ELASTICITY = 0.5;

    private final PeaShops plugin;

    public PricingEngine(PeaShops plugin) {
        this.plugin = plugin;
    }

    /**
     * full effective per-unit price, including dynamic + sale layers.
     * tier resolution is the caller's job — pass the tiered base in.
     */
    public double resolveUnitPriceFull(Shop shop, int currentStock, double tieredBasePrice) {
        double unit = resolveUnitPrice(shop, currentStock, tieredBasePrice);
        if (shop.isOnSale()) unit *= shop.saleMult();
        return unit;
    }

    /**
     * compute the per-unit price for a shop given its current stock.
     * caller passes shop and the current stock count; this method does
     * NOT scan the chest (that's the executor's job — it already has
     * stock counted under the lock).
     *
     * if shop.dynamicPricing() is false, returns basePrice unchanged.
     */
    public double resolveUnitPrice(Shop shop, int currentStock, double basePrice) {
        if (!shop.dynamicPricing()) return basePrice;
        if (basePrice <= 0) return basePrice;

        double effectiveStock = Math.max(currentStock, 1);
        double mult = Math.pow(TARGET_STOCK / effectiveStock, ELASTICITY);
        double price = basePrice * mult;

        double floor = plugin.getConfig().getDouble("dynamic-pricing.global-floor-mult", 0.5);
        double ceil  = plugin.getConfig().getDouble("dynamic-pricing.global-ceiling-mult", 3.0);
        double minP = basePrice * floor;
        double maxP = basePrice * ceil;
        if (price < minP) price = minP;
        if (price > maxP) price = maxP;
        return price;
    }
}
