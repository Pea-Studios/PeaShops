package gg.peastudios.peashops.shop;

import gg.peastudios.peashops.PeaShops;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;

// yaml persistence — one file per shop under plugins/PeaShops/shops/<uuid>.yml.
// keeps things simple and human-readable (you can hand-edit if needed).
//
// loaded on enable, saved on register/update/remove. for 1.0 we serialize
// the full Shop including bulk tiers and acl. linked-network and dynamic
// pricing fields land in 1.1+.
//
// TODO: switch to sqlite if shop count goes past ~10k. yaml's load time at
// scale is fine but listing all shops for /shop find gets slow.
public final class ShopStorage {

    private final PeaShops plugin;
    private final File shopsDir;

    public ShopStorage(PeaShops plugin) {
        this.plugin = plugin;
        this.shopsDir = new File(plugin.getDataFolder(), "shops");
        if (!shopsDir.exists() && !shopsDir.mkdirs()) {
            plugin.getLogger().warning("could not create shops dir at " + shopsDir.getAbsolutePath());
        }
    }

    public void saveAll() {
        for (Shop s : plugin.getShopManager().snapshot().values()) {
            save(s);
        }
    }

    public void save(Shop shop) {
        File f = new File(shopsDir, shop.id() + ".yml");
        YamlConfiguration y = new YamlConfiguration();
        y.set("id", shop.id().toString());
        y.set("owner", shop.owner().toString());
        Location loc = shop.chest();
        y.set("chest.world", loc.getWorld().getName());
        y.set("chest.x", loc.getBlockX());
        y.set("chest.y", loc.getBlockY());
        y.set("chest.z", loc.getBlockZ());
        y.set("type", shop.type().name());
        y.set("buyPrice", shop.buyPrice());
        y.set("sellPrice", shop.sellPrice());
        y.set("dynamicPricing", shop.dynamicPricing());
        if (shop.networkCentral() != null) {
            y.set("networkCentral", shop.networkCentral().toString());
        }
        // sale persists across restarts so a /reload can't accidentally
        // reset an active sale window (notes.txt:62 — journal-equivalent).
        if (shop.saleEndsAt() != null) {
            y.set("sale.endsAt", shop.saleEndsAt());
            y.set("sale.mult", shop.saleMult());
        }
        if (shop.description() != null && !shop.description().isEmpty()) {
            y.set("description", shop.description());
        }
        // serialize itemSpec as a single-item map; bukkit's yaml support handles ItemStack natively
        y.set("itemSpec", shop.itemSpec());

        // bulk tiers — Map<Integer, Double> stored as a section
        for (var e : shop.bulkTiers().entrySet()) {
            y.set("bulkTiers." + e.getKey(), e.getValue());
        }

        // acl — per-player granted perms, list of enum names per player uuid
        for (var e : shop.aclSnapshot().entrySet()) {
            java.util.List<String> perms = new java.util.ArrayList<>();
            for (var perm : e.getValue().keySet()) perms.add(perm.name());
            y.set("acl." + e.getKey(), perms);
        }

        try {
            y.save(f);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "could not save shop " + shop.id(), e);
        }
    }

    public void delete(UUID shopId) {
        File f = new File(shopsDir, shopId + ".yml");
        if (f.exists() && !f.delete()) {
            plugin.getLogger().warning("could not delete shop file " + f.getName());
        }
    }

    public int loadAll() {
        if (!shopsDir.exists()) return 0;
        File[] files = shopsDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return 0;
        int loaded = 0;
        for (File f : files) {
            try {
                if (loadOne(f)) loaded++;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "skipping malformed shop file " + f.getName(), e);
            }
        }
        return loaded;
    }

    private boolean loadOne(File f) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        UUID id = UUID.fromString(y.getString("id"));
        UUID owner = UUID.fromString(y.getString("owner"));

        String worldName = y.getString("chest.world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("shop " + id + " references missing world " + worldName + " — skipping");
            return false;
        }
        Location loc = new Location(world, y.getInt("chest.x"), y.getInt("chest.y"), y.getInt("chest.z"));

        ShopType type;
        try {
            type = ShopType.valueOf(y.getString("type"));
        } catch (IllegalArgumentException e) {
            return false;
        }

        ItemStack itemSpec = y.getItemStack("itemSpec");
        if (itemSpec == null || itemSpec.getType() == Material.AIR) return false;

        double buy = y.getDouble("buyPrice");
        double sell = y.getDouble("sellPrice");

        Shop shop = new Shop(id, owner, loc, type, itemSpec, buy, sell);
        if (y.contains("dynamicPricing")) {
            shop.setDynamicPricing(y.getBoolean("dynamicPricing"));
        }
        if (y.contains("networkCentral")) {
            try {
                shop.setNetworkCentral(UUID.fromString(y.getString("networkCentral")));
            } catch (IllegalArgumentException ignored) { /* bad value, leave null */ }
        }
        if (y.contains("sale.endsAt")) {
            long endsAt = y.getLong("sale.endsAt");
            double mult = y.getDouble("sale.mult", 1.0);
            // only restore if not already expired — avoids re-arming dead sales
            if (endsAt > System.currentTimeMillis()) {
                shop.setSale(endsAt, mult);
            }
        }
        if (y.contains("description")) {
            shop.setDescription(y.getString("description", ""));
        }
        if (y.isConfigurationSection("bulkTiers")) {
            for (String key : y.getConfigurationSection("bulkTiers").getKeys(false)) {
                try {
                    int qty = Integer.parseInt(key);
                    double price = y.getDouble("bulkTiers." + key);
                    shop.addTier(qty, price);
                } catch (NumberFormatException ignored) { /* skip bad keys */ }
            }
        }
        if (y.isConfigurationSection("acl")) {
            for (String uuidStr : y.getConfigurationSection("acl").getKeys(false)) {
                try {
                    UUID who = UUID.fromString(uuidStr);
                    for (String permStr : y.getStringList("acl." + uuidStr)) {
                        try { shop.grant(who, ShopPermission.valueOf(permStr)); }
                        catch (IllegalArgumentException ignored) { /* unknown perm name */ }
                    }
                } catch (IllegalArgumentException ignored) { /* bad uuid */ }
            }
        }

        plugin.getShopManager().register(shop);
        return true;
    }
}
