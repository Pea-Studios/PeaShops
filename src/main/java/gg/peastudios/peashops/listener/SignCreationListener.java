package gg.peastudios.peashops.listener;

import gg.peastudios.peashops.PeaShops;
import gg.peastudios.peashops.shop.Shop;
import gg.peastudios.peashops.shop.ShopSignFormat;
import gg.peastudios.peashops.shop.ShopType;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import java.util.UUID;

// fires when a player places a sign and types the shop format. validates,
// resolves the chest, registers the shop with ShopManager + persists.
//
// chest discovery: prefer the chest the sign is attached to (wall sign on
// the front of a chest), fall back to the block directly below (sign post
// on top of a chest). ambiguous cases reject — we don't want to guess.
public final class SignCreationListener implements Listener {

    private final PeaShops plugin;

    public SignCreationListener(PeaShops plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        // legacy String[] api still works — paper supports both. simpler for parsing.
        String[] lines = event.getLines();

        // routing:
        //   line 1 starts with '['  → typed-format path (existing flow)
        //   all lines blank          → maybe gui path (only if attached to a chest)
        //   anything else            → vanity sign, leave alone
        boolean typedShop = lines.length >= 1 && lines[0] != null && lines[0].trim().startsWith("[");
        boolean allBlank = isAllBlank(lines);
        if (!typedShop && !allBlank) return;

        Player player = event.getPlayer();

        if (allBlank) {
            // gui path — only fire if there's a chest the sign refers to AND
            // the player has create perm. otherwise let the blank vanity sign be.
            Block chestForGui = findChest(event.getBlock());
            if (chestForGui == null || !(chestForGui.getState() instanceof InventoryHolder)) return;
            if (!player.hasPermission("peashops.create")) return;
            if (plugin.getShopManager().byLocation(chestForGui.getLocation()) != null) {
                player.sendMessage("§cthis chest is already a shop");
                return;
            }
            plugin.getCreationFlow().start(player, event.getBlock(), chestForGui);
            return;
        }

        ShopSignFormat.ParseResult parsed = ShopSignFormat.parse(lines);
        if (!parsed.isOk()) {
            // only complain if the player clearly meant to make a shop ([SOMETHING] on line 1)
            event.getPlayer().sendMessage("§cshop sign error: " + parsed.error);
            return;
        }

        // permission gate
        if (!player.hasPermission("peashops.create")) {
            event.getPlayer().sendMessage("§cyou don't have permission to create shops");
            return;
        }
        if (parsed.type == ShopType.ADMIN && !player.hasPermission("peashops.admin")) {
            event.getPlayer().sendMessage("§cadmin shops require peashops.admin");
            return;
        }

        // find the chest
        Block chestBlock = findChest(event.getBlock());
        if (chestBlock == null) {
            event.getPlayer().sendMessage("§cno chest attached — place the sign on a chest, or with a chest directly below");
            return;
        }
        if (!(chestBlock.getState() instanceof InventoryHolder)) {
            event.getPlayer().sendMessage("§cattached block isn't a container");
            return;
        }

        // is this chest already a shop?
        if (plugin.getShopManager().byLocation(chestBlock.getLocation()) != null) {
            event.getPlayer().sendMessage("§cthis chest is already a shop");
            return;
        }

        // build the shop
        UUID shopId = UUID.randomUUID();
        UUID ownerId = parsed.type == ShopType.ADMIN
                ? new UUID(0, 0)              // sentinel for admin shops
                : player.getUniqueId();

        // line 3 on the sign is the BUNDLE price (total for line 2's qty) —
        // that's how the decorated sign reads ("32 for $5") and what every
        // mc shop plugin player intuitively expects. internally we store
        // per-unit so total = unitPrice * amount lines up exactly at trade
        // time. parser already guarantees parsed.amount >= 1.
        double pricePerUnit = parsed.price / parsed.amount;
        double buy = (parsed.type == ShopType.BUY || parsed.type == ShopType.ADMIN) ? pricePerUnit : 0;
        double sell = (parsed.type == ShopType.SELL || parsed.type == ShopType.ADMIN) ? pricePerUnit : 0;

        ItemStack template = parsed.item.clone();
        template.setAmount(1); // template carries 1; amount is per-trade

        Shop shop = new Shop(shopId, ownerId, chestBlock.getLocation(), parsed.type, template, buy, sell);

        // stamp the chest with the PDC marker (binds identity to the block, not to the sign)
        plugin.getShopManager().markBlock(chestBlock, shopId);
        plugin.getShopManager().register(shop);
        plugin.getShopStorage().save(shop);

        // decorate the sign visually so the player sees it took
        String[] decorated = ShopSignFormat.decorate(parsed.type, parsed.amount, parsed.price,
                template.getType().name().toLowerCase().replace('_', ' '), player.getName());
        for (int i = 0; i < 4; i++) event.setLine(i, decorated[i]);

        // remember the per-trade amount on the sign... actually we need to stash this somewhere.
        // for 1.0 the amount lives on the sign's line 2 visually but the SHOP doesn't store it
        // (Shop's itemSpec is a 1-item template). transaction lookup will re-read the amount
        // from the sign at click time. simpler than another data field.

        player.sendMessage("§ashop created: §f" + parsed.type + " §7" + parsed.amount
                + " §a" + template.getType() + " §7for §f$" + parsed.price);
    }

    // hint chat message at sign-place time. SignChangeEvent only fires when
    // the player closes the sign edit, so we can't pop the GUI immediately —
    // the player has to press Esc/Done first. this listener at least tells
    // them that's coming, and that they can skip the GUI by typing the format.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Material type = event.getBlock().getType();
        // sign materials all have "_SIGN" in their name
        if (!type.name().endsWith("_SIGN") && !type.name().endsWith("_HANGING_SIGN")) return;
        Block chest = findChest(event.getBlock());
        if (chest == null || !(chest.getState() instanceof InventoryHolder)) return;
        Player p = event.getPlayer();
        if (!p.hasPermission("peashops.create")) return;
        if (plugin.getShopManager().byLocation(chest.getLocation()) != null) return;
        p.sendMessage("§7close the sign editor (Esc) to open the shop GUI, "
                + "§7or type §f[BUY/SELL/ADMIN]§7 / qty / price / item to skip it.");
    }

    private static boolean isAllBlank(String[] lines) {
        if (lines == null) return true;
        for (String line : lines) {
            if (line != null && !line.trim().isEmpty()) return false;
        }
        return true;
    }

    /**
     * locate the chest this sign refers to:
     *   - if wall sign, the block it's attached to (sign's facing-opposite)
     *   - else the block directly below
     * returns null if neither yields a container.
     */
    private Block findChest(Block sign) {
        // wall sign: get attached face
        BlockData data = sign.getBlockData();
        if (data instanceof WallSign wall) {
            BlockFace facing = wall.getFacing();
            Block attached = sign.getRelative(facing.getOppositeFace());
            if (attached.getState() instanceof InventoryHolder) return attached;
        }
        // floor / standing sign — try below
        Block below = sign.getRelative(BlockFace.DOWN);
        if (below.getState() instanceof InventoryHolder) return below;

        return null;
    }
}
