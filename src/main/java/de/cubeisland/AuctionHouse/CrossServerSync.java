package de.cubeisland.AuctionHouse;

import de.cubeisland.AuctionHouse.Database.DatabaseSnapshot;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/**
 * Coordinates cross-server synchronization.
 *
 * Redis is the primary sync path. The old full-MySQL polling is kept only as an optional fallback
 * and is disabled by default because full reloads are expensive on production servers.
 *
 * Full MySQL reloads are now two-phase so they can never freeze the Server thread:
 * 1. fetchSnapshot() runs on an async thread with its own short-lived MySQL connection
 *    (pure JDBC, no Bukkit API);
 * 2. applySnapshot() runs on the next server tick and rebuilds the in-memory caches.
 *
 * A snapshot fetched while this server was writing to MySQL is discarded (stale-snapshot guard
 * in Database.applySnapshot) so a periodic reload can no longer resurrect just-sold auctions.
 */
public class CrossServerSync
{
    /** Full table scans are expensive; anything below this is almost certainly a misconfiguration. */
    private static final int MIN_FALLBACK_INTERVAL_SECONDS = 30;

    private static CrossServerSync instance;
    private BukkitTask task;
    private volatile long lastSync = 0L;
    private final AtomicBoolean syncing = new AtomicBoolean(false);

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

        if (seconds < MIN_FALLBACK_INTERVAL_SECONDS)
        {
            seconds = MIN_FALLBACK_INTERVAL_SECONDS;
            AuctionHouse.getInstance().getLogger().warning(
                "auction.database.syncIntervalSeconds is below " + MIN_FALLBACK_INTERVAL_SECONDS
                + "s. Full MySQL reloads every few seconds caused the 110k-line debug spam and tick freezes; "
                + "the interval was raised to " + MIN_FALLBACK_INTERVAL_SECONDS
                + "s. Enable redis for instant sync, or set syncIntervalSeconds: 0 to disable polling.");
        }

        final int intervalSeconds = seconds;
        long ticks = intervalSeconds * 20L;
        // Async timer: the JDBC fetch must never run on the Server thread.
        this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, new Runnable()
        {
            @Override
            public void run()
            {
                fullSyncNow(false);
            }
        }, ticks, ticks);
        AuctionHouse.log("Fallback full MySQL auction sync enabled: every " + intervalSeconds + "s (fetched async)");
    }

    public void stop()
    {
        if (this.task != null)
        {
            this.task.cancel();
            this.task = null;
        }
        RedisSync.getInstance().stop();
        this.syncing.set(false);
    }

    /**
     * Backward-compatible entrypoint used by older GUI code. When Redis is available it intentionally
     * does not reload all MySQL tables, because Redis keeps the local cache current.
     *
     * The call returns immediately in every mode: possible full reloads are queued onto an async
     * thread, so opening a menu can no longer block on MySQL.
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

    /**
     * Runs one full MySQL reload. Safe to call from any thread (including the Server thread,
     * e.g. from GUI code or the Redis FULL_RELOAD event): when called on the Server thread the
     * work is rescheduled onto the async pool, and the resulting snapshot is applied back on
     * the main thread.
     */
    public void fullSyncNow(final boolean verbose)
    {
        final AuctionHouse plugin = AuctionHouse.getInstance();
        if (plugin == null || !plugin.isEnabled() || plugin.getDB() == null)
        {
            return;
        }

        if (Bukkit.isPrimaryThread())
        {
            try
            {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable()
                {
                    @Override
                    public void run()
                    {
                        fullSyncNow(verbose);
                    }
                });
            }
            catch (RuntimeException ignored)
            {
                // Plugin got disabled while we were scheduling; nothing to sync anymore.
            }
            return;
        }

        if (!this.syncing.compareAndSet(false, true))
        {
            return; // another sync is already in flight
        }

        try
        {
            final DatabaseSnapshot snapshot = plugin.getDB().fetchSnapshot();
            if (!plugin.isEnabled())
            {
                this.syncing.set(false);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        if (plugin.isEnabled() && plugin.getDB() != null)
                        {
                            if (plugin.getDB().applySnapshot(snapshot, snapshot.fetchedAt))
                            {
                                lastSync = System.currentTimeMillis();
                                if (verbose || AuctionHouse.debugMode)
                                {
                                    AuctionHouse.debug("Full MySQL auction sync completed.");
                                }
                            }
                            else if (verbose || AuctionHouse.debugMode)
                            {
                                AuctionHouse.debug("Full MySQL auction sync skipped a stale snapshot.");
                            }
                        }
                    }
                    finally
                    {
                        syncing.set(false);
                    }
                }
            });
        }
        catch (RuntimeException ex)
        {
            // Fetching or scheduling failed; release the guard so the next interval can retry.
            this.syncing.set(false);
            AuctionHouse.error("Full MySQL auction sync failed.", ex);
        }
    }

    public long getLastSync()
    {
        return this.lastSync;
    }
}
