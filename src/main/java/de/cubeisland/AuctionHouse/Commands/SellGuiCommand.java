package de.cubeisland.AuctionHouse.Commands;

import de.cubeisland.AuctionHouse.AbstractCommand;
import de.cubeisland.AuctionHouse.Auction.Auction;
import de.cubeisland.AuctionHouse.Auction.Bidder;
import de.cubeisland.AuctionHouse.AuctionHouse;
import de.cubeisland.AuctionHouse.AuctionHouseConfiguration;
import de.cubeisland.AuctionHouse.BaseCommand;
import de.cubeisland.AuctionHouse.CommandArgs;
import de.cubeisland.AuctionHouse.PermissionUtil;
import de.cubeisland.AuctionHouse.Util;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import static de.cubeisland.AuctionHouse.AuctionHouse.t;

public class SellGuiCommand extends AbstractCommand
{
    public SellGuiCommand(BaseCommand base)
    {
        super(base, "sell", "s");
    }

    @Override
    public boolean execute(CommandSender sender, CommandArgs args)
    {
        if (!(sender instanceof Player player))
        {
            sender.sendMessage(t("gui_only_players"));
            return true;
        }
        if (!PermissionUtil.check(sender, "gui_sell_perm", "auctionhouse.command.add", "zauctionhouse.sell", "auctionhouse.*"))
        {
            return true;
        }
        Double price = args.getDouble(0);
        if (price == null || price <= 0)
        {
            player.sendMessage(t("gui_sell_usage"));
            return true;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR)
        {
            player.sendMessage(t("add_sell_hand"));
            return true;
        }
        int amount = hand.getAmount();
        Integer providedAmount = args.getInt(1);
        if (providedAmount != null && providedAmount > 0 && providedAmount <= hand.getAmount())
        {
            amount = providedAmount;
        }
        ItemStack toSell = hand.clone();
        toSell.setAmount(amount);
        AuctionHouseConfiguration config = AuctionHouse.getInstance().getConfiguration();
        if (!PermissionUtil.canCreateAuctions(player, 1))
        {
            return true;
        }
        if (config.auction_blacklist.stream().anyMatch(item -> item.getType() == toSell.getType()))
        {
            player.sendMessage(t("add_blacklist"));
            return true;
        }
        Auction auction = new Auction(toSell, Bidder.getInstance(player), System.currentTimeMillis() + config.auction_standardLength, price);
        if (Util.registerAuction(auction, Bidder.getInstance(player)))
        {
            hand.setAmount(hand.getAmount() - amount);
            if (hand.getAmount() <= 0)
            {
                player.getInventory().setItemInMainHand(null);
            }
            player.sendMessage(t("gui_sell_created", toSell.getType().toString() + " x" + toSell.getAmount(), AuctionHouse.getInstance().getEconomy().format(price)));
        }
        else
        {
            player.sendMessage(t("add_all_stop"));
        }
        return true;
    }

    @Override
    public String getDescription()
    {
        return t("command_sell");
    }
}
