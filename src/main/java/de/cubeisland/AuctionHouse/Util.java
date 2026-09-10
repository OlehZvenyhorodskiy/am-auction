package de.cubeisland.AuctionHouse;

import de.cubeisland.AuctionHouse.Auction.Auction;
import de.cubeisland.AuctionHouse.Auction.Bid;
import de.cubeisland.AuctionHouse.Auction.Bidder;
import de.cubeisland.AuctionHouse.Auction.ServerBidder;
import static de.cubeisland.AuctionHouse.AuctionHouse.t;
import de.cubeisland.AuctionHouse.Database.Database;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

/**
 * Utility methods used across the plugin.
 */
public class Util
{
    private static final AuctionHouse plugin = AuctionHouse.getInstance();
    private static final String ITEM_PREFIX = "base64:";
    private static final Map<String, Long> AUCTION_REGISTER_COOLDOWN = new ConcurrentHashMap<String, Long>();
    private static final Map<String, Object> AUCTION_REGISTER_LOCKS = new ConcurrentHashMap<String, Object>();
    private static final long REGISTER_FAIL_COOLDOWN_MS = 3000L;

    public static long convertTimeToMillis(String str)
    {
        if (str == null)
        {
            return -1;
        }

        Pattern pattern = Pattern.compile("^(\\d+)([smhd])?$", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(str.trim());
        if (!matcher.matches())
        {
            return -1;
        }

        long time;
        try
        {
            time = Long.parseLong(String.valueOf(matcher.group(1)));
        }
        catch (Throwable t)
        {
            AuctionHouse.error("Failed to convert to a number", t);
            return -1;
        }
        if (time < 0)
        {
            return -1;
        }
        String unitSuffix = matcher.group(2);
        if (unitSuffix == null)
        {
            unitSuffix = "m";
        }
        switch (unitSuffix.toLowerCase(Locale.ROOT).charAt(0))
        {
            case 'd':
                time = Math.multiplyExact(time, 24L);
            case 'h':
                time = Math.multiplyExact(time, 60L);
            case 'm':
                time = Math.multiplyExact(time, 60L);
            case 's':
                time = Math.multiplyExact(time, 1000L);
                break;
            default:
                return -1;
        }
        return time;
    }

    public static boolean registerAuction(Auction auction, CommandSender sender)
    {
        return registerAuction(auction, Bidder.getInstance(sender));
    }

    public static boolean registerAuction(Auction auction, Bidder owner)
    {
        if (auction == null)
        {
            return false;
        }
        Bidder actualOwner = auction.getOwner() != null ? auction.getOwner() : owner;
        String lockKey = getAuctionOwnerKey(actualOwner);
        long now = System.currentTimeMillis();
        Long cooldownUntil = AUCTION_REGISTER_COOLDOWN.get(lockKey);
        if (cooldownUntil != null && cooldownUntil > now)
        {
            return false;
        }

        Object lock = AUCTION_REGISTER_LOCKS.computeIfAbsent(lockKey, key -> new Object());
        synchronized (lock)
        {
            cooldownUntil = AUCTION_REGISTER_COOLDOWN.get(lockKey);
            if (cooldownUntil != null && cooldownUntil > System.currentTimeMillis())
            {
                return false;
            }
            return registerAuctionLocked(auction, owner, actualOwner, lockKey);
        }
    }

    private static String getAuctionOwnerKey(Bidder bidder)
    {
        if (bidder == null)
        {
            return "server";
        }
        try
        {
            if (bidder.getId() > 0)
            {
                return "id:" + bidder.getId();
            }
        }
        catch (Throwable ignored)
        {
        }
        try
        {
            return "name:" + bidder.getName();
        }
        catch (Throwable ignored)
        {
            return "unknown";
        }
    }

    private static void markAuctionRegisterFailure(String key)
    {
        AUCTION_REGISTER_COOLDOWN.put(key, System.currentTimeMillis() + REGISTER_FAIL_COOLDOWN_MS);
    }

    private static void releaseFailedAuctionId(Auction auction)
    {
        try
        {
            if (auction != null)
            {
                Manager.getInstance().releaseAuctionId(auction.getId());
            }
        }
        catch (Throwable ignored)
        {
        }
    }

    private static boolean registerAuctionLocked(Auction auction, Bidder owner, Bidder actualOwner, String lockKey)
    {
        try
        {
            if (auction.getId() <= 0)
            {
                AuctionHouse.getInstance().getLogger().warning("AuctionHousAqua: no free auction id was available; auction was not created.");
                releaseFailedAuctionId(auction);
                markAuctionRegisterFailure(lockKey);
                return false;
            }
            if (actualOwner == null || !actualOwner.ensureDatabaseRow() || actualOwner.getId() <= 0)
            {
                AuctionHouse.getInstance().getLogger().severe("Failed to register auction #" + auction.getId() + ": owner bidder is not loaded in database or could not be recreated");
                releaseFailedAuctionId(auction);
                markAuctionRegisterFailure(lockKey);
                return false;
            }

            if (owner != null && owner != actualOwner)
            {
                owner.ensureDatabaseRow();
            }

            if (!PermissionUtil.canCreateAuctionForOwner(actualOwner, 1, true))
            {
                releaseFailedAuctionId(auction);
                return false;
            }

            AuctionHouse.getInstance().getDB().exec(
                "INSERT INTO `auctions` (" +
                "`id` ," +
                "`ownerid` ," +
                "`item` ," +
                "`amount` ," +
                "`timestamp`" +
                ")" +
                "VALUES (?, ?, ?, ?, ?)",
                auction.getId(), auction.getOwnerId(), auction.getConvertItem(),
                auction.getItemAmount(), auction.getEndTimestamp());

            auction.createInitialBidIfMissing();
            Manager.getInstance().addAuction(auction);
            if (owner != null)
            {
                owner.addAuction(auction);
            }
            RedisSync.getInstance().publishAuctionUpsert(auction.getId());

            for (Bidder bidder : Bidder.getInstances().values())
            {
                boolean subscribedToMaterial = false;
                for (ItemStack subscribedItem : bidder.getMatSub())
                {
                    if (subscribedItem != null && subscribedItem.getType() == auction.getItem().getType())
                    {
                        subscribedToMaterial = true;
                        break;
                    }
                }
                if (subscribedToMaterial && !bidder.equals(auction.getOwner()) && bidder.isOnline())
                {
                    bidder.addSubscription(auction);
                    bidder.getPlayer().sendMessage(t("info_new", auction.getId(), auction.getItemType()));
                }
            }

            AUCTION_REGISTER_COOLDOWN.remove(lockKey);
            return true;
        }
        catch (Throwable ex)
        {
            Throwable root = rootCause(ex);
            AuctionHouse.getInstance().getLogger().severe("Failed to register auction #" + auction.getId() + ": " + root.getClass().getName() + ": " + root.getMessage());
            root.printStackTrace();
            releaseFailedAuctionId(auction);
            markAuctionRegisterFailure(lockKey);
            return false;
        }
    }

    private static Throwable rootCause(Throwable throwable)
    {
        Throwable result = throwable;
        while (result.getCause() != null && result.getCause() != result)
        {
            result = result.getCause();
        }
        return result;
    }


    public static void sendInfo(CommandSender sender, Auction auction)
    {
        String output = "";
        if (auction.getItemData() == 0)
        {
            output += t("info_out_1", auction.getId(), auction.getItemType(), auction.getItemAmount());
        }
        else
        {
            output += t("info_out_11", auction.getId(), auction.getItemType(), auction.getItemData(), auction.getItemAmount());
        }

        if (!auction.getItem().getEnchantments().isEmpty())
        {
            output += " " + t("info_out_ench");
            for (Enchantment enchantment : auction.getItem().getEnchantments().keySet())
            {
                output += " " + enchantment.getKey().toString() + ":";
                output += auction.getItem().getEnchantmentLevel(enchantment);
            }
        }
        Bid bid = auction.getBids().peek();
        if (bid.getBidder().equals(auction.getOwner()))
        {
            output += " " + t("info_out_bid", plugin.getEconomy().format(bid.getAmount()));
        }
        else
        {
            if (bid.getBidder() instanceof ServerBidder)
            {
                output += " " + t("info_out_leadserv");
            }
            else
            {
                if (bid.getBidder().getName().equals(sender.getName()))
                {
                    output += " " + t("info_out_lead", bid.getBidder().getName());
                }
                else
                {
                    output += " " + t("info_out_lead2", bid.getBidder().getName());
                }
            }
            output += " " + t("info_out_with", plugin.getEconomy().format(bid.getAmount()));
        }
        AuctionHouseConfiguration config = plugin.getConfiguration();
        if (auction.getAuctionEnd() - System.currentTimeMillis() > 1000L * 60L * 60L * 24L)
        {
            output += " " + t("info_out_end", formatDate(auction.getAuctionEnd(), config.auction_timeFormat));
        }
        else
        {
            output += " " + t("info_out_end2", convertTime(auction.getAuctionEnd() - System.currentTimeMillis()));
        }
        sender.sendMessage(output);
    }

    public static void sendInfo(CommandSender sender, List<Auction> auctionlist)
    {
        int max = auctionlist.size();
        if (max == 0)
        {
            sender.sendMessage(t("i") + " " + t("no_detect"));
        }
        for (int i = 0; i < max; ++i)
        {
            sendInfo(sender, auctionlist.get(i));
        }
    }

    public static String convertTime(long time)
    {
        if (TimeUnit.MILLISECONDS.toMinutes(time) == 0)
        {
            return t("less_time");
        }
        return String.format("%dh %dm",
            TimeUnit.MILLISECONDS.toHours(time),
            TimeUnit.MILLISECONDS.toMinutes(time) -
            TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(time))
        );
    }

