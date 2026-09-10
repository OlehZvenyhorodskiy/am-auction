package de.cubeisland.AuctionHouse;

import de.cubeisland.AuctionHouse.Auction.Auction;
import de.cubeisland.AuctionHouse.Auction.AuctionItem;
import de.cubeisland.AuctionHouse.Auction.Bidder;
import static de.cubeisland.AuctionHouse.AuctionHouse.t;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Listener for player and sign events.
 */
public class AuctionHouseListener implements Listener
{
    private static final String PRIMARY_SIGN_HEADER = "[AuctionHousAqua]";
    private final AuctionHouse plugin;
    private final AuctionHouseConfiguration config;
    private final Economy econ;

    public AuctionHouseListener(AuctionHouse plugin)
    {
        this.plugin = plugin;
        this.config = plugin.getConfiguration();
        this.econ = plugin.getEconomy();
    }

    @EventHandler
    public void goesOnline(final PlayerJoinEvent event)
    {
        if (!event.getPlayer().hasPermission("auctionhouse.use")) return;

        Bidder bidder = Bidder.getInstance(event.getPlayer());
        Util.updateNotifyData(bidder);
        plugin.getServer().getScheduler().runTask(plugin, new Runnable()
        {
            @Override
            public void run()
            {
                Bidder bidder = Bidder.getInstance(event.getPlayer());
                if (bidder.hasNotifyState(Bidder.NOTIFY_WIN))
                {
                    event.getPlayer().sendMessage(t("i") + " " + t("event_new"));
                    bidder.unsetNotifyState(Bidder.NOTIFY_WIN);
                }
                if (bidder.hasNotifyState(Bidder.NOTIFY_CANCEL))
                {
                    event.getPlayer().sendMessage(t("i") + " " + t("event_fail"));
                    bidder.unsetNotifyState(Bidder.NOTIFY_CANCEL);
                }
                if (bidder.hasNotifyState(Bidder.NOTIFY_ITEMS))
                {
                    event.getPlayer().sendMessage(t("i") + " " + t("event_old", config.auction_itemBoxLength));
                    bidder.unsetNotifyState(Bidder.NOTIFY_ITEMS);
                }
            }
        });
    }

    @EventHandler
    public void goesOffline(PlayerQuitEvent event)
    {
        Bidder bidder = Bidder.getInstance(event.getPlayer());
        AuctionBox items = bidder.getBox();

        if (!items.getItemList().isEmpty())
        {
            Iterator<AuctionItem> iterator = items.getItemList().iterator();
            while (iterator.hasNext())
            {
                AuctionItem item = iterator.next();
                if (System.currentTimeMillis() - item.getDate() > config.auction_itemBoxLength * 24L * 60L * 60L * 1000L)
                {
                    iterator.remove();
                }
            }
        }
        if (!items.getItemList().isEmpty())
        {
            bidder.setNotifyState(Bidder.NOTIFY_ITEMS);
        }
        Util.updateNotifyData(bidder);
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event)
    {
        String firstLine = event.getLine(0);
        if (firstLine == null)
        {
            return;
        }

        if (firstLine.equalsIgnoreCase(PRIMARY_SIGN_HEADER) || firstLine.equalsIgnoreCase("[AuctionHouse]") || firstLine.equalsIgnoreCase("[ah]") || firstLine.equalsIgnoreCase("[aquaah]"))
        {
            Player player = event.getPlayer();
            String secondLine = event.getLine(1) == null ? "" : event.getLine(1);
            if (secondLine.equalsIgnoreCase("AuctionBox") || secondLine.equalsIgnoreCase("box"))
            {
                if (!Perm.sign_create_box.check(player))
                {
                    event.setCancelled(true);
                    return;
                }
                event.setLine(1, "AuctionBox");
                event.setLine(2, "");
                event.setLine(3, "");
            }
            else if (secondLine.equalsIgnoreCase("Start"))
            {
                if (!Perm.sign_create_add.check(player))
                {
                    event.setCancelled(true);
                    return;
                }
                if (Util.convertTimeToMillis(event.getLine(2)) < 0)
                {
                    player.sendMessage(t("event_sign_fail"));
                    event.setCancelled(true);
                    return;
                }
                event.setLine(1, "Start");
            }
            else if (secondLine.equalsIgnoreCase("List") || secondLine.equalsIgnoreCase("AuctionSearch"))
            {
                if (!Perm.sign_create_list.check(player))
                {
                    event.setCancelled(true);
                    return;
                }
                Material material = Material.matchMaterial(event.getLine(2));
                event.setLine(2, material != null ? material.toString() : "# All #");
                event.setLine(1, "AuctionSearch");
                event.setLine(3, "");
            }
            else
            {
                player.sendMessage(t("event_sign_fail"));
                event.setCancelled(true);
                return;
            }
            player.sendMessage(t("event_sign_create"));
            event.setLine(0, PRIMARY_SIGN_HEADER);
        }
    }

