package de.cubeisland.AuctionHouse.Database;

import de.cubeisland.AuctionHouse.Auction.Auction;
import de.cubeisland.AuctionHouse.Auction.AuctionItem;
import de.cubeisland.AuctionHouse.Auction.Bid;
import de.cubeisland.AuctionHouse.Auction.Bidder;
import de.cubeisland.AuctionHouse.Auction.ServerBidder;
import de.cubeisland.AuctionHouse.AuctionHouse;
import de.cubeisland.AuctionHouse.Manager;
import de.cubeisland.AuctionHouse.Util;
import java.lang.reflect.Field;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.bukkit.inventory.ItemStack;

/**
 * Database backend for AuctionHousAqua.
 */
public class Database
{
    private final String host;
    private final int port;
    private final String user;
    private final String pass;
    private final String configuredName;
    private final boolean allowAutoPrefixedNames;
    private final List<String> databaseNameCandidates;
    private String activeName;
    private final String jdbcUrl;
    private final String extraParameters;
    private final boolean autoCreateDatabase;
    private final int connectTimeoutMs;
    private final int socketTimeoutMs;

    private Connection connection;

    public Database(String user, String pass, String name)
    {
        this("localhost", 3306, user, pass, name, null, true, "", 10000, 30000, true, new ArrayList<String>());
    }

    public Database(String host, short port, String user, String pass, String name)
    {
        this(host, (int) port, user, pass, name, null, true, "", 10000, 30000, true, new ArrayList<String>());
    }

    public Database(String host, int port, String user, String pass, String name,
                    String jdbcUrl, boolean autoCreateDatabase, String extraParameters,
                    int connectTimeoutMs, int socketTimeoutMs,
                    boolean allowAutoPrefixedNames, List<String> databaseNameCandidates)
    {
        loadDriver();
        this.host = safeTrim(host, "localhost");
        this.port = port <= 0 ? 3306 : port;
        this.user = safeTrim(user, "");
        this.pass = pass == null ? "" : pass;
        this.configuredName = safeTrim(name, "auctionhouse");
        this.activeName = this.configuredName;
        this.allowAutoPrefixedNames = allowAutoPrefixedNames;
        this.databaseNameCandidates = buildDatabaseNameCandidates(this.configuredName, databaseNameCandidates, user, allowAutoPrefixedNames);
        this.jdbcUrl = jdbcUrl == null ? "" : jdbcUrl.trim();
        this.autoCreateDatabase = autoCreateDatabase;
        this.extraParameters = normalizeExtraParameters(extraParameters);
        this.connectTimeoutMs = connectTimeoutMs <= 0 ? 10000 : connectTimeoutMs;
        this.socketTimeoutMs = socketTimeoutMs <= 0 ? 30000 : socketTimeoutMs;

        connect();
        setupStructure();
    }

    private static void loadDriver()
    {
        try
        {
            try
            {
                Class.forName("com.mysql.cj.jdbc.Driver");
            }
            catch (ClassNotFoundException ignored)
            {
                Class.forName("com.mysql.jdbc.Driver");
            }
        }
        catch (Throwable t)
        {
            throw new IllegalStateException("Couldn't find the MySQL driver! Put mysql-connector-j into the server or shade it into the plugin jar.", t);
        }
    }

