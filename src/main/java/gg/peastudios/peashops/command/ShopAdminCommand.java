package gg.peastudios.peashops.command;

import gg.peastudios.peashops.PeaShops;
import gg.peastudios.peashops.shop.Shop;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

// /shopadmin — staff-only commands. gated entirely behind peashops.admin
// so plugin.yml only needs to declare it; no fine-grained sub-perms.
public final class ShopAdminCommand implements CommandExecutor, TabCompleter {

    private static final int PAGE_SIZE = 8;

    private final PeaShops plugin;

    public ShopAdminCommand(PeaShops plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("peashops.admin")) {
            sender.sendMessage("§cno permission");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§7/shopadmin reload | stats | list [page] | remove <uuidPrefix>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadConfig();
                plugin.getMessages().reload();
                sender.sendMessage("§areloaded peashops config + messages");
            }
            case "stats" -> {
                int shops = plugin.getShopManager().total();
                int mats = plugin.getShopManager().getSearchIndex().trackedMaterials();
                int locks = plugin.getTransactionLock().trackedShops();
                int nets  = plugin.getTransactionLock().trackedNetworks();
                int inflight = plugin.getTransactionExecutor().inFlightCount();
                sender.sendMessage("§ashops: §f" + shops);
                sender.sendMessage("§asearch index: §f" + mats + " materials");
                sender.sendMessage("§alocks tracked: §f" + locks + " shop · " + nets + " network");
                sender.sendMessage("§ain flight: §f" + inflight);
            }
            case "list" -> handleList(sender, args);
            case "remove" -> handleRemove(sender, args);
            default -> sender.sendMessage("§cunknown subcommand: " + args[0]);
        }
        return true;
    }

    private void handleList(CommandSender sender, String[] args) {
        int page = 1;
        if (args.length >= 2) {
            try { page = Math.max(1, Integer.parseInt(args[1])); }
            catch (NumberFormatException e) { sender.sendMessage("§cpage must be a number"); return; }
        }
        List<Shop> all = new ArrayList<>(plugin.getShopManager().snapshot().values());
        all.sort((a, b) -> a.id().toString().compareTo(b.id().toString()));
        int totalPages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page > totalPages) page = totalPages;
        sender.sendMessage("§ashops §7(page " + page + " / " + totalPages + ", " + all.size() + " total):");
        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, all.size());
        for (int i = start; i < end; i++) {
            Shop s = all.get(i);
            String shortId = s.id().toString().substring(0, 8);
            String ownerName;
            if (s.isAdmin()) {
                ownerName = "[server]";
            } else {
                OfflinePlayer op = Bukkit.getOfflinePlayer(s.owner());
                ownerName = op.getName() != null ? op.getName() : s.owner().toString().substring(0, 8);
            }
            Location loc = s.chest();
            sender.sendMessage(" §8• §f" + shortId + " §7" + s.type() + " §f" + s.itemSpec().getType().name().toLowerCase()
                    + " §7@ " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ()
                    + " §8(" + ownerName + ")");
        }
    }

    private void handleRemove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cusage: /shopadmin remove <uuidPrefix>");
            return;
        }
        String prefix = args[1].toLowerCase();
        Shop match = null;
        int matches = 0;
        for (Shop s : plugin.getShopManager().snapshot().values()) {
            if (s.id().toString().toLowerCase().startsWith(prefix)) {
                match = s;
                matches++;
            }
        }
        if (matches == 0) {
            sender.sendMessage("§cno shop matches that prefix");
            return;
        }
        if (matches > 1) {
            sender.sendMessage("§cprefix is ambiguous (" + matches + " matches) — give more chars");
            return;
        }
        UUID id = match.id();
        plugin.getShopManager().remove(id);
        plugin.getShopStorage().delete(id);
        sender.sendMessage("§aremoved shop §f" + id);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) return Arrays.asList("reload", "stats", "list", "remove");
        return Collections.emptyList();
    }
}
