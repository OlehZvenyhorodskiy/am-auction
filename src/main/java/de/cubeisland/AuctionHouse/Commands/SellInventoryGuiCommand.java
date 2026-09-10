package de.cubeisland.AuctionHouse.Commands;

import de.cubeisland.AuctionHouse.AbstractCommand;
import de.cubeisland.AuctionHouse.AuctionHouse;
import de.cubeisland.AuctionHouse.BaseCommand;
import de.cubeisland.AuctionHouse.CommandArgs;
import de.cubeisland.AuctionHouse.PermissionUtil;
import de.cubeisland.AuctionHouse.gui.MenuFactory;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static de.cubeisland.AuctionHouse.AuctionHouse.t;

public class SellInventoryGuiCommand extends AbstractCommand
{
    public SellInventoryGuiCommand(BaseCommand base)
    {
        super(base, "sellinventory", "si", "vi");
    }

    @Override
    public boolean execute(CommandSender sender, CommandArgs args)
    {
        if (!(sender instanceof Player player))
        {
            sender.sendMessage(t("gui_only_players"));
            return true;
        }
        if (!PermissionUtil.check(sender, "gui_sell_inventory_perm", "auctionhouse.command.add", "zauctionhouse.sell.inventory", "auctionhouse.*"))
        {
            return true;
        }
        Double price = args.getDouble(0);
        if (price == null || price <= 0)
        {
            player.sendMessage(t("gui_sell_inventory_usage"));
            return true;
        }
        MenuFactory.openSellInventory(AuctionHouse.getInstance(), player, price);
        return true;
    }

    @Override
    public String getDescription()
    {
        return t("command_sell_inventory");
    }
}
