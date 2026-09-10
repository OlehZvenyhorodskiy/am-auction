package de.cubeisland.AuctionHouse;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;

/**
 * Lightweight Redis pub/sub + lock synchronization without an external Redis library.
 *
 * MySQL remains the source of truth. Redis is only used to notify other servers about
 * changed auction rows, changed claim inventories and short distributed locks during buys.
 */
public class RedisSync
{
    private static final String VERSION = "AHAQ1";
    /** After a connection failure, skip Redis commands for a while instead of stalling callers (up to several seconds each). */
    private static final long COMMAND_BREAKER_MS = 15000L;

    private static RedisSync instance;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ConcurrentMap<Integer, String> localAuctionLocks = new ConcurrentHashMap<Integer, String>();
    private Thread subscriberThread;
    private Socket subscriberSocket;
    private String serverId;
    private Semaphore commandLimiter = new Semaphore(16);
    private volatile boolean subscriberConnected = false;
    private volatile long lastMessageAt = 0L;
    private volatile long lastPublishAt = 0L;
    private volatile String lastError = "";
    /** When set to a future timestamp, execute() fails fast without opening a socket. */
    private volatile long commandBreakerUntil = 0L;

    public static RedisSync getInstance()
    {
        if (instance == null)
        {
            instance = new RedisSync();
        }
        return instance;
    }

    public boolean isRunning()
    {
        return this.running.get();
    }

    public String getServerId()
    {
        return this.serverId;
    }

    public boolean isSubscriberConnected()
    {
        return this.subscriberConnected;
    }

    public long getLastMessageAt()
    {
        return this.lastMessageAt;
    }

    public long getLastPublishAt()
    {
        return this.lastPublishAt;
    }

    public String getLastError()
    {
        return this.lastError == null ? "" : this.lastError;
    }

    public boolean ping()
    {
        try
        {
            Object pong = execute("PING");
            return pong != null && "PONG".equalsIgnoreCase(String.valueOf(pong));
        }
        catch (RuntimeException ex)
        {
            this.lastError = ex.getMessage();
            return false;
        }
    }

