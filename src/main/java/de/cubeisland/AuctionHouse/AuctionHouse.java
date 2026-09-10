package de.cubeisland.AuctionHouse;

import de.cubeisland.AuctionHouse.Auction.Bidder;
import de.cubeisland.AuctionHouse.Commands.AddCommand;
import de.cubeisland.AuctionHouse.Commands.BidCommand;
import de.cubeisland.AuctionHouse.Commands.ConfirmCommand;
import de.cubeisland.AuctionHouse.Commands.GetItemsCommand;
import de.cubeisland.AuctionHouse.Commands.OpenMenuCommand;
import de.cubeisland.AuctionHouse.Commands.SellGuiCommand;
import de.cubeisland.AuctionHouse.Commands.SellInventoryGuiCommand;
import de.cubeisland.AuctionHouse.Commands.CategoriesGuiCommand;
import de.cubeisland.AuctionHouse.Commands.ItemsGuiCommand;
import de.cubeisland.AuctionHouse.Commands.ClaimGuiCommand;
import de.cubeisland.AuctionHouse.Commands.BuyingGuiCommand;
import de.cubeisland.AuctionHouse.Commands.ExpireGuiCommand;
import de.cubeisland.AuctionHouse.Commands.VersionGuiCommand;
import de.cubeisland.AuctionHouse.Commands.HelpCommand;
import de.cubeisland.AuctionHouse.Commands.InfoCommand;
import de.cubeisland.AuctionHouse.Commands.ListCommand;
import de.cubeisland.AuctionHouse.Commands.NotifyCommand;
import de.cubeisland.AuctionHouse.Commands.ReloadCommand;
import de.cubeisland.AuctionHouse.Commands.RedisCommand;
import de.cubeisland.AuctionHouse.Commands.RemoveCommand;
import de.cubeisland.AuctionHouse.Commands.SearchCommand;
import de.cubeisland.AuctionHouse.Commands.SubscribeCommand;
import de.cubeisland.AuctionHouse.Commands.UnSubscribeCommand;
import de.cubeisland.AuctionHouse.Commands.UndoBidCommand;
import de.cubeisland.AuctionHouse.Database.Database;
import de.cubeisland.libMinecraft.Translation;
import de.cubeisland.AuctionHouse.gui.MenuListener;
import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Server;
import org.bukkit.configuration.Configuration;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main Class
 */
public class AuctionHouse extends JavaPlugin
{
    private static AuctionHouse instance = null;
    private static Logger logger = null;
    public static boolean debugMode = false;
    private static boolean logMode = false;
    private static Translation translation;
    
    private Server server;
    private PluginManager pm;
    private AuctionHouseConfiguration config;
    private File dataFolder;
    private Economy economy = null;
    private Database database;
    private MenuSettings menuSettings;
//TODO später eigene AuktionsBox als Kiste mit separatem inventar 
//TODO flatfile mit angeboten
//TODO DatenBankNutzung schöner machen
//TODO ah rem last / l
    public AuctionHouse()
    {
        instance = this;
    }
    
    public static AuctionHouse getInstance()
    {
        return instance;
    }

