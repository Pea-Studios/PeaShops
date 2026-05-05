package gg.peastudios.peashops.transaction;

// computed price + tier info for a transaction at lock-acquisition time.
// the "price drift" check compares the price the player saw at gui-open
// vs the price recomputed inside the lock; if it drifted past the configured
// threshold we abort with a "price changed, retry" message.
//
// dynamic pricing without this check = arbitrage exploit. two players hit
// /shop, both quoted $10, both buy, shop only earns $20 even though the
// curve says the second buy should cost $11.
public final class PriceQuote {

    public final double unitPrice;
    public final int amount;
    public final double total;
    public final int tierIndex;     // -1 if no bulk-tier matched, else 0..n
    public final long quotedAt;     // ms epoch — used for drift checks

    public PriceQuote(double unitPrice, int amount, double total, int tierIndex, long quotedAt) {
        this.unitPrice = unitPrice;
        this.amount = amount;
        this.total = total;
        this.tierIndex = tierIndex;
        this.quotedAt = quotedAt;
    }

    /**
     * returns true if the prior quote drifted past the allowed multiplier.
     * eg max=0.05 means a 5% delta in either direction triggers a retry.
     */
    public boolean drifted(PriceQuote prior, double maxDelta) {
        if (prior == null) return false;
        double diff = Math.abs(prior.total - this.total);
        double base = Math.max(prior.total, this.total);
        if (base <= 0.0) return false;
        return (diff / base) > maxDelta;
    }
}