    public void start()
    {
        stop();
        AuctionHouse plugin = AuctionHouse.getInstance();
        if (plugin == null || plugin.getConfiguration() == null)
        {
            return;
        }
        AuctionHouseConfiguration config = plugin.getConfiguration();
        if (!config.redis_enabled)
        {
            AuctionHouse.log("Redis synchronization disabled by config.");
            return;
        }

        this.serverId = loadOrCreateServerId(plugin);
        this.commandLimiter = new Semaphore(Math.max(1, config.redis_pool_maxTotal));

        AuctionHouse.debug("Redis connecting to " + config.redis_host + ":" + config.redis_port + ", db=" + config.redis_database + ", channels=" + config.redis_channel_main + "," + config.redis_channel_inventory + "," + config.redis_channel_transaction);
        if (!ping())
        {
            AuctionHouse.error("Redis synchronization disabled: cannot connect to Redis. Last error: " + getLastError());
            return;
        }
        AuctionHouse.debug("Redis PING successful.");

        this.running.set(true);
        this.subscriberThread = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                subscribeLoop();
            }
        }, "AuctionHousAqua-RedisSync");
        this.subscriberThread.setDaemon(true);
        this.subscriberThread.start();
        AuctionHouse.log("Redis synchronization enabled for server " + this.serverId + ".");
    }

    public void stop()
    {
        this.running.set(false);
        this.subscriberConnected = false;
        closeSubscriberSocket();
        if (this.subscriberThread != null)
        {
            this.subscriberThread.interrupt();
            this.subscriberThread = null;
        }
        this.localAuctionLocks.clear();
    }

    public boolean tryLockAuction(int auctionId, String reason)
    {
        AuctionHouseConfiguration config = AuctionHouse.getInstance().getConfiguration();
        if (!config.redis_enabled || !isRunning())
        {
            return true;
        }
        String token = this.serverId + ":" + reason + ":" + System.nanoTime();
        Object result;
        try
        {
            result = execute("SET", lockKey(auctionId), token, "NX", "PX", String.valueOf(Math.max(1000, config.redis_lockTtlMs)));
        }
        catch (RuntimeException ex)
        {
            // Redis unreachable: degrade to single-server mode instead of failing (and previously
            // stalling the Server thread) on every buy. The circuit breaker in execute() keeps
            // further attempts cheap for a while.
            AuctionHouse.debug("Redis lock unavailable, continuing without a distributed lock: " + ex.getMessage());
            return true;
        }
        if ("OK".equalsIgnoreCase(String.valueOf(result)))
        {
            this.localAuctionLocks.put(auctionId, token);
            publish(config.redis_channel_main, message("AUCTION_INTERACT", auctionId, reason));
            return true;
        }
        return false;
    }

    public void releaseAuctionLock(int auctionId)
    {
        AuctionHouseConfiguration config = AuctionHouse.getInstance().getConfiguration();
        if (!config.redis_enabled || !isRunning())
        {
            return;
        }
        String token = this.localAuctionLocks.remove(auctionId);
        if (token == null)
        {
            return;
        }
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
        try
        {
            execute("EVAL", script, "1", lockKey(auctionId), token);
        }
        catch (RuntimeException ex)
        {
            AuctionHouse.debug("Failed to release Redis lock for auction #" + auctionId + ": " + ex.getMessage());
        }
    }

    public void publishAuctionUpsert(int auctionId)
    {
        publishMain("AUCTION_UPSERT", auctionId, "");
    }

    public void publishAuctionRemove(int auctionId)
    {
        publishMain("AUCTION_REMOVE", auctionId, "");
    }

    public void publishInventoryUpdate(int bidderId)
    {
        AuctionHouse plugin = AuctionHouse.getInstance();
        if (plugin == null || plugin.getConfiguration() == null)
        {
            return;
        }
        AuctionHouseConfiguration config = plugin.getConfiguration();
        publish(config.redis_channel_inventory, message("INVENTORY_UPDATE", bidderId, ""));
    }

    public void publishTransactionUpdate(int auctionId)
    {
        AuctionHouse plugin = AuctionHouse.getInstance();
        if (plugin == null || plugin.getConfiguration() == null)
        {
            return;
        }
        AuctionHouseConfiguration config = plugin.getConfiguration();
        publish(config.redis_channel_transaction, message("TRANSACTION_UPDATE", auctionId, ""));
    }

    public void publishFullReload()
    {
        publishMain("FULL_RELOAD", 0, "");
    }

    private void publishMain(String type, int id, String extra)
    {
        AuctionHouse plugin = AuctionHouse.getInstance();
        if (plugin == null || plugin.getConfiguration() == null)
        {
            return;
        }
        AuctionHouseConfiguration config = plugin.getConfiguration();
        publish(config.redis_channel_main, message(type, id, extra));
    }

    private void publish(String channel, String payload)
    {
        AuctionHouse plugin = AuctionHouse.getInstance();
        if (plugin == null || plugin.getConfiguration() == null)
        {
            return;
        }
        AuctionHouseConfiguration config = plugin.getConfiguration();
        if (!config.redis_enabled || !isRunning())
        {
            return;
        }
        try
        {
            execute("PUBLISH", channel, payload);
            this.lastPublishAt = System.currentTimeMillis();
            AuctionHouse.debug("Redis published event to " + channel + ": " + payload);
        }
        catch (RuntimeException ex)
        {
            AuctionHouse.debug("Redis publish failed: " + ex.getMessage());
        }
    }

    private String message(String type, int id, String extra)
    {
        return VERSION + "|" + this.serverId + "|" + type + "|" + id + "|" + sanitize(extra) + "|" + System.currentTimeMillis();
    }

    private String sanitize(String value)
    {
        if (value == null)
        {
            return "";
        }
        return value.replace('|', '/');
    }

    private String lockKey(int auctionId)
    {
        AuctionHouseConfiguration config = AuctionHouse.getInstance().getConfiguration();
        return config.redis_channel_prefix + "lock:auction:" + auctionId;
    }

    private void subscribeLoop()
    {
        while (this.running.get())
        {
            try
            {
                subscribeOnce();
            }
            catch (Throwable ex)
            {
                if (this.running.get())
                {
                    this.lastError = ex.getMessage();
                    this.subscriberConnected = false;
                    AuctionHouse.debug("Redis subscriber reconnecting after error: " + ex.getMessage());
                    sleepQuietly(2000L);
                }
            }
        }
    }

    private void subscribeOnce() throws IOException
    {
        AuctionHouseConfiguration config = AuctionHouse.getInstance().getConfiguration();
        Socket socket = openSubscriberSocket();
        this.subscriberSocket = socket;
        InputStream input = new BufferedInputStream(socket.getInputStream());
        BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
        authenticate(socket, input, output);
        sendCommand(output, "SUBSCRIBE", config.redis_channel_main, config.redis_channel_inventory, config.redis_channel_transaction);
        this.subscriberConnected = true;
        this.lastError = "";
        AuctionHouse.debug("Redis subscriber subscribed to: " + config.redis_channel_main + ", " + config.redis_channel_inventory + ", " + config.redis_channel_transaction);

        while (this.running.get() && !socket.isClosed())
        {
            Object response = readResp(input);
            if (!(response instanceof List))
            {
                continue;
            }
            List<?> array = (List<?>)response;
            if (array.size() < 3)
            {
                continue;
            }
            String kind = String.valueOf(array.get(0));
            if (!"message".equalsIgnoreCase(kind))
            {
                continue;
            }
            String payload = String.valueOf(array.get(2));
            this.lastMessageAt = System.currentTimeMillis();
            AuctionHouse.debug("Redis received event: " + payload);
            handlePayload(payload);
        }
    }

    private void handlePayload(String payload)
    {
        if (payload == null || payload.isEmpty())
        {
            return;
        }
        String[] parts = payload.split("\\|", -1);
        if (parts.length < 4 || !VERSION.equals(parts[0]))
        {
            return;
        }
        String sourceServer = parts[1];
        if (this.serverId != null && this.serverId.equals(sourceServer))
        {
            return;
        }
        String type = parts[2];
        int id;
        try
        {
            id = Integer.parseInt(parts[3]);
        }
        catch (NumberFormatException ignored)
        {
            return;
        }
        AuctionHouse plugin = AuctionHouse.getInstance();
        if (plugin == null || !plugin.isEnabled())
        {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, new Runnable()
        {
            @Override
            public void run()
            {
                handleEventOnMain(type, id);
            }
        });
    }

    private void handleEventOnMain(String type, int id)
    {
        AuctionHouse plugin = AuctionHouse.getInstance();
        if (plugin == null || plugin.getDB() == null)
        {
            return;
        }
        try
        {
            if ("AUCTION_UPSERT".equals(type) || "BID_UPDATE".equals(type))
            {
                plugin.getDB().loadAuctionById(id);
            }
            else if ("AUCTION_REMOVE".equals(type))
            {
                plugin.getDB().removeAuctionFromMemory(id);
            }
            else if ("INVENTORY_UPDATE".equals(type))
            {
                plugin.getDB().reloadAuctionBoxForBidder(id);
            }
            else if ("FULL_RELOAD".equals(type))
            {
                CrossServerSync.getInstance().fullSyncNow(false);
            }
        }
        catch (RuntimeException ex)
        {
            AuctionHouse.error("Failed to apply Redis sync event " + type + " for id " + id + ".", ex);
        }
    }

    private Object execute(String... args)
    {
        // Circuit breaker: after a recent connection failure, fail fast instead of making the
        // caller (often the Server thread during a buy) wait for connect/read timeouts again.
        if (System.currentTimeMillis() < this.commandBreakerUntil)
        {
            throw new IllegalStateException("Redis skipped: connection failed recently (circuit breaker open)");
        }
        boolean acquired = false;
        try
        {
            this.commandLimiter.acquire();
            acquired = true;
            Socket socket = openSocket();
            try
            {
                InputStream input = new BufferedInputStream(socket.getInputStream());
                BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
                authenticate(socket, input, output);
                sendCommand(output, args);
                return readResp(input);
            }
            finally
            {
                try
                {
                    socket.close();
                }
                catch (IOException ignored)
                {
                }
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            this.lastError = "Interrupted while waiting for Redis command slot: " + e.getMessage();
            throw new IllegalStateException("Interrupted while waiting for Redis command slot", e);
        }
        catch (IOException e)
        {
            this.lastError = e.getMessage();
            this.commandBreakerUntil = System.currentTimeMillis() + COMMAND_BREAKER_MS;
            throw new IllegalStateException("Redis command failed", e);
        }
        finally
        {
            if (acquired)
            {
                this.commandLimiter.release();
            }
        }
    }

    private Socket openSocket() throws IOException
    {
        return openSocket(false);
    }

    private Socket openSubscriberSocket() throws IOException
    {
        return openSocket(true);
    }

    private Socket openSocket(boolean subscriber) throws IOException
    {
        AuctionHouseConfiguration config = AuctionHouse.getInstance().getConfiguration();
        Socket socket = new Socket();
        int timeout = Math.max(500, config.redis_timeoutMs);
        socket.connect(new InetSocketAddress(config.redis_host, config.redis_port), timeout);
        // Pub/Sub sockets must stay idle forever when there are no auction events.
        // A finite read timeout makes Redis look broken and causes reconnect spam every few seconds.
        socket.setSoTimeout(subscriber ? 0 : timeout * 3);
        socket.setTcpNoDelay(true);
        return socket;
    }

    private void authenticate(Socket socket, InputStream input, BufferedOutputStream output) throws IOException
    {
        AuctionHouseConfiguration config = AuctionHouse.getInstance().getConfiguration();
        if (config.redis_password != null && !config.redis_password.isEmpty())
        {
            sendCommand(output, "AUTH", config.redis_password);
            readResp(input);
        }
        if (config.redis_database > 0)
        {
            sendCommand(output, "SELECT", String.valueOf(config.redis_database));
            readResp(input);
        }
    }

    private void sendCommand(BufferedOutputStream output, String... args) throws IOException
    {
        output.write(('*'));
        output.write(String.valueOf(args.length).getBytes(StandardCharsets.UTF_8));
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
        for (String arg : args)
        {
            byte[] data = (arg == null ? "" : arg).getBytes(StandardCharsets.UTF_8);
            output.write(('$'));
            output.write(String.valueOf(data.length).getBytes(StandardCharsets.UTF_8));
            output.write("\r\n".getBytes(StandardCharsets.UTF_8));
            output.write(data);
            output.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        output.flush();
    }

    private Object readResp(InputStream input) throws IOException
    {
        int prefix = input.read();
        if (prefix < 0)
        {
            throw new IOException("Redis connection closed");
        }
        switch (prefix)
        {
            case '+':
                return readLine(input);
            case '-':
                throw new IOException("Redis error: " + readLine(input));
            case ':':
                return Long.valueOf(readLine(input));
            case '$':
                int length = Integer.parseInt(readLine(input));
                if (length < 0)
                {
                    return null;
                }
                byte[] data = readExactly(input, length);
                readExactly(input, 2);
                return new String(data, StandardCharsets.UTF_8);
            case '*':
                int count = Integer.parseInt(readLine(input));
                if (count < 0)
                {
                    return null;
                }
                List<Object> list = new ArrayList<Object>(count);
                for (int i = 0; i < count; i++)
                {
                    list.add(readResp(input));
                }
                return list;
            default:
                throw new IOException("Unknown Redis RESP prefix: " + (char)prefix);
        }
    }

    private String readLine(InputStream input) throws IOException
    {
        StringBuilder builder = new StringBuilder();
        int previous = -1;
        int current;
        while ((current = input.read()) >= 0)
        {
            if (previous == '\r' && current == '\n')
            {
                builder.setLength(builder.length() - 1);
                return builder.toString();
            }
            builder.append((char)current);
            previous = current;
        }
        throw new IOException("Redis line ended unexpectedly");
    }

    private byte[] readExactly(InputStream input, int length) throws IOException
    {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length)
        {
            int read = input.read(data, offset, length - offset);
            if (read < 0)
            {
                throw new IOException("Redis bulk string ended unexpectedly");
            }
            offset += read;
        }
        return data;
    }

    private void closeSubscriberSocket()
    {
        if (this.subscriberSocket != null)
        {
            try
            {
                this.subscriberSocket.close();
            }
            catch (IOException ignored)
            {
            }
            finally
            {
                this.subscriberSocket = null;
            }
        }
    }

    private void sleepQuietly(long millis)
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (InterruptedException ignored)
        {
            Thread.currentThread().interrupt();
        }
    }


    /**
     * Deletes all Redis keys owned by this plugin prefix. Pub/Sub channel history
     * is not persisted by Redis, so there is nothing to delete for channels; this
     * removes locks and any cached/inventory keys created under the configured
     * auctionhouse prefix.
     *
     * @return number of deleted keys, or -1 when Redis is disabled/not configured
     */
    public int clearAuctionHouseKeys()
    {
        AuctionHouse plugin = AuctionHouse.getInstance();
        if (plugin == null || plugin.getConfiguration() == null)
        {
            return -1;
        }
        AuctionHouseConfiguration config = plugin.getConfiguration();
        if (!config.redis_enabled)
        {
            this.localAuctionLocks.clear();
            return -1;
        }

        String pattern = config.redis_channel_prefix + "*";
        String cursor = "0";
        int deleted = 0;
        try
        {
            do
            {
                Object response = execute("SCAN", cursor, "MATCH", pattern, "COUNT", "500");
                if (!(response instanceof List))
                {
                    break;
                }
                List<?> scan = (List<?>)response;
                if (scan.size() < 2)
                {
                    break;
                }
                cursor = String.valueOf(scan.get(0));
                Object keysObject = scan.get(1);
                if (keysObject instanceof List)
                {
                    List<?> keys = (List<?>)keysObject;
                    if (!keys.isEmpty())
                    {
                        String[] delArgs = new String[keys.size() + 1];
                        delArgs[0] = "DEL";
                        for (int i = 0; i < keys.size(); i++)
                        {
                            delArgs[i + 1] = String.valueOf(keys.get(i));
                        }
                        Object del = execute(delArgs);
                        if (del instanceof Number)
                        {
                            deleted += ((Number)del).intValue();
                        }
                    }
                }
            }
            while (!"0".equals(cursor));
            this.localAuctionLocks.clear();
            return deleted;
        }
        catch (RuntimeException ex)
        {
            this.lastError = ex.getMessage();
            AuctionHouse.error("Failed to clear Redis auction keys with pattern " + pattern + ".", ex);
            return -1;
        }
    }

    private String loadOrCreateServerId(AuctionHouse plugin)
    {
        File file = new File(plugin.getDataFolder(), "server.info");
        try
        {
            if (file.exists())
            {
                String existing = Files.readString(file.toPath(), StandardCharsets.UTF_8).trim();
                if (!existing.isEmpty())
                {
                    return existing;
                }
            }
            String created = UUID.randomUUID().toString();
            File parent = file.getParentFile();
            if (parent != null && !parent.exists())
            {
                parent.mkdirs();
            }
            try (FileOutputStream output = new FileOutputStream(file))
            {
                output.write(created.getBytes(StandardCharsets.UTF_8));
            }
            return created;
        }
        catch (IOException e)
        {
            String fallback = UUID.randomUUID().toString();
            AuctionHouse.error("Failed to read/write server.info. Using temporary Redis server id " + fallback + ".", e);
            return fallback;
        }
    }
}
