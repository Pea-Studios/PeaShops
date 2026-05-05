package gg.peastudios.peashops.shop;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

// parses + formats shop sign content. format:
//   line 1: [BUY] / [SELL] / [ADMIN]   (TRADE planned for 1.1)
//   line 2: amount (positive integer)
//   line 3: price — "$10" or just "10"
//   line 4: item material name (eg DIAMOND, oak_log, GOLDEN_APPLE)
//
// ParseResult.error is non-null when the sign couldn't be turned into a shop.
// kept simple — reject anything weird with a clear reason and let the player
// retype.
public final class ShopSignFormat {

    public static final class ParseResult {
        public final ShopType type;
        public final int amount;
        public final double price;
        public final ItemStack item;
        public final String error;

        private ParseResult(ShopType type, int amount, double price, ItemStack item, String error) {
            this.type = type; this.amount = amount; this.price = price; this.item = item; this.error = error;
        }
        public static ParseResult ok(ShopType t, int amt, double p, ItemStack it) {
            return new ParseResult(t, amt, p, it, null);
        }
        public static ParseResult fail(String why) {
            return new ParseResult(null, 0, 0, null, why);
        }
        public boolean isOk() { return error == null; }
    }

    public static ParseResult parse(String[] lines) {
        if (lines == null || lines.length < 4) return ParseResult.fail("need 4 lines");

        // line 1: type tag
        String tag = stripBrackets(lines[0]).toUpperCase();
        ShopType type;
        switch (tag) {
            case "BUY":   type = ShopType.BUY;   break;
            case "SELL":  type = ShopType.SELL;  break;
            case "ADMIN": type = ShopType.ADMIN; break;
            default: return ParseResult.fail("line 1 must be [BUY], [SELL], or [ADMIN]");
        }

        // line 2: amount
        int amount;
        try {
            amount = Integer.parseInt(lines[1].trim());
        } catch (NumberFormatException e) {
            return ParseResult.fail("line 2 must be a number (amount)");
        }
        if (amount <= 0 || amount > 64 * 36) {
            return ParseResult.fail("amount must be 1-2304");
        }

        // line 3: price
        String priceStr = lines[2].trim();
        if (priceStr.startsWith("$")) priceStr = priceStr.substring(1);
        // also allow trailing $ for currency symbols on the right
        if (priceStr.endsWith("$")) priceStr = priceStr.substring(0, priceStr.length() - 1);
        double price;
        try {
            price = Double.parseDouble(priceStr);
        } catch (NumberFormatException e) {
            return ParseResult.fail("line 3 must be a price (e.g. $10)");
        }
        if (price < 0 || !Double.isFinite(price)) {
            return ParseResult.fail("price must be non-negative");
        }

        // line 4: item
        String itemName = lines[3].trim().toUpperCase().replace(' ', '_');
        Material mat = Material.matchMaterial(itemName);
        if (mat == null || mat.isAir()) {
            return ParseResult.fail("line 4 unknown item: " + lines[3]);
        }
        ItemStack item = new ItemStack(mat);

        return ParseResult.ok(type, amount, price, item);
    }

    /**
     * decorate the sign post-creation. layout:
     *   line 1: "32 for $5"   (qty + bundle price — the headline)
     *   line 2: cobblestone   (the item)
     *   line 3: PeaSplitter   (owner name; player can edit later for vanity)
     *   line 4: [BUY]         (colored type marker — also a visual signal
     *                          to other players "don't grief this")
     */
    public static String[] decorate(ShopType type, int amount, double price, String itemDisplay, String ownerName) {
        return decorate(type, amount, price, itemDisplay, ownerName, false, false);
    }

    /**
     * extended decorator. price is the bundle price already adjusted for
     * dynamic curve + sale (caller computes). flags add a small visual
     * suffix on line 1: §6! for sale, §b* for dynamic. line 1 is also
     * colored differently when on sale (gold) so it's eye-catching.
     */
    public static String[] decorate(ShopType type, int amount, double price, String itemDisplay,
                                    String ownerName, boolean onSale, boolean dynamic) {
        String[] out = new String[4];
        String priceStr = price == Math.floor(price) ? String.valueOf((long) price) : String.format("%.2f", price);
        String suffix = onSale ? " §6§l!" : (dynamic ? " §b*" : "");
        String headlineColor = onSale ? "§6" : "§f";
        out[0] = headlineColor + amount + " §7for " + headlineColor + "$" + priceStr + suffix;
        out[1] = "§f" + itemDisplay;
        out[2] = "§7" + (ownerName == null ? "" : ownerName);
        switch (type) {
            case BUY:   out[3] = "§a§l[BUY]"; break;
            case SELL:  out[3] = "§e§l[SELL]"; break;
            case ADMIN: out[3] = "§c§l[ADMIN]"; break;
            default:    out[3] = "§7[?]";
        }
        return out;
    }

    private static String stripBrackets(String s) {
        s = s.trim();
        if (s.startsWith("[") && s.endsWith("]") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
