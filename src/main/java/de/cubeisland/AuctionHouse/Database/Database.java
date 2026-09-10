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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bukkit.inventory.ItemStack;

/**
 * Database backend for AuctionHousAqua.
 */
public class Database
{
    /** After a failed connect attempt, fail fast for a while instead of stalling callers with connect timeouts. */
    private static final long CONNECT_BREAKER_MS = 15000L;

    private final String type;
    private final java.io.File dataFolder;
    private final String host;
    private final int port;
    private final String user;
    private final String pass;
    private final String configuredName;
    private final boolean allowAutoPrefixedNames;
    private final List<String> databaseNameCandidates;
    private volatile String activeName;
    private final String jdbcUrl;
    private final String extraParameters;
    private final boolean autoCreateDatabase;
    private final int connectTimeoutMs;
    private final int socketTimeoutMs;

    private Connection connection;

    /**
     * Timestamp of the last successful write executed through this Database.
     * Snapshots fetched asynchronously are discarded when a local write happened while
     * they were being fetched, so a full reload can never resurrect rows that were
     * just sold or removed on this server.
     */
    private volatile long lastWriteAt = 0L;

    /**
     * When set to a future timestamp, ensureConnection() fails fast without trying to
     * reconnect. A single failed connect attempt costs several connectTimeoutMs (one per
     * database-name candidate); repeating that on the Server thread for every menu click
     * was one of the reported freeze causes while MySQL was unreachable.
     */
    private volatile long connectBreakerUntil = 0L;

    public Database(String user, String pass, String name)
    {
        this("mysql", null, "localhost", 3306, user, pass, name, null, true, "", 10000, 30000, true, new ArrayList<String>());
    }

    public Database(String host, short port, String user, String pass, String name)
    {
        this("mysql", null, host, (int) port, user, pass, name, null, true, "", 10000, 30000, true, new ArrayList<String>());
    }

    public Database(String host, int port, String user, String pass, String name,
                    String jdbcUrl, boolean autoCreateDatabase, String extraParameters,
                    int connectTimeoutMs, int socketTimeoutMs,
                    boolean allowAutoPrefixedNames, List<String> databaseNameCandidates)
    {
        this("mysql", null, host, port, user, pass, name, jdbcUrl, autoCreateDatabase, extraParameters, connectTimeoutMs, socketTimeoutMs, allowAutoPrefixedNames, databaseNameCandidates);
    }