    @Override
    public void onEnable()
    {
        logger = this.getLogger();
        this.server = this.getServer();
        this.pm = this.server.getPluginManager();
        this.dataFolder = this.getDataFolder();

        this.dataFolder.mkdirs();
        this.saveDefaultConfig();
        saveBundledResources();

        org.bukkit.configuration.file.FileConfiguration configuration = super.getConfig();
        configuration.options().copyDefaults(true);
        logMode = configuration.getBoolean("logging.pluginActivity", false);
        debugMode = configuration.getBoolean("logging.debug", configuration.getBoolean("debug", false));
        this.config = new AuctionHouseConfiguration(configuration);
        this.saveConfig();
        this.menuSettings = new MenuSettings(new File(this.getDataFolder(), "menus.yml"));

        this.economy = this.setupEconomy();
        
        translation = Translation.get(this.getDataFolder(), this.getClass(), config.auction_language);
        if (translation == null) translation = Translation.get(this.getDataFolder(), this.getClass(), "en");

        try
        {
            String dbType = config.auction_database_type == null ? "mysql" : config.auction_database_type.trim().toLowerCase(java.util.Locale.ROOT);
            if (!"mysql".equals(dbType) && !"sqlite".equals(dbType))
            {
                throw new IllegalStateException("Unsupported database type in config: " + config.auction_database_type + ". Supported types are: mysql, sqlite.");
            }

            database = new Database(dbType,
                                    this.getDataFolder(),
                                    config.auction_database_host,
                                    config.auction_database_port,
                                    config.auction_database_user,
                                    config.auction_database_pass,
                                    config.auction_database_name,
                                    config.auction_database_jdbcUrl,
                                    config.auction_database_autoCreateDatabase,
                                    config.auction_database_connectionParameters,
                                    config.auction_database_connectTimeoutMs,
                                    config.auction_database_socketTimeoutMs,
                                    config.auction_database_allowAutoPrefixedNames,
                                    config.auction_database_nameCandidates);
            database.loadDatabase();
            log("Database connection established. Active schema: " + database.getName());
            Manager.getInstance().removeOldAuctions();
            CrossServerSync.getInstance().start();
        }
        catch (IllegalStateException e)
        {
            error("Failed to initialize the database.", e);
            error("Open " + new File(this.getDataFolder(), "config.yml").getAbsolutePath() + " and check auction.database.host/port/user/pass/name.");
            error("Check auction.database.type/jdbcUrl/host/port/user/pass/name in config.yml.");
            error("This build now supports direct jdbcUrl overrides, configurable connection parameters, and database-name fallback candidates for stubborn shared-hosting MySQL setups.");
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        this.pm.registerEvents(new AuctionHouseListener(this), this);
        this.pm.registerEvents(new MenuListener(), this);
        
        BaseCommand baseCommand = new BaseCommand(this);
        baseCommand
            .registerSubCommand(new     OpenMenuCommand(baseCommand))
            .registerSubCommand(new         HelpCommand(baseCommand))
            .registerSubCommand(new      SellGuiCommand(baseCommand))
            .registerSubCommand(new SellInventoryGuiCommand(baseCommand))
            .registerSubCommand(new CategoriesGuiCommand(baseCommand))
            .registerSubCommand(new     ItemsGuiCommand(baseCommand))
            .registerSubCommand(new     ClaimGuiCommand(baseCommand))
            .registerSubCommand(new    BuyingGuiCommand(baseCommand))
            .registerSubCommand(new    ExpireGuiCommand(baseCommand))
            .registerSubCommand(new   VersionGuiCommand(baseCommand))
            .registerSubCommand(new          AddCommand(baseCommand))
            .registerSubCommand(new       RemoveCommand(baseCommand))
            .registerSubCommand(new          BidCommand(baseCommand))
            .registerSubCommand(new         InfoCommand(baseCommand))
            .registerSubCommand(new       SearchCommand(baseCommand))
            .registerSubCommand(new      UndoBidCommand(baseCommand))
            .registerSubCommand(new       NotifyCommand(baseCommand))
            .registerSubCommand(new     GetItemsCommand(baseCommand))
            .registerSubCommand(new    SubscribeCommand(baseCommand))
            .registerSubCommand(new  UnSubscribeCommand(baseCommand))
            .registerSubCommand(new         ListCommand(baseCommand))
            .registerSubCommand(new      ConfirmCommand(baseCommand))    
            .registerSubCommand(new       ReloadCommand(baseCommand))
            .registerSubCommand(new       RedisCommand(baseCommand)) 
        .setDefaultCommand("menu");
        this.getCommand("auctionhouse").setExecutor(baseCommand);
        this.getCommand("auctionhouse").setTabCompleter(baseCommand);
        
        AuctionTimer.getInstance().firstschedule();
    }
    

    private void saveBundledResources()
    {
        File languageDir = new File(this.dataFolder, "language");
        if (!languageDir.exists())
        {
            languageDir.mkdirs();
        }

        saveResourceIfMissing("language/de.ini");
        saveResourceIfMissing("language/en.ini");
        saveResourceIfMissing("language/ru.ini");
        saveResourceIfMissing("menus.yml");
    }

    private void saveResourceIfMissing(String resourcePath)
    {
        File out = new File(this.dataFolder, resourcePath);
        if (!out.exists())
        {
            if (this.getResource(resourcePath) == null)
            {
                this.getLogger().warning("Bundled resource not found in jar: " + resourcePath);
                return;
            }
            this.saveResource(resourcePath, false);
        }
    }

    @Override
    public void onDisable()
    {
        CrossServerSync.getInstance().stop();
        // Cancel pending sync/apply tasks first so nothing touches the database while it closes.
        this.getServer().getScheduler().cancelTasks(this);
        if (this.database != null)
        {
            // Bounded close: runs the blocking Connection#close on a daemon thread and
            // aborts the connection after 3s. This is what used to freeze the Server thread
            // for 40+ seconds on /plugman unload (Paper Watchdog dumps at Database.java:346).
            this.database.close(3000L);
        }
        this.database = null;
        this.economy = null;
        this.config = null;
        this.menuSettings = null;
        AuctionTimer.getInstance().stop();
        Bidder.getInstances().clear();
    }
    
    private Economy setupEconomy()
    {
        if (this.pm.getPlugin("Vault") != null)
        {
            RegisteredServiceProvider<Economy> rsp = this.server.getServicesManager().getRegistration(Economy.class);
            if (rsp != null)
            {
                Economy eco = rsp.getProvider();
                if (eco != null)
                {
                    return eco;
                }
            }
        }
        throw new IllegalStateException("Failed to initialize with Vault!");
    }
    
    public Economy getEconomy()
    {
        return this.economy;
    }
       
    public AuctionHouseConfiguration getConfiguration()
    {
        return this.config;
    }

    public MenuSettings getMenuSettings()
    {
        return this.menuSettings;
    }

    public static void log(String msg)
    {
        if (logMode)
        {
            logger.log(Level.INFO, msg);
        }
    }

    public static void error(String msg)
    {
        logger.log(Level.SEVERE, msg);
    }

    public static void error(String msg, Throwable t)
    {
        logger.log(Level.SEVERE, msg, t);
    }

    public static void debug(String msg)
    {
        if (debugMode)
        {
            logger.log(Level.INFO, "[debug] " + msg);
        }
    }
    
    public static String t(String key, Object... params)
    {
        return translation.translate(key, params).replace("AuctionBox", "Хранилище");
    }
    
    public Database getDB()
    {
        return this.database;
    }
}
