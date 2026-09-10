package de.cubeisland.AuctionHouse;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.Configuration;
import org.bukkit.inventory.ItemStack;

/**
 * Loads the configuration file.
 */
public class AuctionHouseConfiguration
{
    public final long auction_undoTime;
    public final int auction_maxAuctions_overall;
    public final int auction_maxAuctions_player;
    public final boolean auction_maxAuctions_opIgnore;
    public final long auction_maxLength;
    public final boolean auction_opCanCheat;
    public final List<ItemStack> auction_blacklist;
    public final String auction_timeFormat;
    public final long auction_standardLength;
    public final List<Long> auction_notifyTime;
    public final int auction_punish;
    public final int auction_itemBoxLength;
    public final int auction_comission;
    public final String auction_language;
    public final boolean auction_confirmID;
    public final long auction_removeTime;

    public final String auction_database_host;
    public final int auction_database_port;
    public final String auction_database_user;
    public final String auction_database_pass;
    public final String auction_database_name;
    public final String auction_database_type;
    public final String auction_database_jdbcUrl;
    public final boolean auction_database_autoCreateDatabase;
    public final String auction_database_connectionParameters;
    public final int auction_database_connectTimeoutMs;
    public final int auction_database_socketTimeoutMs;
    public final boolean auction_database_allowAutoPrefixedNames;
    public final List<String> auction_database_nameCandidates;
    public final int auction_database_syncIntervalSeconds;

    public final boolean redis_enabled;
    public final String redis_channel_prefix;
    public final String redis_channel_main;
    public final String redis_channel_inventory;
    public final String redis_channel_transaction;
    public final String redis_host;
    public final int redis_port;
    public final String redis_password;
    public final int redis_database;
    public final int redis_timeoutMs;
    public final int redis_lockTtlMs;
    public final int redis_pool_maxTotal;
    public final int redis_pool_maxIdle;
    public final int redis_pool_minIdle;

    public AuctionHouseConfiguration(Configuration config)
    {
        this.auction_maxAuctions_player = config.getInt("auction.maxAuctions.player");
        this.auction_maxAuctions_opIgnore = config.getBoolean("auction.maxAuctions.opIgnore");
        this.auction_maxAuctions_overall = config.getInt("auction.maxAuctions.overall");
        this.auction_opCanCheat = config.getBoolean("auction.opCanCheat");
        this.auction_timeFormat = config.getString("auction.timeFormat");
        this.auction_punish = config.getInt("auction.punish");
        this.auction_itemBoxLength = config.getInt("auction.itemBoxLength");
        this.auction_comission = config.getInt("auction.comission");
        this.auction_language = config.getString("auction.language");
        this.auction_confirmID = config.getBoolean("auction.confirmID");

        this.auction_database_type = config.getString("auction.database.type", "mysql");
        this.auction_database_host = config.getString("auction.database.host", "localhost");
        this.auction_database_port = config.getInt("auction.database.port", 3306);
        this.auction_database_user = config.getString("auction.database.user", "auctionhouse");
        this.auction_database_pass = config.getString("auction.database.pass", "");
        this.auction_database_name = config.getString("auction.database.name", "auctionhouse");
        this.auction_database_jdbcUrl = config.getString("auction.database.jdbcUrl", "");
        this.auction_database_autoCreateDatabase = config.getBoolean("auction.database.autoCreateDatabase", true);
        this.auction_database_connectionParameters = config.getString("auction.database.connectionParameters", "");
        this.auction_database_connectTimeoutMs = config.getInt("auction.database.connectTimeoutMs", 10000);
        this.auction_database_socketTimeoutMs = config.getInt("auction.database.socketTimeoutMs", 30000);
        this.auction_database_allowAutoPrefixedNames = config.getBoolean("auction.database.allowAutoPrefixedNames", true);
        this.auction_database_nameCandidates = config.getStringList("auction.database.nameCandidates");
        this.auction_database_syncIntervalSeconds = config.getInt("auction.database.syncIntervalSeconds", 0);

        this.redis_enabled = config.getBoolean("redis.enabled", true);
        this.redis_channel_prefix = config.getString("redis.channel.prefix", "auctionhouse:");
        this.redis_channel_main = config.getString("redis.channel.main", this.redis_channel_prefix + "messaging");
        this.redis_channel_inventory = config.getString("redis.channel.inventory", this.redis_channel_prefix + "inventories");
        this.redis_channel_transaction = config.getString("redis.channel.transaction", this.redis_channel_prefix + "transactions");
        this.redis_host = config.getString("redis.host", "localhost");
        this.redis_port = config.getInt("redis.port", 6379);
        this.redis_password = config.getString("redis.password", "");
        this.redis_database = config.getInt("redis.database", 0);
        this.redis_timeoutMs = config.getInt("redis.timeoutMs", 2000);
        this.redis_lockTtlMs = config.getInt("redis.lockTtlMs", 15000);
        this.redis_pool_maxTotal = config.getInt("redis.poolConfig.maxTotal", 128);
        this.redis_pool_maxIdle = config.getInt("redis.poolConfig.maxIdle", 128);
        this.redis_pool_minIdle = config.getInt("redis.poolConfig.minIdle", 16);

        this.auction_undoTime = Util.convertTimeToMillis(config.getString("auction.undoTime"));
        this.auction_removeTime = Util.convertTimeToMillis(config.getString("auction.removeTime"));
        this.auction_maxLength = Util.convertTimeToMillis(config.getString("auction.maxLength"));
        this.auction_standardLength = Util.convertTimeToMillis(config.getString("auction.standardLength"));

        this.auction_notifyTime = this.convertList(config.getStringList("auction.notifyTime"));
        this.auction_blacklist = getItemList(config.getStringList("auction.blacklist"));
    }

    private List<Long> convertList(List<String> str)
    {
        List<Long> list = new ArrayList<Long>();
        for (String value : str)
        {
            list.add(Util.convertTimeToMillis(value));
        }
        return list;
    }

    private List<ItemStack> getItemList(List<String> str)
    {
        List<ItemStack> out = new ArrayList<ItemStack>();
        for (String raw : str)
        {
            if (raw == null || raw.trim().isEmpty())
            {
                continue;
            }

            String materialName = raw.trim();
            int separator = materialName.indexOf(':');
            if (separator >= 0)
            {
                materialName = materialName.substring(0, separator);
            }

            Material material = Material.matchMaterial(materialName);
            if (material != null)
            {
                out.add(new ItemStack(material, 1));
                continue;
            }

            try
            {
                int legacyId = Integer.parseInt(materialName);
                AuctionHouse.debug("Ignoring legacy blacklist id '" + legacyId + "' on 1.21.8. Use material names instead.");
            }
            catch (NumberFormatException ignored)
            {
            }
        }
        return out;
    }
}