    public Database(String type, java.io.File dataFolder,
                    String host, int port, String user, String pass, String name,
                    String jdbcUrl, boolean autoCreateDatabase, String extraParameters,
                    int connectTimeoutMs, int socketTimeoutMs,
                    boolean allowAutoPrefixedNames, List<String> databaseNameCandidates)
    {
        this.type = (type == null || type.trim().isEmpty()) ? "mysql" : type.trim().toLowerCase(Locale.ROOT);
        this.dataFolder = dataFolder;
        loadDriver(this.type);
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

    public boolean isSqlite()
    {
        return "sqlite".equalsIgnoreCase(this.type);
    }

    private static void loadDriver(String type)
    {
        if ("sqlite".equalsIgnoreCase(type))
        {
            try
            {
                Class.forName("org.sqlite.JDBC");
            }
            catch (Throwable t)
            {
                throw new IllegalStateException("Couldn't find the SQLite driver! Ensure org.sqlite.JDBC is available in the server runtime.", t);
            }
            return;
        }

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
        try
        {
            this.connection = openConnection();
            this.connectBreakerUntil = 0L;
        }
        catch (RuntimeException ex)
        {
            this.connectBreakerUntil = System.currentTimeMillis() + CONNECT_BREAKER_MS;
            throw ex;
        }
    }

    /**
     * Opens a brand new MySQL connection without touching {@link #connection}.
     * Tries the configured jdbcUrl first, then every database-name candidate
     * (optionally auto-creating the schema). Used by {@link #connect()} and by
     * the async snapshot fetch which runs on its own connection so it can never
     * block or be blocked by the main-thread connection.
     */
    private Connection openConnection()
    {
        if (isSqlite())
        {
            if (this.dataFolder != null && !this.dataFolder.exists())
            {
                this.dataFolder.mkdirs();
            }
            java.io.File dbFile = this.dataFolder != null
                ? new java.io.File(this.dataFolder, this.configuredName + ".db")
                : new java.io.File(this.configuredName + ".db");
            String sqliteUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
            try
            {
                Connection conn = DriverManager.getConnection(sqliteUrl);
                conn.setAutoCommit(true);
                try (Statement s = conn.createStatement())
                {
                    s.execute("PRAGMA foreign_keys = ON;");
                    s.execute("PRAGMA journal_mode = WAL;");
                    s.execute("PRAGMA synchronous = NORMAL;");
                }
                this.activeName = this.configuredName;
                return conn;
            }
            catch (SQLException e)
            {
                throw new IllegalStateException("Failed to connect to SQLite database at " + sqliteUrl, e);
            }
        }

        SQLException firstFailure = null;
        List<String> attemptedUrls = new ArrayList<String>();

        if (!this.jdbcUrl.isEmpty())
        {
            String finalUrl = appendParameters(this.jdbcUrl);
            attemptedUrls.add(maskCredentials(finalUrl));
            try
            {
                return finishConnection(DriverManager.getConnection(finalUrl, this.user, this.pass), this.configuredName);
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
                    return finishConnection(DriverManager.getConnection(databaseUrl, this.user, this.pass), candidateName);
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
                        return finishConnection(DriverManager.getConnection(databaseUrl, this.user, this.pass), candidateName);
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

    private Connection finishConnection(Connection connection, String name) throws SQLException
    {
        connection.setAutoCommit(true);
        try
        {
            connection.setCatalog(name);
        }
        catch (SQLException ignored)
        {
        }
        this.activeName = name;
        return connection;
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

    private synchronized void ensureConnection()
    {
        if (System.currentTimeMillis() < this.connectBreakerUntil)
        {
            throw new IllegalStateException("MySQL connection failed recently; skipping reconnect attempt (circuit breaker).");
        }
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
        close(3000L);
    }

    /**
     * Closes the shared connection without freezing the calling (usually main) thread.
     *
     * The blocking {@link Connection#close()} runs on a short-lived daemon thread and is
     * only waited for up to timeoutMs milliseconds. When it does not finish in time
     * (e.g. the MySQL socket is stuck, as seen in the Paper Watchdog dumps at
     * Database.java closeSilently), the connection is aborted forcefully, which closes
     * the underlying socket immediately.
     */
    public void close(long timeoutMs)
    {
        final Connection toClose;
        synchronized (this)
        {
            toClose = this.connection;
            this.connection = null;
        }
        if (toClose == null)
        {
            return;
        }
        Thread closer = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    toClose.close();
                }
                catch (SQLException ignored)
                {
                }
            }
        }, "AuctionHousAqua-DB-Close");
        closer.setDaemon(true);
        closer.start();
        try
        {
            closer.join(Math.max(500L, timeoutMs));
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        if (closer.isAlive())
        {
            AuctionHouse plugin = AuctionHouse.getInstance();
            if (plugin != null)
            {
                plugin.getLogger().warning("Database close did not finish in time; aborting the MySQL connection forcefully.");
            }
            try
            {
                toClose.abort(Runnable::run);
            }
            catch (SQLException | RuntimeException ignored)
            {
            }
        }
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
        // Statement leak fix: callers close the ResultSet (often via try-with-resources)
        // but never the Statement. closeOnCompletion() makes the driver close the
        // Statement as soon as its ResultSet is closed, so statements can no longer pile
        // up on the connection (the pile-up was what froze Connection#close for 40+ seconds).
        try
        {
            statement.closeOnCompletion();
        }
        catch (Throwable ignored)
        {
        }
        for (int i = 0; i < params.length; ++i)
        {
            if (params[i] instanceof Timestamp)
            {
                statement.setTimestamp(i + 1, (Timestamp) params[i]);
            }
            else
            {
                statement.setObject(i + 1, params[i]);
            }
        }
        return statement;
    }

    private void closeStatement(PreparedStatement statement)
    {
        if (statement != null)
        {
            try
            {
                statement.close();
            }
            catch (SQLException ignored)
            {
            }
        }
    }

    private void markWrite()
    {
        this.lastWriteAt = System.currentTimeMillis();
    }

    public long getLastWriteAt()
    {
        return this.lastWriteAt;
    }


    private void setupStructure()
    {
        if (isSqlite())
        {
            this.exec("CREATE TABLE IF NOT EXISTS `bidder` ("
                + "`id` INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "`name` TEXT NOT NULL UNIQUE,"
                + "`type` INTEGER NOT NULL,"
                + "`notify` INTEGER NOT NULL"
                + ");");

            this.exec("CREATE TABLE IF NOT EXISTS `auctions` ("
                + "`id` INTEGER PRIMARY KEY,"
                + "`ownerid` INTEGER NOT NULL,"
                + "`item` TEXT NOT NULL,"
                + "`amount` INTEGER NOT NULL,"
                + "`timestamp` TIMESTAMP NOT NULL,"
                + "FOREIGN KEY (`ownerid`) REFERENCES `bidder` (`id`) ON DELETE CASCADE"
                + ");");

            this.exec("CREATE TABLE IF NOT EXISTS `bids` ("
                + "`id` INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "`auctionid` INTEGER NOT NULL,"
                + "`bidderid` INTEGER NOT NULL,"
                + "`amount` REAL NOT NULL,"
                + "`timestamp` TIMESTAMP NOT NULL,"
                + "FOREIGN KEY (`auctionid`) REFERENCES `auctions` (`id`) ON DELETE CASCADE,"
                + "FOREIGN KEY (`bidderid`) REFERENCES `bidder` (`id`) ON DELETE CASCADE"
                + ");");

            this.exec("CREATE TABLE IF NOT EXISTS `auctionbox` ("
                + "`id` INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "`bidderid` INTEGER NOT NULL,"
                + "`item` TEXT NOT NULL,"
                + "`amount` INTEGER NOT NULL,"
                + "`price` REAL NOT NULL,"
                + "`timestamp` TIMESTAMP NOT NULL,"
                + "`ownerid` INTEGER NOT NULL,"
                + "FOREIGN KEY (`bidderid`) REFERENCES `bidder` (`id`) ON DELETE CASCADE"
                + ");");

            this.exec("CREATE TABLE IF NOT EXISTS `subscription` ("
                + "`id` INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "`bidderid` INTEGER NOT NULL,"
                + "`auctionid` INTEGER DEFAULT NULL,"
                + "`type` INTEGER NOT NULL,"
                + "`item` TEXT DEFAULT NULL,"
                + "FOREIGN KEY (`bidderid`) REFERENCES `bidder` (`id`) ON DELETE CASCADE,"
                + "FOREIGN KEY (`auctionid`) REFERENCES `auctions` (`id`) ON DELETE CASCADE"
                + ");");

            this.exec("CREATE TABLE IF NOT EXISTS `price` ("
                + "`id` INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "`item` TEXT DEFAULT NULL,"
                + "`price` REAL NOT NULL,"
                + "`amount` INTEGER NOT NULL"
                + ");");

            this.exec("CREATE INDEX IF NOT EXISTS `idx_auctions_ownerid` ON `auctions` (`ownerid`);");
            this.exec("CREATE INDEX IF NOT EXISTS `idx_bids_auctionid` ON `bids` (`auctionid`);");
            this.exec("CREATE INDEX IF NOT EXISTS `idx_bids_bidderid` ON `bids` (`bidderid`);");
            this.exec("CREATE INDEX IF NOT EXISTS `idx_auctionbox_bidderid` ON `auctionbox` (`bidderid`);");
            this.exec("CREATE INDEX IF NOT EXISTS `idx_subscription_bidderid` ON `subscription` (`bidderid`);");
            this.exec("CREATE INDEX IF NOT EXISTS `idx_subscription_auctionid` ON `subscription` (`auctionid`);");
            return;
        }

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
        PreparedStatement statement = null;
        try
        {
            statement = createStatement(query, params);
            return statement.executeQuery();
        }
        catch (SQLException e)
        {
            closeStatement(statement);
            throw new IllegalStateException("Failed to execute query: " + query, e);
        }
    }

    public int execUpdate(String query, Object... params)
    {
        try (PreparedStatement statement = createStatement(query, params))
        {
            this.markWrite();
            return statement.executeUpdate();
        }
        catch (SQLException e)
        {
            throw new IllegalStateException("Failed to execute query: " + query, e);
        }
    }

    public boolean exec(String query, Object... params)
    {
        try (PreparedStatement statement = createStatement(query, params))
        {
            this.markWrite();
            return statement.execute();
        }
        catch (SQLException e)
        {
            throw new IllegalStateException("Failed to execute query: " + query, e);
        }
    }

    /**
     * Legacy synchronous full reload on the calling thread (used once during onEnable).
     * Periodic and GUI-triggered reloads must use {@link #fetchSnapshot()} on an async
     * thread plus {@link #applySnapshot(DatabaseSnapshot, long)} on the main thread instead.
     */
    public synchronized void loadDatabase()
    {
        long startedAt = System.currentTimeMillis();
        try
        {
            this.ensureConnection();
            this.applySnapshot(this.fetchSnapshot(this.connection, startedAt), startedAt);
        }
        catch (SQLException ex)
        {
            throw new IllegalStateException("Error while loading the database!", ex);
        }
    }

    /**
     * Fetches a full snapshot of all auction tables on a dedicated short-lived connection.
     * This method performs JDBC I/O only and never touches the Bukkit API, so it is safe
     * to call from an async thread while the main thread keeps using the shared connection.
     *
     * @throws IllegalStateException when the database cannot be reached or read
     */
    public DatabaseSnapshot fetchSnapshot()
    {
        long startedAt = System.currentTimeMillis();
        Connection connection = openSnapshotConnection();
        try
        {
            return this.fetchSnapshot(connection, startedAt);
        }
        catch (SQLException ex)
        {
            throw new IllegalStateException("Error while fetching a database snapshot!", ex);
        }
        finally
        {
            try
            {
                connection.close();
            }
            catch (SQLException ignored)
            {
            }
        }
    }

    private Connection openSnapshotConnection()
    {
        if (this.isSqlite())
        {
            return this.openConnection();
        }
        if (this.jdbcUrl.isEmpty() && this.activeName != null)
        {
            String url = this.buildDatabaseUrl(this.activeName);
            try
            {
                return this.finishConnection(DriverManager.getConnection(url, this.user, this.pass), this.activeName);
            }
            catch (SQLException ignored)
            {
                // Fall through and retry with the full candidate list below.
            }
        }
        return this.openConnection();
    }

    private DatabaseSnapshot fetchSnapshot(Connection connection, long startedAt) throws SQLException
    {
        AuctionHouse.debug("Start loading database...");

        List<DatabaseSnapshot.BidderRow> bidders = new ArrayList<DatabaseSnapshot.BidderRow>();
        Map<Integer, String> bidderNames = new HashMap<Integer, String>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM `bidder`");
             ResultSet bidderset = statement.executeQuery())
        {
            while (bidderset.next())
            {
                int id = bidderset.getInt("id");
                String name = bidderset.getString("name");
                bidderNames.put(Integer.valueOf(id), name);
                bidders.add(new DatabaseSnapshot.BidderRow(id, name, bidderset.getByte("notify")));
            }
        }

        List<DatabaseSnapshot.AuctionRow> auctions = new ArrayList<DatabaseSnapshot.AuctionRow>();
        Map<Integer, DatabaseSnapshot.AuctionRow> auctionRowsById = new HashMap<Integer, DatabaseSnapshot.AuctionRow>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM `auctions` ORDER BY `id` ASC");
             ResultSet auctionset = statement.executeQuery())
        {
            while (auctionset.next())
            {
                int id = auctionset.getInt("id");
                DatabaseSnapshot.AuctionRow row = new DatabaseSnapshot.AuctionRow(
                    id,
                    auctionset.getInt("ownerid"),
                    auctionset.getString("item"),
                    auctionset.getInt("amount"),
                    auctionset.getTimestamp("timestamp").getTime()
                );
                auctions.add(row);
                auctionRowsById.put(Integer.valueOf(id), row);
            }
        }

        // Bid history is loaded with one query instead of one query per auction (old N+1 pattern).
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM `bids` ORDER BY `auctionid` ASC, `timestamp` ASC, `id` ASC");
             ResultSet bidset = statement.executeQuery())
        {
            while (bidset.next())
            {
                DatabaseSnapshot.AuctionRow auctionRow = auctionRowsById.get(Integer.valueOf(bidset.getInt("auctionid")));
                if (auctionRow == null)
                {
                    continue; // bid without an auction cannot exist while the FKs are in place
                }
                auctionRow.bids.add(new DatabaseSnapshot.BidRow(
                    bidset.getInt("id"),
                    bidset.getInt("bidderid"),
                    bidset.getDouble("amount"),
                    bidset.getTimestamp("timestamp")
                ));
            }
        }

