package gg.peastudios.peashops.shop;

import org.bukkit.Material;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// reverse index: material -> shop ids selling/buying that material. populated
// incrementally by ShopManager on register/remove. lookup is constant-time
// per material; iteration cost is just the count of shops trading that item.
//
// 1.0 doesn't bother with cache or per-player rate limiting — at small scale
// it's noise. revisit if /shop find ends up hot in the profiler.
public final class SearchIndex {

    private final Map<Material, Set<UUID>> byMaterial = new ConcurrentHashMap<>();

    public void add(Shop shop) {
        Material mat = shop.itemSpec().getType();
        byMaterial.computeIfAbsent(mat, k -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(shop.id());
    }

    public void remove(Shop shop) {
        Material mat = shop.itemSpec().getType();
        Set<UUID> set = byMaterial.get(mat);
        if (set != null) {
            set.remove(shop.id());
            if (set.isEmpty()) byMaterial.remove(mat);
        }
    }

    /** snapshot copy — safe for caller to iterate without locking. */
    public List<UUID> findByMaterial(Material mat) {
        Set<UUID> set = byMaterial.get(mat);
        if (set == null || set.isEmpty()) return Collections.emptyList();
        return new ArrayList<>(set);
    }

    public int trackedMaterials() { return byMaterial.size(); }
}
