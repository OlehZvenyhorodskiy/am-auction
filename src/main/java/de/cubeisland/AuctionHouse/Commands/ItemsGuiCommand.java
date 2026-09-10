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

public class ItemsGuiCommand extends AbstractCommand
{
    public ItemsGuiCommand(BaseCommand base)
    {
        super(base, "items");
    }

    @Override
    public boolean execute(CommandSender sender, CommandArgs args)
    {
        if (!(sender instanceof Player player))
        {
            sender.sendMessage(t("gui_only_players"));
            return true;
        }
        if (!PermissionUtil.check(sender, "help_perm", "auctionhouse.use", "zauctionhouse.use", "auctionhouse.*"))
        {
            return true;
        }
        MenuFactory.openOwn(AuctionHouse.getInstance(), player);
        return true;
    }

    @Override
    public String getDescription()
    {
        return t("command_items");
    }
}