        List<DatabaseSnapshot.SubscriptionRow> subscriptions = new ArrayList<DatabaseSnapshot.SubscriptionRow>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM `subscription`");
             ResultSet subset = statement.executeQuery())
        {
            while (subset.next())
            {
                subscriptions.add(new DatabaseSnapshot.SubscriptionRow(
                    subset.getInt("bidderid"),
                    subset.getInt("auctionid"),
                    subset.getInt("type"),
                    subset.getString("item")
                ));
            }
        }

        List<DatabaseSnapshot.BoxRow> boxes = new ArrayList<DatabaseSnapshot.BoxRow>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM `auctionbox` ORDER BY `timestamp` ASC");
             ResultSet itemset = statement.executeQuery())
        {
            while (itemset.next())
            {
                boxes.add(new DatabaseSnapshot.BoxRow(
                    itemset.getInt("id"),
                    itemset.getInt("bidderid"),
                    itemset.getString("item"),
                    itemset.getInt("amount"),
                    itemset.getDouble("price"),
                    itemset.getTimestamp("timestamp"),
                    itemset.getInt("ownerid")
                ));
            }
        }

        List<DatabaseSnapshot.PriceRow> prices = new ArrayList<DatabaseSnapshot.PriceRow>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM `price`");
             ResultSet priceset = statement.executeQuery())
        {
            while (priceset.next())
            {
                prices.add(new DatabaseSnapshot.PriceRow(
                    priceset.getString("item"),
                    priceset.getDouble("price"),
                    priceset.getInt("amount")
                ));
            }
        }

        // Resolve bidder names that are referenced by rows but missing from the bidder table
        // (possible on shared hosting where the FK creation failed). This used to run as single
        // row SELECTs in the middle of the apply step; doing it here keeps the apply step JDBC-free.
        this.resolveMissingBidderNames(connection, bidderNames, auctions, boxes, subscriptions);

        return new DatabaseSnapshot(startedAt, bidders, auctions, subscriptions, boxes, prices, bidderNames);
    }

    private void resolveMissingBidderNames(Connection connection, Map<Integer, String> bidderNames,
                                           List<DatabaseSnapshot.AuctionRow> auctions,
                                           List<DatabaseSnapshot.BoxRow> boxes,
                                           List<DatabaseSnapshot.SubscriptionRow> subscriptions) throws SQLException
    {
        Set<Integer> referenced = new HashSet<Integer>();
        for (DatabaseSnapshot.AuctionRow auctionRow : auctions)
        {
            referenced.add(Integer.valueOf(auctionRow.ownerId));
            for (DatabaseSnapshot.BidRow bidRow : auctionRow.bids)
            {
                referenced.add(Integer.valueOf(bidRow.bidderId));
            }
        }
        for (DatabaseSnapshot.SubscriptionRow subscriptionRow : subscriptions)
        {
            referenced.add(Integer.valueOf(subscriptionRow.bidderId));
        }
        for (DatabaseSnapshot.BoxRow boxRow : boxes)
        {
            referenced.add(Integer.valueOf(boxRow.bidderId));
            referenced.add(Integer.valueOf(boxRow.ownerId));
        }
        for (Integer id : referenced)
        {
            if (!bidderNames.containsKey(id))
            {
                String name = this.lookupBidderName(connection, id.intValue());
                if (name != null)
                {
                    bidderNames.put(id, name);
                }
            }
        }
    }

    private String lookupBidderName(Connection connection, int id) throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement("SELECT `name` FROM `bidder` WHERE `id`=? LIMIT 1"))
        {
            statement.setInt(1, id);
            try (ResultSet set = statement.executeQuery())
            {
                if (set.next())
                {
                    return set.getString("name");
                }
            }
        }
        return null;
    }

    /**
     * Rebuilds the in-memory caches (Manager auctions, Bidders, boxes, subscriptions, prices)
     * from a snapshot previously returned by {@link #fetchSnapshot()}.
     *
     * This method creates Bukkit objects (ItemStack deserialization, OfflinePlayer lookups)
     * and must therefore run on the main server thread. It performs no JDBC calls.
     *
     * @return false when the snapshot was stale (a local write happened while it was fetched)
     *         and was discarded without touching the caches.
     */
    public boolean applySnapshot(DatabaseSnapshot snapshot, long fetchedAt)
    {
        if (snapshot == null)
        {
            return false;
        }
        if (fetchedAt > 0L && fetchedAt < this.lastWriteAt)
        {
            AuctionHouse.debug("Skipped a stale database snapshot: this server wrote to MySQL while it was being fetched.");
            return false;
        }

        Manager.getInstance().clearForDatabaseReload();
        Bidder.getInstances().clear();
        ServerBidder.resetInstance();

        Map<Integer, String> bidderNames = snapshot.bidderNames;
        for (DatabaseSnapshot.BidderRow bidderRow : snapshot.bidders)
        {
            Bidder bidder = Bidder.getInstance(bidderRow.id, bidderRow.name);
            bidder.resetNotifyState(bidderRow.notify);
        }
        AuctionHouse.debug("All bidders loaded!");

        for (DatabaseSnapshot.AuctionRow auctionRow : snapshot.auctions)
        {
            String ownerName = bidderNames.get(Integer.valueOf(auctionRow.ownerId));
            if (ownerName == null)
            {
                AuctionHouse.debug("Skipped auction #" + auctionRow.id + ": unknown bidder id " + auctionRow.ownerId + ".");
                continue;
            }
            ItemStack item = Util.convertItem(auctionRow.item, auctionRow.amount);
            Bidder owner = Bidder.getInstance(auctionRow.ownerId, ownerName);
            Auction auction = new Auction(auctionRow.id, item, owner, auctionRow.auctionEnd);
            for (DatabaseSnapshot.BidRow bidRow : auctionRow.bids)
            {
                String bidderName = bidderNames.get(Integer.valueOf(bidRow.bidderId));
                if (bidderName == null)
                {
                    AuctionHouse.debug("Skipped bid #" + bidRow.id + ": unknown bidder id " + bidRow.bidderId + ".");
                    continue;
                }
                auction.getBids().push(new Bid(bidRow.id, bidRow.bidderId, bidderName, bidRow.amount, bidRow.timestamp));
            }
            Manager.getInstance().addAuction(auction);
        }
        AuctionHouse.debug("All auctions loaded!");

        for (DatabaseSnapshot.SubscriptionRow subscriptionRow : snapshot.subscriptions)
        {
            String bidderName = bidderNames.get(Integer.valueOf(subscriptionRow.bidderId));
            if (bidderName == null)
            {
                AuctionHouse.debug("Skipped subscription: unknown bidder id " + subscriptionRow.bidderId + ".");
                continue;
            }
            Bidder bidder = Bidder.getInstance(subscriptionRow.bidderId, bidderName);
            if (subscriptionRow.type == 1)
            {
                bidder.addDataBaseSub(subscriptionRow.auctionId);
            }
            else
            {
                bidder.addDataBaseSub(Util.convertItem(subscriptionRow.item));
            }
        }
        AuctionHouse.debug("All subscriptions loaded!");

        for (DatabaseSnapshot.BoxRow boxRow : snapshot.boxes)
        {
            String bidderName = bidderNames.get(Integer.valueOf(boxRow.bidderId));
            if (bidderName == null)
            {
                AuctionHouse.debug("Skipped auctionbox item #" + boxRow.id + ": unknown bidder id " + boxRow.bidderId + ".");
                continue;
            }
            Bidder bidder = Bidder.getInstance(boxRow.bidderId, bidderName);
            String ownerName = bidderNames.get(Integer.valueOf(boxRow.ownerId));
            bidder.getBox().getItemList().add(
                new AuctionItem(
                    bidder,
                    Util.convertItem(boxRow.item, boxRow.amount),
                    boxRow.timestamp,
                    ownerName,
                    boxRow.price,
                    boxRow.id
                )
            );
        }
        AuctionHouse.debug("All auctionboxes loaded!");

        for (DatabaseSnapshot.PriceRow priceRow : snapshot.prices)
        {
            Manager.getInstance().setPrice(Util.convertItem(priceRow.item), priceRow.price, priceRow.amount);
        }
        AuctionHouse.debug("All average prices loaded!");
        AuctionHouse.log("Database loaded successfully");
        return true;
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

        try (PreparedStatement statement = this.createStatement(query.toString(), params.toArray()))
        {
            this.markWrite();
            return statement.executeUpdate();
        }
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

        try (PreparedStatement statement = this.createStatement(query.toString(), params.toArray()))
        {
            this.markWrite();
            return statement.executeUpdate();
        }
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
        try (PreparedStatement statement = this.createStatement(query.toString(), condition == null ? new Object[0] : condition.getValues()))
        {
            this.markWrite();
            return statement.executeUpdate();
        }
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

        PreparedStatement statement = null;
        try
        {
            statement = this.createStatement(query.toString(), condition == null ? new Object[0] : condition.getValues());
            return statement.executeQuery();
        }
        catch (SQLException e)
        {
            closeStatement(statement);
            throw e;
        }
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
