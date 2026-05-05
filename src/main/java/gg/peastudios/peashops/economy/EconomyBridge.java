package gg.peastudios.peashops.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class EconomyBridge {

    private final Logger log;
    private Economy economy;

    public EconomyBridge(Logger log) {
        this.log = log;
    }

    public boolean hookup() {
        if (Bukkit.getServicesManager().getRegistration(Economy.class) == null) {
            log.warning("vault economy not found — peashops will refuse to handle trades");
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        this.economy = rsp.getProvider();
        log.info("vault economy bound: " + economy.getName());
        return true;
    }

    public boolean isReady() { return economy != null; }

    public double balance(OfflinePlayer player) {
        if (economy == null) return 0.0;
        return economy.getBalance(player);
    }

    public boolean has(OfflinePlayer player, double amount) {
        if (economy == null) return false;
        if (!economy.hasAccount(player)) return false;
        return economy.has(player, amount);
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        if (economy == null) return false;
        if (amount <= 0) return true;
        if (!ensureAccount(player, "withdraw")) return false;
        double before = economy.getBalance(player);
        EconomyResponse resp = economy.withdrawPlayer(player, amount);
        if (!resp.transactionSuccess()) {
            log.log(Level.WARNING, "withdraw refused by vault for {0} ({1}) amount={2}: {3}",
                    new Object[]{player.getName(), player.getUniqueId(), amount, resp.errorMessage});
            return false;
        }
        double after = economy.getBalance(player);
        // some vault providers round, hence the tolerance
        if (Math.abs((before - amount) - after) > 0.01) {
            log.log(Level.WARNING, "vault provider lied on withdraw — refunding {0} to {1} ({2})",
                    new Object[]{amount, player.getName(), player.getUniqueId()});
            economy.depositPlayer(player, amount);
            return false;
        }
        return true;
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        if (economy == null) return false;
        if (amount <= 0) return true;
        if (!ensureAccount(player, "deposit")) return false;
        EconomyResponse resp = economy.depositPlayer(player, amount);
        if (!resp.transactionSuccess()) {
            log.log(Level.WARNING, "deposit refused by vault for {0} ({1}) amount={2}: {3}",
                    new Object[]{player.getName(), player.getUniqueId(), amount, resp.errorMessage});
            return false;
        }
        return true;
    }

    // some providers (essx) return FAILURE for accounts that don't exist yet,
    // so we have to create explicitly before deposit/withdraw.
    private boolean ensureAccount(OfflinePlayer player, String op) {
        if (economy.hasAccount(player)) return true;
        if (economy.createPlayerAccount(player)) return true;
        log.log(Level.WARNING, "{0} failed: vault refused to create account for {1} ({2})",
                new Object[]{op, player.getName(), player.getUniqueId()});
        return false;
    }

    public boolean restoreBalance(OfflinePlayer player, double targetBalance) {
        if (economy == null) return false;
        double now = economy.getBalance(player);
        double delta = targetBalance - now;
        if (Math.abs(delta) < 0.01) return true;
        if (delta > 0) return deposit(player, delta);
        else return withdraw(player, -delta);
    }
}
