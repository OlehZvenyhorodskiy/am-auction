package de.cubeisland.AuctionHouse;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/**
 * Coordinates cross-server synchronization.
 *
 * Redis is the primary sync path. The old full-MySQL polling is kept only as an optional fallback
 * and is disabled by default because full reloads are expensive on production servers.
 */
public class CrossServerSync
{
    private static CrossServerSync instance;
    private BukkitTask task;
    private long lastSync = 0L;
    private boolean syncing = false;

    public static CrossServerSync getInstance()
    {
        if (instance == null)
        {
            instance = new CrossServerSync();
        }
        return instance;
    }

    public void start()
    {
        stop();
        AuctionHouse plugin = AuctionHouse.getInstance();
        RedisSync.getInstance().start();

        int seconds = plugin.getConfiguration().auction_database_syncIntervalSeconds;
        if (RedisSync.getInstance().isRunning())
        {
            AuctionHouse.log("Full MySQL polling disabled; Redis events are used for cross-server sync.");
            return;
        }

        if (seconds <= 0)
        {
            AuctionHouse.log("Cross-server full MySQL polling disabled by config.");
            return;
        }

        long ticks = Math.max(20L, seconds * 20L);
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable()
        {
            @Override
            public void run()
            {
                fullSyncNow(false);
            }
        }, ticks, ticks);
        AuctionHouse.log("Fallback full MySQL auction sync enabled: every " + seconds + "s");
    }

    public void stop()
    {
        if (this.task != null)
        {
            this.task.cancel();
            this.task = null;
        }
        RedisSync.getInstance().stop();
        this.syncing = false;
    }

    /**
     * Backward-compatible entrypoint used by older GUI code. When Redis is available it intentionally
     * does not reload all MySQL tables, because Redis keeps the local cache current.
     */
    public void syncNow(boolean verbose)
    {
        if (RedisSync.getInstance().isRunning())
        {
            if (verbose || AuctionHouse.debugMode)
            {
                AuctionHouse.debug("Redis sync is active; skipped expensive full MySQL reload.");
            }
            return;
        }
        AuctionHouse plugin = AuctionHouse.getInstance();
        if (plugin != null && plugin.getConfiguration() != null && plugin.getConfiguration().auction_database_syncIntervalSeconds <= 0)
        {
            if (verbose || AuctionHouse.debugMode)
            {
                AuctionHouse.debug("Redis is not running and fallback MySQL polling is disabled; skipped GUI-triggered full reload.");
            }
            return;
        }
        fullSyncNow(verbose);
    }

    public void fullSyncNow(boolean verbose)
    {
        if (this.syncing)
        {
            return;
        }
        AuctionHouse plugin = AuctionHouse.getInstance();
        if (plugin == null || !plugin.isEnabled() || plugin.getDB() == null)
        {
            return;
        }
        this.syncing = true;
        try
        {
            plugin.getDB().loadDatabase();
            this.lastSync = System.currentTimeMillis();
            if (verbose || AuctionHouse.debugMode)
            {
                AuctionHouse.debug("Full MySQL auction sync completed.");
            }
        }
        catch (RuntimeException ex)
        {
            AuctionHouse.error("Full MySQL auction sync failed.", ex);
        }
        finally
        {
            this.syncing = false;
        }
    }

    public long getLastSync()
    {
        return this.lastSync;
    }
}