    @EventHandler
    public void onBreakBlock(BlockBreakEvent event)
    {
        final Player player = event.getPlayer();
        final Block block = event.getBlock();
        if (player.isSneaking())
        {
            return;
        }

        if (isAuctionSign(block))
        {
            event.setCancelled(true);
            block.getState().update();
            return;
        }

        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST})
        {
            Block relative = block.getRelative(face);
            if (!isAuctionSign(relative))
            {
                continue;
            }

            BlockData data = relative.getBlockData();
            if (data instanceof WallSign)
            {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event)
    {
        final Player player = event.getPlayer();
        final Block block = event.getClickedBlock();
        if (block == null)
        {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK)
        {
            return;
        }
        if (!(block.getState() instanceof Sign))
        {
            return;
        }

        Sign sign = (Sign)block.getState();
        if (!(PRIMARY_SIGN_HEADER.equalsIgnoreCase(sign.getLine(0)) || "[AuctionHouse]".equalsIgnoreCase(sign.getLine(0))))
        {
            return;
        }

        event.setCancelled(true);
        if (sign.getLine(1).equals("AuctionBox"))
        {
            if (!Perm.sign_auctionbox.check(player)) return;
            if (!(Bidder.getInstance(player).getBox().giveNextItem()))
            {
                player.sendMessage(t("i") + " " + t("time_sign_empty"));
            }
            return;
        }
        if (sign.getLine(1).equals("Start"))
        {
            if (!Perm.sign_start.check(player)) return;

            ItemStack handItem = player.getInventory().getItemInMainHand();
            if (handItem == null || handItem.getType().equals(Material.AIR))
            {
                player.sendMessage(t("pro") + " " + t("add_sell_hand"));
                return;
            }

            long length = Util.convertTimeToMillis(sign.getLine(2));
            if (length == -1)
            {
                return;
            }

            double startbid;
            try
            {
                startbid = Double.parseDouble(sign.getLine(3));
            }
            catch (NumberFormatException ex)
            {
                startbid = 0.0;
            }

            for (ItemStack item : config.auction_blacklist)
            {
                if (item.getType().equals(handItem.getType()))
                {
                    player.sendMessage(t("e") + " " + t("add_blacklist"));
                    return;
                }
            }

            if (!PermissionUtil.canCreateAuctions(player, 1))
            {
                return;
            }

            Auction newAuction = new Auction(handItem.clone(),
                Bidder.getInstance(player),
                System.currentTimeMillis() + length,
                startbid);
            if (!(Util.registerAuction(newAuction, player)))
            {
                player.sendMessage(t("i") + " " + t("add_max_auction", config.auction_maxAuctions_overall));
            }
            else
            {
                player.getInventory().removeItem(handItem.clone());
                player.sendMessage(t("i") + " " + t("add_start", 1,
                    newAuction.getItemType() + "x" + newAuction.getItemAmount(),
                    econ.format(startbid),
                    Util.formatDate(newAuction.getAuctionEnd(), config.auction_timeFormat)));
            }
            return;
        }
        if (sign.getLine(1).equals("AuctionSearch"))
        {
            if (!Perm.sign_list.check(player)) return;
            List<Auction> auctions;
            if (sign.getLine(2).equals("# All #"))
            {
                auctions = Manager.getInstance().getAuctions();
                Sorter.DATE.sortAuction(auctions);
            }
            else
            {
                Material material = Material.matchMaterial(sign.getLine(2));
                if (material == null)
                {
                    player.sendMessage(t("no_detect"));
                    return;
                }
                auctions = Manager.getInstance().getAuctionItem(new ItemStack(material, 1));
                Sorter.DATE.sortAuction(auctions);
            }
            if (auctions.isEmpty())
            {
                player.sendMessage(t("no_detect"));
                return;
            }
            Collections.reverse(auctions);
            for (Auction auction : auctions)
            {
                Util.sendInfo(player, auction);
            }
        }
    }

    private boolean isAuctionSign(Block block)
    {
        if (block == null)
        {
            return false;
        }
        if (!(block.getState() instanceof Sign))
        {
            return false;
        }
        if (!Tag.ALL_SIGNS.isTagged(block.getType()))
        {
            return false;
        }
        Sign sign = (Sign)block.getState();
        return PRIMARY_SIGN_HEADER.equalsIgnoreCase(sign.getLine(0)) || "[AuctionHouse]".equalsIgnoreCase(sign.getLine(0));
    }
}