    public static String convertItem(ItemStack item)
    {
        try
        {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
            dataOutput.writeObject(item);
            dataOutput.close();
            return ITEM_PREFIX + Base64.getEncoder().encodeToString(outputStream.toByteArray());
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Failed to serialize ItemStack", e);
        }
    }

    public static ItemStack convertItem(String in, int amount)
    {
        ItemStack out = convertItem(in);
        if (out == null)
        {
            return null;
        }
        out.setAmount(amount);
        return out;
    }

    public static ItemStack convertItem(String in)
    {
        if (in == null || in.isEmpty())
        {
            return null;
        }

        if (in.startsWith(ITEM_PREFIX))
        {
            try
            {
                byte[] data = Base64.getDecoder().decode(in.substring(ITEM_PREFIX.length()));
                BukkitObjectInputStream input = new BukkitObjectInputStream(new ByteArrayInputStream(data));
                Object object = input.readObject();
                input.close();
                if (object instanceof ItemStack)
                {
                    return (ItemStack)object;
                }
            }
            catch (IOException e)
            {
                throw new IllegalStateException("Failed to deserialize ItemStack", e);
            }
            catch (ClassNotFoundException e)
            {
                throw new IllegalStateException("Failed to deserialize ItemStack", e);
            }
        }

        Material material = Material.matchMaterial(in);
        if (material != null)
        {
            return new ItemStack(material, 1);
        }
        return null;
    }

    public static short getItemData(ItemStack item)
    {
        if (item == null)
        {
            return 0;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta instanceof Damageable)
        {
            return (short)((Damageable)meta).getDamage();
        }
        return 0;
    }

    public static String formatDate(long time, String pattern)
    {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ROOT);
        return format.format(new Date(time));
    }

    public static String formatDateUtc(long time, String pattern)
    {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(time));
    }

    public static void updateNotifyData(Bidder bidder)
    {
        Database db = AuctionHouse.getInstance().getDB();
        db.execUpdate(
            "UPDATE `bidder` SET `notify`=? WHERE `id`=?",
            bidder.getNotifyState(),
            bidder.getId()
        );
    }
}
