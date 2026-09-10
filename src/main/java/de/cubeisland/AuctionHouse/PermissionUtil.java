package de.cubeisland.AuctionHouse;

import de.cubeisland.AuctionHouse.Auction.Auction;
import de.cubeisland.AuctionHouse.Auction.Bidder;
import de.cubeisland.AuctionHouse.Auction.ServerBidder;
import static de.cubeisland.AuctionHouse.AuctionHouse.t;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

public final class PermissionUtil
{
    private static final int[] AUCTION_SLOT_LIMITS = {5, 7, 9, 11, 13, 15, 20};

    private PermissionUtil()
    {
    }

    public static boolean check(CommandSender sender, String denialKey, String... permissions)
    {
        if (hasAny(sender, permissions))
        {
            return true;
        }
        if (denialKey != null)
        {
            sender.sendMessage(t("perm") + " " + t(denialKey));
        }
        return false;
    }

    public static boolean hasAny(CommandSender sender, String... permissions)
    {
        if (sender == null || permissions == null)
        {
            return false;
        }
        for (String permission : permissions)
        {
            if (permission != null && !permission.isEmpty() && sender.hasPermission(permission))
            {
                return true;
            }
        }
        return false;
    }

    public static int getFiniteAuctionSlotPermissionLimit(CommandSender sender)
    {
        if (sender == null)
        {
            return -1;
        }

        int permissionLimit = -1;
        for (int slotLimit : AUCTION_SLOT_LIMITS)
        {
            if (hasAny(sender,
                    "auctionhouse.slots." + slotLimit,
                    "zauctionhouse.slots." + slotLimit))
            {
                permissionLimit = slotLimit;
            }
        }
        return permissionLimit;
    }

    public static boolean bypassesAuctionSlotLimit(CommandSender sender)
    {
        if (sender == null)
        {
            return false;
        }

        // A finite slot permission must always win. This is important when testing as OP,
        // or when a permission group has wildcard/admin permissions together with a rank slot limit.
        if (getFiniteAuctionSlotPermissionLimit(sender) > -1)
        {
            return false;
        }

        AuctionHouseConfiguration config = AuctionHouse.getInstance().getConfiguration();
        return hasAny(sender,
                "auctionhouse.command.add.nolimit",
                "auctionhouse.command.add.nolomit",
                "auctionhouse.slots.unlimited",
                "zauctionhouse.slots.unlimited")
            || (config.auction_maxAuctions_opIgnore && sender.isOp());
    }

    public static int getAuctionSlotLimit(CommandSender sender)
    {
        AuctionHouseConfiguration config = AuctionHouse.getInstance().getConfiguration();
        int defaultLimit = Math.max(0, config.auction_maxAuctions_player);
        if (sender == null)
        {
            return defaultLimit;
        }

        // If a rank has auctionhouse.slots.N, use exactly the highest N from permissions.
        // Do not let auction.maxAuctions.player accidentally raise this value.
        int permissionLimit = getFiniteAuctionSlotPermissionLimit(sender);
        if (permissionLimit > -1)
        {
            return permissionLimit;
        }

        if (bypassesAuctionSlotLimit(sender))
        {
            return Integer.MAX_VALUE;
        }

        return defaultLimit;
    }

    public static int countOwnActiveAuctions(CommandSender sender)
    {
        if (!(sender instanceof Player))
        {
            return 0;
        }
        return countOwnActiveAuctions(Bidder.getInstance((Player)sender));
    }

    public static int countOwnActiveAuctions(Bidder bidder)
    {
        if (bidder == null || bidder instanceof ServerBidder)
        {
            return 0;
        }

        int count = 0;
        int bidderId = bidder.getId();
        for (Auction auction : Manager.getInstance().getAuctions())
        {
            if (auction == null || auction.getOwner() == null)
            {
                continue;
            }
            if (bidderId >= 0)
            {
                if (auction.getOwner().getId() == bidderId)
                {
                    count++;
                }
            }
            else if (auction.getOwner() == bidder)
            {
                count++;
            }
        }
        return count;
    }

    public static boolean canCreateAuctions(CommandSender sender, int additionalAuctions)
    {
        return canCreateAuctions(sender, additionalAuctions, true);
    }

    public static boolean canCreateAuctions(CommandSender sender, int additionalAuctions, boolean sendMessage)
    {
        if (sender == null || sender instanceof ConsoleCommandSender)
        {
            return true;
        }
        int limit = getAuctionSlotLimit(sender);
        if (limit == Integer.MAX_VALUE)
        {
            return true;
        }
        int requested = Math.max(1, additionalAuctions);
        int current = countOwnActiveAuctions(sender);
        if (current + requested <= limit)
        {
            return true;
        }
        if (sendMessage)
        {
            sender.sendMessage(t("i") + " " + t("add_max_auction", limit));
        }
        return false;
    }

    public static boolean canCreateAuctionForOwner(Bidder owner, int additionalAuctions, boolean sendMessage)
    {
        if (owner == null || owner instanceof ServerBidder)
        {
            return true;
        }
        Player player = owner.getPlayer();
        if (player == null)
        {
            // Offline players cannot start an auction through normal gameplay.
            // Keep this permissive for internal/server-owned operations.
            return true;
        }
        return canCreateAuctions(player, additionalAuctions, sendMessage);
    }
}
