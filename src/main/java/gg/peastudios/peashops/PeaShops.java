package gg.peastudios.peashops;

import gg.peastudios.peashops.command.ShopAdminCommand;
import gg.peastudios.peashops.command.ShopCommand;
import gg.peastudios.peashops.economy.EconomyBridge;
import gg.peastudios.peashops.gui.CreationFlow;
import gg.peastudios.peashops.gui.FindGUI;
import gg.peastudios.peashops.gui.InfoGUI;
import gg.peastudios.peashops.listener.HopperGuardListener;
import gg.peastudios.peashops.listener.ShopProtectionListener;
import gg.peastudios.peashops.listener.SignCreationListener;
import gg.peastudios.peashops.listener.SignInteractListener;
import gg.peastudios.peashops.shop.ShopManager;
import gg.peastudios.peashops.shop.ShopStorage;
import gg.peastudios.peashops.transaction.TransactionExecutor;
import gg.peastudios.peashops.transaction.TransactionLock;
import gg.peastudios.peashops.util.MessageUtil;
import gg.peastudios.peashops.util.SignRefresher;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

// PeaShops main plugin class.
//
// 1.0 — basic trade flow live. sign creation, right-click trade, persistence,
// shop protection. hopper auto-restock + linked networks + dynamic pricing
// + GUI ship in subsequent 1.x.
public final class PeaShops extends JavaPlugin {

    private static PeaShops instance;

    private MessageUtil messages;
    private EconomyBridge economyBridge;
    private TransactionLock transactionLock;
    private ShopManager shopManager;
    private ShopStorage shopStorage;
    private TransactionExecutor transactionExecutor;
    private CreationFlow creationFlow;
    private InfoGUI infoGUI;
    private FindGUI findGUI;
    private SignRefresher signRefresher;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        this.messages = new MessageUtil(this);

        this.economyBridge = new EconomyBridge(getLogger());
        if (!economyBridge.hookup()) {
            getLogger().warning("running without vault — trades disabled until vault is loaded and /shop reload is run");
        }

        this.transactionLock = new TransactionLock();
        this.shopManager = new ShopManager(this);
        this.shopStorage = new ShopStorage(this);
        this.transactionExecutor = new TransactionExecutor(this);

        int loaded = shopStorage.loadAll();
        if (loaded > 0) getLogger().info("loaded " + loaded + " shop(s) from disk");

        this.creationFlow = new CreationFlow(this);
        this.infoGUI = new InfoGUI(this);
        this.findGUI = new FindGUI(this);
        this.signRefresher = new SignRefresher(this);
        getServer().getPluginManager().registerEvents(new SignCreationListener(this), this);
        getServer().getPluginManager().registerEvents(new SignInteractListener(this), this);
        getServer().getPluginManager().registerEvents(new HopperGuardListener(this), this);
        getServer().getPluginManager().registerEvents(new ShopProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(creationFlow, this);
        getServer().getPluginManager().registerEvents(infoGUI, this);
        getServer().getPluginManager().registerEvents(findGUI, this);

        PluginCommand shop = getCommand("shop");
        if (shop != null) {
            ShopCommand handler = new ShopCommand(this);
            shop.setExecutor(handler);
            shop.setTabCompleter(handler);
        }
        PluginCommand admin = getCommand("shopadmin");
        if (admin != null) {
            ShopAdminCommand handler = new ShopAdminCommand(this);
            admin.setExecutor(handler);
            admin.setTabCompleter(handler);
        }

        getLogger().info("peashops " + getDescription().getVersion() + " up — " + shopManager.total() + " shops");
    }

    @Override
    public void onDisable() {
        // wait up to 5s for in-flight trades to finish before flushing state.
        // /reload safety: if a player click triggered a trade in the same
        // tick as the reload, ripping state from under it dupes items.
        if (transactionExecutor != null) {
            long deadline = System.currentTimeMillis() + 5000;
            while (transactionExecutor.inFlightCount() > 0 && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            int leaked = transactionExecutor.inFlightCount();
            if (leaked > 0) {
                getLogger().warning("disable proceeded with " + leaked + " transaction(s) still in flight after 5s");
            }
        }
        if (shopStorage != null) {
            shopStorage.saveAll();
        }
        instance = null;
        getLogger().info("peashops down");
    }

    public static PeaShops get() { return instance; }
    public MessageUtil getMessages() { return messages; }
    public EconomyBridge getEconomyBridge() { return economyBridge; }
    public TransactionLock getTransactionLock() { return transactionLock; }
    public ShopManager getShopManager() { return shopManager; }
    public ShopStorage getShopStorage() { return shopStorage; }
    public TransactionExecutor getTransactionExecutor() { return transactionExecutor; }
    public CreationFlow getCreationFlow() { return creationFlow; }
    public InfoGUI getInfoGUI() { return infoGUI; }
    public FindGUI getFindGUI() { return findGUI; }
    public SignRefresher getSignRefresher() { return signRefresher; }
}
