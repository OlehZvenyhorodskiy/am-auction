package de.cubeisland.AuctionHouse.Commands;

import de.cubeisland.AuctionHouse.AbstractCommand;
import de.cubeisland.AuctionHouse.Auction.ServerBidder;
import de.cubeisland.AuctionHouse.Auction.Bidder;
import de.cubeisland.AuctionHouse.AuctionHouse;
import de.cubeisland.AuctionHouse.AuctionHouseConfiguration;
import de.cubeisland.AuctionHouse.BaseCommand;
import de.cubeisland.AuctionHouse.CommandArgs;
import de.cubeisland.AuctionHouse.Database.Database;
import de.cubeisland.AuctionHouse.Manager;
import de.cubeisland.AuctionHouse.RedisSync;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

/**
 * Shows Redis synchronization health and provides an OP/console-only full auction purge.
 */
public class RedisCommand extends AbstractCommand
{
    public RedisCommand(BaseCommand base)
    {
        super(base, "redis", "redisstatus", "clearall", "clearauction", "purge", "resetauction");
    }

    @Override
    public boolean execute(CommandSender sender, CommandArgs args)
    {
        String label = args.getLabel();
        if ("clearall".equalsIgnoreCase(label) || "clearauction".equalsIgnoreCase(label) || "purge".equalsIgnoreCase(label) || "resetauction".equalsIgnoreCase(label))
        {
            return executeClearAll(sender, args);
        }
        return executeRedisStatus(sender);
    }

    private boolean executeClearAll(CommandSender sender, CommandArgs args)
    {
        if (!isConsoleOrOp(sender))
        {
            sender.sendMessage("§c[AuctionHousAqua] Команду полной очистки может выполнять только консоль или OP.");
            return true;
        }

        if (!args.hasFlag("confirm") && !args.hasFlag("yes"))
        {
            sender.sendMessage("§e[AuctionHousAqua] Это полностью очистит аукцион: auctions, bids, auctionbox, subscription, price, bidder и Redis-ключи auctionhouse:*.");
            sender.sendMessage("§e[AuctionHousAqua] Для подтверждения введи: §f/ah clearall confirm");
            return true;
        }

        AuctionHouse plugin = AuctionHouse.getInstance();
        Database db = plugin.getDB();
        if (db == null)
        {
            sender.sendMessage("§c[AuctionHousAqua] База данных не подключена, очистка невозможна.");
            return true;
        }

        int auctionbox = 0;
        int subscriptions = 0;
        int bids = 0;
        int prices = 0;
        int auctions = 0;
        int bidders = 0;
        boolean fkDisabled = false;
        try
        {
            db.execUpdate("SET FOREIGN_KEY_CHECKS=0");
            fkDisabled = true;
            auctionbox = safeDelete(db, "auctionbox");
            subscriptions = safeDelete(db, "subscription");
            bids = safeDelete(db, "bids");
            prices = safeDelete(db, "price");
            auctions = safeDelete(db, "auctions");
            bidders = safeDelete(db, "bidder");
            resetAutoIncrement(db, "auctionbox");
            resetAutoIncrement(db, "subscription");
            resetAutoIncrement(db, "bids");
            resetAutoIncrement(db, "price");
            resetAutoIncrement(db, "bidder");
        }
        catch (RuntimeException ex)
        {
            AuctionHouse.error("Full auction MySQL clear failed.", ex);
            sender.sendMessage("§c[AuctionHousAqua] Ошибка очистки MySQL: " + ex.getMessage());
            return true;
        }
        finally
        {
            if (fkDisabled)
            {
                try
                {
                    db.execUpdate("SET FOREIGN_KEY_CHECKS=1");
                }
                catch (RuntimeException ex)
                {
                    AuctionHouse.error("Failed to re-enable MySQL foreign key checks after auction clear.", ex);
                }
            }
        }

        Manager.getInstance().clearForDatabaseReload();
        Bidder.getInstances().clear();
        ServerBidder.resetInstance();

        int redisDeleted = RedisSync.getInstance().clearAuctionHouseKeys();
        RedisSync.getInstance().publishFullReload();

        try
        {
            db.loadDatabase();
        }
        catch (RuntimeException ex)
        {
            AuctionHouse.error("Database reload after full auction clear failed.", ex);
            sender.sendMessage("§c[AuctionHousAqua] MySQL очищен, но перезагрузка памяти не удалась: " + ex.getMessage());
            return true;
        }

        String message = "[Clear144] full auction clear by " + sender.getName()
            + ": auctions=" + auctions
            + " bids=" + bids
            + " auctionbox=" + auctionbox
            + " subscription=" + subscriptions
            + " price=" + prices
            + " bidder=" + bidders
            + " redisKeys=" + redisDeleted;
        AuctionHouse.log(message);
        sender.sendMessage("§a[AuctionHousAqua] Аукцион полностью очищен. " + message);
        return true;
    }

    private boolean isConsoleOrOp(CommandSender sender)
    {
        return sender instanceof ConsoleCommandSender || sender.isOp() || sender.hasPermission("auctionhouse.admin.clear");
    }

    private int safeDelete(Database db, String table)
    {
        try
        {
            return db.execUpdate("DELETE FROM `" + table + "`");
        }
        catch (RuntimeException ex)
        {
            AuctionHouse.error("Failed to delete table " + table + " during full auction clear.", ex);
            throw ex;
        }
    }

    private void resetAutoIncrement(Database db, String table)
    {
        try
        {
            db.execUpdate("ALTER TABLE `" + table + "` AUTO_INCREMENT=1");
        }
        catch (RuntimeException ex)
        {
            AuctionHouse.debug("Could not reset AUTO_INCREMENT for " + table + ": " + ex.getMessage());
        }
    }

    private boolean executeRedisStatus(CommandSender sender)
    {
        AuctionHouse plugin = AuctionHouse.getInstance();
        AuctionHouseConfiguration config = plugin.getConfiguration();
        RedisSync redis = RedisSync.getInstance();

        sender.sendMessage("§6[AuctionHousAqua] Redis status");
        sender.sendMessage("§7enabled: §f" + config.redis_enabled);
        sender.sendMessage("§7running: §f" + redis.isRunning());
        sender.sendMessage("§7subscriber: §f" + redis.isSubscriberConnected());
        sender.sendMessage("§7ping: §f" + redis.ping());
        sender.sendMessage("§7server id: §f" + String.valueOf(redis.getServerId()));
        sender.sendMessage("§7host: §f" + config.redis_host + ":" + config.redis_port + " db=" + config.redis_database);
        sender.sendMessage("§7channels: §f" + config.redis_channel_main + " §8| §f" + config.redis_channel_inventory + " §8| §f" + config.redis_channel_transaction);
        sender.sendMessage("§7last received: §f" + formatAgo(redis.getLastMessageAt()));
        sender.sendMessage("§7last published: §f" + formatAgo(redis.getLastPublishAt()));
        if (!redis.getLastError().isEmpty())
        {
            sender.sendMessage("§7last error: §c" + redis.getLastError());
        }
        else
        {
            sender.sendMessage("§7last error: §a-");
        }
        return true;
    }

    private String formatAgo(long timestamp)
    {
        if (timestamp <= 0L)
        {
            return "never";
        }
        long seconds = Math.max(0L, (System.currentTimeMillis() - timestamp) / 1000L);
        return seconds + "s ago";
    }

    @Override
    public String getDescription()
    {
        return "Проверить состояние Redis или полностью очистить аукцион MySQL/Redis";
    }
}