    private static String safeTrim(String value, String fallback)
    {
        if (value == null)
        {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static String normalizeExtraParameters(String extraParameters)
    {
        if (extraParameters == null)
        {
            return "";
        }
        String trimmed = extraParameters.trim();
        if (trimmed.isEmpty())
        {
            return "";
        }
        while (trimmed.startsWith("?") || trimmed.startsWith("&"))
        {
            trimmed = trimmed.substring(1);
        }
        return trimmed;
    }

    private static List<String> buildDatabaseNameCandidates(String configuredName, List<String> explicitCandidates, String user, boolean allowAutoPrefixedNames)
    {
        List<String> candidates = new ArrayList<String>();
        addCandidate(candidates, configuredName);

        if (explicitCandidates != null)
        {
            for (String candidate : explicitCandidates)
            {
                addCandidate(candidates, candidate);
            }
        }

        if (allowAutoPrefixedNames)
        {
            String inferredPrefix = inferHostingPrefix(user);
            if (inferredPrefix != null && !configuredName.startsWith(inferredPrefix + "_"))
            {
                addCandidate(candidates, inferredPrefix + "_" + configuredName);
            }
        }

        return candidates;
    }

    private static void addCandidate(List<String> candidates, String candidate)
    {
        if (candidate == null)
        {
            return;
        }
        String trimmed = candidate.trim();
        if (trimmed.isEmpty())
        {
            return;
        }
        if (!candidates.contains(trimmed))
        {
            candidates.add(trimmed);
        }
    }

    private static String inferHostingPrefix(String user)
    {
        if (user == null)
        {
            return null;
        }
        String trimmed = user.trim();
        int underscore = trimmed.indexOf('_');
        if (underscore <= 0)
        {
            return null;
        }
        return trimmed.substring(0, underscore);
    }

    private synchronized void connect()
    {
        closeSilently();

        SQLException firstFailure = null;
        List<String> attemptedUrls = new ArrayList<String>();

        if (!this.jdbcUrl.isEmpty())
        {
            String finalUrl = appendParameters(this.jdbcUrl);
            attemptedUrls.add(maskCredentials(finalUrl));
            try
            {
                this.connection = DriverManager.getConnection(finalUrl, this.user, this.pass);
                this.activeName = this.configuredName;
                configureConnection(this.connection);
                return;
            }
            catch (SQLException e)
            {
                firstFailure = e;
            }
        }
        else
        {
            for (String candidateName : this.databaseNameCandidates)
            {
                String databaseUrl = buildDatabaseUrl(candidateName);
                attemptedUrls.add(maskCredentials(databaseUrl));
                try
                {
                    this.connection = DriverManager.getConnection(databaseUrl, this.user, this.pass);
                    this.activeName = candidateName;
                    configureConnection(this.connection);
                    return;
                }
                catch (SQLException e)
                {
                    if (firstFailure == null)
                    {
                        firstFailure = e;
                    }
                }

                if (this.autoCreateDatabase)
                {
                    tryCreateDatabase(candidateName, attemptedUrls);
                    try
                    {
                        this.connection = DriverManager.getConnection(databaseUrl, this.user, this.pass);
                        this.activeName = candidateName;
                        configureConnection(this.connection);
                        return;
                    }
                    catch (SQLException e)
                    {
                        if (firstFailure == null)
                        {
                            firstFailure = e;
                        }
                    }
                }
            }
        }

        StringBuilder message = new StringBuilder("Failed to connect to the database server [host=")
            .append(this.host)
            .append(", port=")
            .append(this.port)
            .append(", database=")
            .append(this.configuredName)
            .append(", user=")
            .append(this.user)
            .append("]");
        if (!attemptedUrls.isEmpty())
        {
            message.append(" using ").append(attemptedUrls);
        }
        throw new IllegalStateException(message.toString(), firstFailure);
    }

    private void tryCreateDatabase(String databaseName, List<String> attemptedUrls)
    {
        String serverUrl = buildServerUrl();
        attemptedUrls.add(maskCredentials(serverUrl));
        try (Connection serverConnection = DriverManager.getConnection(serverUrl, this.user, this.pass);
             Statement statement = serverConnection.createStatement())
        {
            statement.execute("CREATE DATABASE IF NOT EXISTS `" + databaseName.replace("`", "``") + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        catch (SQLException ignored)
        {
            // A lot of shared hosts do not grant CREATE DATABASE. We ignore this and retry the normal DB connection.
        }
    }

    private String buildServerUrl()
    {
        return appendParameters("jdbc:mysql://" + this.host + ":" + this.port + "/");
    }

    private String buildDatabaseUrl(String databaseName)
    {
        return appendParameters("jdbc:mysql://" + this.host + ":" + this.port + "/" + databaseName);
    }

    private String appendParameters(String baseUrl)
    {
        String separator = baseUrl.contains("?") ? "&" : "?";
        String defaultParameters = "useUnicode=true&characterEncoding=utf8&connectionCollation=utf8mb4_unicode_ci"
            + "&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
            + "&connectTimeout=" + this.connectTimeoutMs
            + "&socketTimeout=" + this.socketTimeoutMs
            + "&tcpKeepAlive=true";
        if (this.extraParameters.isEmpty())
        {
            return baseUrl + separator + defaultParameters;
        }
        return baseUrl + separator + defaultParameters + "&" + this.extraParameters;
    }

    private String maskCredentials(String url)
    {
        return url.replace(this.pass, "****");
    }

    private void configureConnection(Connection connection) throws SQLException
    {
        connection.setAutoCommit(true);
        try
        {
            connection.setCatalog(this.activeName);
        }
        catch (SQLException ignored)
        {
        }
    }

    private synchronized void ensureConnection()
    {
        try
        {
            if (this.connection == null || this.connection.isClosed() || !this.connection.isValid(2))
            {
                connect();
            }
        }
        catch (SQLException e)
        {
            connect();
        }
    }

    public void close()
    {
        closeSilently();
    }

    private synchronized void closeSilently()
    {
        if (this.connection != null)
        {
            try
            {
                this.connection.close();
            }
            catch (SQLException ignored)
            {
            }
            finally
            {
                this.connection = null;
            }
        }
    }

    private PreparedStatement createStatement(String query, Object... params) throws SQLException
    {
        ensureConnection();
        PreparedStatement statement = this.connection.prepareStatement(query);
        for (int i = 0; i < params.length; ++i)
        {
            statement.setObject(i + 1, params[i]);
        }
        return statement;
    }

    private void setupStructure()
    {
        this.exec("CREATE TABLE IF NOT EXISTS `bidder` ("
            + "`id` INT NOT NULL AUTO_INCREMENT,"
            + "`name` VARCHAR(64) NOT NULL,"
            + "`type` TINYINT(1) NOT NULL COMMENT 'is ServerBidder?',"
            + "`notify` SMALLINT NOT NULL,"
            + "PRIMARY KEY (`id`),"
            + "UNIQUE KEY `uq_bidder_name` (`name`)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci AUTO_INCREMENT=1;");

        this.exec("CREATE TABLE IF NOT EXISTS `auctions` ("
            + "`id` INT UNSIGNED NOT NULL,"
            + "`ownerid` INT NOT NULL,"
            + "`item` LONGTEXT NOT NULL,"
            + "`amount` INT NOT NULL,"
            + "`timestamp` TIMESTAMP NOT NULL,"
            + "PRIMARY KEY (`id`),"
            + "KEY `idx_auctions_ownerid` (`ownerid`),"
            + "CONSTRAINT `fk_auctions_ownerid` FOREIGN KEY (`ownerid`) REFERENCES `bidder` (`id`) ON DELETE CASCADE"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;");

        this.exec("CREATE TABLE IF NOT EXISTS `bids` ("
            + "`id` INT NOT NULL AUTO_INCREMENT,"
            + "`auctionid` INT UNSIGNED NOT NULL,"
            + "`bidderid` INT NOT NULL,"
            + "`amount` DOUBLE NOT NULL,"
            + "`timestamp` TIMESTAMP NOT NULL,"
            + "PRIMARY KEY (`id`),"
            + "KEY `idx_bids_auctionid` (`auctionid`),"
            + "KEY `idx_bids_bidderid` (`bidderid`),"
            + "CONSTRAINT `fk_bids_auctionid` FOREIGN KEY (`auctionid`) REFERENCES `auctions` (`id`) ON DELETE CASCADE,"
            + "CONSTRAINT `fk_bids_bidderid` FOREIGN KEY (`bidderid`) REFERENCES `bidder` (`id`) ON DELETE CASCADE"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci AUTO_INCREMENT=1;");

        this.exec("CREATE TABLE IF NOT EXISTS `auctionbox` ("
            + "`id` INT NOT NULL AUTO_INCREMENT,"
            + "`bidderid` INT NOT NULL,"
            + "`item` LONGTEXT NOT NULL COMMENT 'Serialized ItemStack',"
            + "`amount` INT NOT NULL,"
            + "`price` DECIMAL(11,2) NOT NULL,"
            + "`timestamp` TIMESTAMP NOT NULL,"
            + "`ownerid` INT NOT NULL COMMENT 'Bidder who started auction',"
            + "PRIMARY KEY (`id`),"
            + "KEY `idx_auctionbox_bidderid` (`bidderid`),"
            + "CONSTRAINT `fk_auctionbox_bidderid` FOREIGN KEY (`bidderid`) REFERENCES `bidder` (`id`) ON DELETE CASCADE"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci AUTO_INCREMENT=1;");

        this.exec("CREATE TABLE IF NOT EXISTS `subscription` ("
            + "`id` INT NOT NULL AUTO_INCREMENT,"
            + "`bidderid` INT NOT NULL,"
            + "`auctionid` INT UNSIGNED DEFAULT NULL,"
            + "`type` TINYINT(1) NOT NULL,"
            + "`item` LONGTEXT DEFAULT NULL COMMENT 'Serialized ItemStack',"
            + "PRIMARY KEY (`id`),"
            + "KEY `idx_subscription_bidderid` (`bidderid`),"
            + "KEY `idx_subscription_auctionid` (`auctionid`),"
            + "CONSTRAINT `fk_subscription_bidderid` FOREIGN KEY (`bidderid`) REFERENCES `bidder` (`id`) ON DELETE CASCADE,"
            + "CONSTRAINT `fk_subscription_auctionid` FOREIGN KEY (`auctionid`) REFERENCES `auctions` (`id`) ON DELETE CASCADE"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci AUTO_INCREMENT=1;");

        this.exec("CREATE TABLE IF NOT EXISTS `price` ("
            + "`id` INT NOT NULL AUTO_INCREMENT,"
            + "`item` LONGTEXT DEFAULT NULL COMMENT 'Serialized ItemStack',"
            + "`price` DECIMAL(11,2) NOT NULL,"
            + "`amount` INT NOT NULL,"
            + "PRIMARY KEY (`id`)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci AUTO_INCREMENT=1;");
    }

    public String getHost()
    {
        return this.host;
    }

    public int getPort()
    {
        return this.port;
    }

    public String getUser()
    {
        return this.user;
    }

    public String getPass()
    {
        return this.pass;
    }

    public String getName()
    {
        return this.activeName;
    }

    public ResultSet query(String query, Object... params)
    {
        try
        {
            return createStatement(query, params).executeQuery();
        }
        catch (SQLException e)
        {
            throw new IllegalStateException("Failed to execute query: " + query, e);
        }
    }

    public int execUpdate(String query, Object... params)
    {
        try
        {
            return createStatement(query, params).executeUpdate();
        }
        catch (SQLException e)
        {
            throw new IllegalStateException("Failed to execute query: " + query, e);
        }
    }

    public boolean exec(String query, Object... params)
    {
        try
        {
            return createStatement(query, params).execute();
        }
        catch (SQLException e)
        {
            throw new IllegalStateException("Failed to execute query: " + query, e);
        }
    }

    public synchronized void loadDatabase()
    {
        AuctionHouse.debug("Start loading database...");

        try
        {
            Manager.getInstance().clearForDatabaseReload();
            Bidder.getInstances().clear();
            ServerBidder.resetInstance();

            Map<Integer, String> bidderNames = loadBiddersIntoMemory();
            AuctionHouse.debug("All bidders loaded!");

            try (ResultSet auctions = this.query("SELECT * FROM `auctions` ORDER BY `id` ASC"))
            {
                while (auctions.next())
                {
                    int id = auctions.getInt("id");
                    int ownerId = auctions.getInt("ownerid");
                    ItemStack item = Util.convertItem(auctions.getString("item"), auctions.getInt("amount"));
                    Bidder owner = Bidder.getInstance(ownerId, bidderName(bidderNames, ownerId));
                    long auctionEnd = auctions.getTimestamp("timestamp").getTime();
                    Auction auction = new Auction(id, item, owner, auctionEnd);
                    Manager.getInstance().addAuction(auction);

                    try (ResultSet bidset = this.query("SELECT * FROM `bids` WHERE `auctionid` = ? ORDER BY `timestamp` ASC", id))
                    {
                        while (bidset.next())
                        {
                            int bidderId = bidset.getInt("bidderid");
                            Bid bid = new Bid(
                                bidset.getInt("id"),
                                bidderId,
                                bidderName(bidderNames, bidderId),
                                bidset.getDouble("amount"),
                                bidset.getTimestamp("timestamp")
                            );
                            Auction loaded = Manager.getInstance().getAuction(auction.getId());
                            if (loaded != null)
                            {
                                loaded.getBids().push(bid);
                            }
                        }
                    }
                }
            }
            AuctionHouse.debug("All auctions loaded!");

            try (ResultSet subset = this.query("SELECT * FROM `subscription`"))
            {
                while (subset.next())
                {
                    int bidderId = subset.getInt("bidderid");
                    Bidder bidder = Bidder.getInstance(bidderId, bidderName(bidderNames, bidderId));
                    if (subset.getInt("type") == 1)
                    {
                        bidder.addDataBaseSub(subset.getInt("auctionid"));
                    }
                    else
                    {
                        bidder.addDataBaseSub(Util.convertItem(subset.getString("item")));
                    }
                }
            }
            AuctionHouse.debug("All subscriptions loaded!");

            try (ResultSet itemset = this.query("SELECT * FROM `auctionbox` ORDER BY `timestamp` ASC"))
            {
                while (itemset.next())
                {
                    int bidderId = itemset.getInt("bidderid");
                    int ownerId = itemset.getInt("ownerid");
                    Bidder bidder = Bidder.getInstance(bidderId, bidderName(bidderNames, bidderId));
                    bidder.getBox().getItemList().add(
                        new AuctionItem(
                            bidder,
                            Util.convertItem(itemset.getString("item"), itemset.getInt("amount")),
                            itemset.getTimestamp("timestamp"),
                            bidderName(bidderNames, ownerId),
                            itemset.getDouble("price"),
                            itemset.getInt("id")
                        )
                    );
                }
            }
            AuctionHouse.debug("All auctionboxes loaded!");

            try (ResultSet priceset = this.query("SELECT * FROM `price`"))
            {
                while (priceset.next())
                {
                    Manager.getInstance().setPrice(Util.convertItem(priceset.getString("item")), priceset.getDouble("price"), priceset.getInt("amount"));
                }
            }
            AuctionHouse.debug("All average prices loaded!");
            AuctionHouse.log("Database loaded successfully");
        }
        catch (SQLException ex)
        {
            throw new IllegalStateException("Error while loading the database!", ex);
        }
    }

    public synchronized boolean loadAuctionById(int auctionId)
    {
        if (auctionId <= 0)
        {
            return false;
        }
        try
        {
            Map<Integer, String> bidderNames = loadBidderNameMap();
            try (ResultSet auctions = this.query("SELECT * FROM `auctions` WHERE `id`=? LIMIT 1", auctionId))
            {
                if (!auctions.next())
                {
                    removeAuctionFromMemory(auctionId);
                    return false;
                }

                removeAuctionFromMemory(auctionId);

                int ownerId = auctions.getInt("ownerid");
                ItemStack item = Util.convertItem(auctions.getString("item"), auctions.getInt("amount"));
                Bidder owner = Bidder.getInstance(ownerId, bidderName(bidderNames, ownerId));
                Auction auction = new Auction(auctionId, item, owner, auctions.getTimestamp("timestamp").getTime());
                Manager.getInstance().addAuction(auction);

                try (ResultSet bidset = this.query("SELECT * FROM `bids` WHERE `auctionid` = ? ORDER BY `timestamp` ASC", auctionId))
                {
                    while (bidset.next())
                    {
                        int bidderId = bidset.getInt("bidderid");
                        auction.getBids().push(new Bid(
                            bidset.getInt("id"),
                            bidderId,
                            bidderName(bidderNames, bidderId),
                            bidset.getDouble("amount"),
                            bidset.getTimestamp("timestamp")
                        ));
                    }
                }
                AuctionHouse.debug("Auction #" + auctionId + " refreshed from MySQL after Redis event.");
                return true;
            }
        }
        catch (SQLException ex)
        {
            throw new IllegalStateException("Error while loading auction #" + auctionId + " from the database!", ex);
        }
    }

    public synchronized void removeAuctionFromMemory(int auctionId)
    {
        Manager.getInstance().removeLocalAuction(auctionId);
    }

    public synchronized void reloadAuctionBoxForBidder(int bidderId)
    {
        if (bidderId <= 0)
        {
            return;
        }
        try
        {
            Map<Integer, String> bidderNames = loadBidderNameMap();
            String name = bidderName(bidderNames, bidderId);
            if (name == null)
            {
                return;
            }
            Bidder bidder = Bidder.getInstance(bidderId, name);
            bidder.getBox().getItemList().clear();
            try (ResultSet itemset = this.query("SELECT * FROM `auctionbox` WHERE `bidderid`=? ORDER BY `timestamp` ASC", bidderId))
            {
                while (itemset.next())
                {
                    int ownerId = itemset.getInt("ownerid");
                    bidder.getBox().getItemList().add(
                        new AuctionItem(
                            bidder,
                            Util.convertItem(itemset.getString("item"), itemset.getInt("amount")),
                            itemset.getTimestamp("timestamp"),
                            bidderName(bidderNames, ownerId),
                            itemset.getDouble("price"),
                            itemset.getInt("id")
                        )
                    );
                }
            }
            AuctionHouse.debug("Auction box for bidder #" + bidderId + " refreshed from MySQL after Redis event.");
        }
        catch (SQLException ex)
        {
            throw new IllegalStateException("Error while loading auction box for bidder #" + bidderId + " from the database!", ex);
        }
    }

    public boolean auctionExists(int auctionId)
    {
        try (ResultSet set = this.query("SELECT `id` FROM `auctions` WHERE `id`=? LIMIT 1", auctionId))
        {
            return set.next();
        }
        catch (SQLException ex)
        {
            throw new IllegalStateException("Error while checking auction #" + auctionId + " existence!", ex);
        }
    }

    private Map<Integer, String> loadBiddersIntoMemory() throws SQLException
    {
        Map<Integer, String> bidderNames = new HashMap<Integer, String>();
        try (ResultSet bidderset = this.query("SELECT * FROM `bidder`"))
        {
            while (bidderset.next())
            {
                int id = bidderset.getInt("id");
                String name = bidderset.getString("name");
                bidderNames.put(id, name);
                Bidder bidder = Bidder.getInstance(id, name);
                bidder.resetNotifyState(bidderset.getByte("notify"));
            }
        }
        return bidderNames;
    }

    private Map<Integer, String> loadBidderNameMap() throws SQLException
    {
        Map<Integer, String> bidderNames = new HashMap<Integer, String>();
        try (ResultSet bidderset = this.query("SELECT `id`, `name` FROM `bidder`"))
        {
            while (bidderset.next())
            {
                bidderNames.put(bidderset.getInt("id"), bidderset.getString("name"));
            }
        }
        return bidderNames;
    }

    private String bidderName(Map<Integer, String> names, int id)
    {
        String name = names.get(id);
        if (name != null)
        {
            return name;
        }
        return getBidderString(id);
    }

    private String getBidderString(int id)
    {
        try (ResultSet set = this.query("SELECT * from `bidder` where `id`=? LIMIT 1;", id))
        {
            if (set.next())
            {
                return set.getString("name");
            }
        }
        catch (SQLException ignored)
        {
        }
        return null;
    }

    public Database updateEntity(DatabaseEntity entity) throws SQLException
    {
        String idName = null;
        Object idValue = null;
        Map<String, Object> fields = new HashMap<String, Object>();

        String propName;
        for (Field field : entity.getClass().getDeclaredFields())
        {
            try
            {
                if (field.isAnnotationPresent(EntityIdentifier.class))
                {
                    field.setAccessible(true);
                    idName = field.getAnnotation(EntityIdentifier.class).name();
                    if ("".equals(idName))
                    {
                        idName = field.getName();
                    }
                    idValue = field.get(entity);
                }
                else if (field.isAnnotationPresent(EntityProperty.class))
                {
                    field.setAccessible(true);
                    propName = field.getAnnotation(EntityProperty.class).name();
                    if ("".equals(propName))
                    {
                        propName = field.getName();
                    }
                    fields.put(propName, field.get(entity));
                }
            }
            catch (IllegalAccessException e)
            {}
        }

        if (idName == null)
        {
            throw new IllegalArgumentException("The given entity does not contain an identifier!");
        }

        this.update(new String[] {entity.getTable()}, fields, new Condition(quoteName(idName) + " = ?", idValue), 1, -1);

        return this;
    }

    public int update(String[] tables, Map<String, Object> fields) throws SQLException
    {
        return this.update(tables, fields, null, 0, -1);
    }

    public int update(String[] tables, Map<String, Object> fields, Condition condition) throws SQLException
    {
        return this.update(tables, fields, condition, 0, -1);
    }

    public int update(String[] tables, Map<String, Object> fields, Condition condition, int limit) throws SQLException
    {
        return this.update(tables, fields, condition, limit, -1);
    }

    public int update(String[] tables, Map<String, Object> fields, Condition condition, int limit, int offset) throws SQLException
    {
        if (fields == null || fields.size() < 1)
        {
            return 0;
        }
        Iterator<String> fieldNames = fields.keySet().iterator();
        List<Object> params = new ArrayList<Object>(fields.values());

        StringBuilder query = new StringBuilder("UPDATE ").append(generateTableList(tables)).append(" SET ");
        query.append(fieldNames.next()).append(" = ?");
        while (fieldNames.hasNext())
        {
            query.append(", ").append(fieldNames.next()).append(" = ?");
        }

        if (condition != null)
        {
            query.append(" WHERE ").append(condition.getStatement());
            for (Object value : condition.getValues())
            {
                params.add(value);
            }
        }
        if (limit > 0)
        {
            query.append(" LIMIT ");
            if (offset > -1)
            {
                query.append(offset).append(", ");
            }
            query.append(limit);
        }

        return this.createStatement(query.toString(), params.toArray()).executeUpdate();
    }

    public int insert(DatabaseEntity entity) throws SQLException
    {
        Map<String, Object> fields = new HashMap<String, Object>();
        String propName;
        for (Field field : entity.getClass().getDeclaredFields())
        {
            if (field.isAnnotationPresent(EntityProperty.class))
            {
                try
                {
                    field.setAccessible(true);
                    propName = field.getAnnotation(EntityProperty.class).name();
                    if ("".equals(propName))
                    {
                        propName = field.getName();
                    }
                    fields.put(propName, field.get(entity));
                }
                catch (IllegalAccessException e)
                {}
            }
        }
        return this.insert(entity.getTable(), fields);
    }

    public int insert(String table, Map<String, Object> fields) throws SQLException
    {
        if (fields == null || fields.size() < 1)
        {
            return 0;
        }
        Iterator<String> fieldNames = fields.keySet().iterator();
        List<Object> params = new ArrayList<Object>(fields.values());

        StringBuilder query = new StringBuilder("INSERT INTO ").append(quoteName(table)).append(" (")
            .append(fieldNames.next());
        StringBuilder vals = new StringBuilder(" VALUES (?)");
        while (fieldNames.hasNext())
        {
            query.append(", ").append(fieldNames.next());
            vals.insert(vals.length() - 1, ", ?");
        }
        query.append(")").append(vals);

        return this.createStatement(query.toString(), params.toArray()).executeUpdate();
    }

    public int delete(String[] tables, Condition condition) throws SQLException
    {
        return this.delete(tables, condition, 0, -1);
    }

    public int delete(String[] tables, Condition condition, int limit) throws SQLException
    {
        return this.delete(tables, condition, limit, -1);
    }

    public int delete(String[] tables, Condition condition, int limit, int offset) throws SQLException
    {
        StringBuilder query = new StringBuilder("DELETE FROM ").append(generateTableList(tables));
        if (condition != null)
        {
            query.append(" WHERE ").append(condition.getStatement());
        }
        if (limit > 0)
        {
            query.append(" LIMIT ");
            if (offset > -1)
            {
                query.append(offset).append(", ");
            }
            query.append(limit);
        }
        return this.createStatement(query.toString(), condition == null ? new Object[0] : condition.getValues()).executeUpdate();
    }

    public ResultSet select(String[] fields, String[] tables) throws SQLException
    {
        return this.select(fields, tables, null, 0, -1);
    }

    public ResultSet select(String[] fields, String[] tables, Condition condition) throws SQLException
    {
        return this.select(fields, tables, condition, 0, -1);
    }

    public ResultSet select(String[] fields, String[] tables, Condition condition, int limit) throws SQLException
    {
        return this.select(fields, tables, condition, limit, -1);
    }

    public ResultSet select(String[] fields, String[] tables, Condition condition, int limit, int offset) throws SQLException
    {
        StringBuilder query = new StringBuilder("SELECT ").append(generateFieldList(fields));
        query.append(" FROM ").append(generateTableList(tables));
        if (condition != null)
        {
            query.append(" WHERE ").append(condition.getStatement());
        }
        if (limit > 0)
        {
            query.append(" LIMIT ");
            if (offset > -1)
            {
                query.append(offset).append(", ");
            }
            query.append(limit);
        }

        return this.createStatement(query.toString(), condition == null ? new Object[0] : condition.getValues()).executeQuery();
    }

    private String generateFieldList(String[] fields)
    {
        if (fields == null || fields.length < 1)
        {
            return "*";
        }
        StringBuilder builder = new StringBuilder(quoteName(fields[0]));
        for (int i = 1; i < fields.length; i++)
        {
            builder.append(", ").append(quoteName(fields[i]));
        }
        return builder.toString();
    }

    private String generateTableList(String[] tables)
    {
        if (tables == null || tables.length < 1)
        {
            throw new IllegalArgumentException("There has to be at least one table!");
        }
        StringBuilder builder = new StringBuilder(quoteName(tables[0]));
        for (int i = 1; i < tables.length; i++)
        {
            builder.append(", ").append(quoteName(tables[i]));
        }
        return builder.toString();
    }

    private static String quoteName(String name)
    {
        return "`" + Objects.requireNonNull(name, "name") + "`";
    }
}
