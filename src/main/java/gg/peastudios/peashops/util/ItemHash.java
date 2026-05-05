package gg.peastudios.peashops.util;

import org.bukkit.inventory.ItemStack;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

// canonical itemstack hashing. ItemStack.isSimilar misses stored enchants
// and a few other NBT cases, so we use serializeAsBytes (1.20.5+) and sha-256.
public final class ItemHash {

    private ItemHash() {}

    public static String hash(ItemStack item) {
        if (item == null) return "null";
        try {
            byte[] bytes = item.serializeAsBytes();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // every JVM has SHA-256; this should never happen
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * stricter equality than ItemStack.isSimilar. true iff the canonical
     * serialised forms match.
     */
    public static boolean equalsExact(ItemStack a, ItemStack b) {
        if (a == null || b == null) return a == b;
        return hash(a).equals(hash(b));
    }
}
