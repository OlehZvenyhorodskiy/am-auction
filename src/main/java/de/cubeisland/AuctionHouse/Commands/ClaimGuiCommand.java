package de.cubeisland.AuctionHouse.Commands;

import de.cubeisland.AuctionHouse.AbstractCommand;
import de.cubeisland.AuctionHouse.AuctionHouse;
import de.cubeisland.AuctionHouse.BaseCommand;
import de.cubeisland.AuctionHouse.CommandArgs;
import de.cubeisland.AuctionHouse.PermissionUtil;
import de.cubeisland.AuctionHouse.gui.ClaimBoxMenu;
import de.cubeisland.AuctionHouse.gui.MenuFactory;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static de.cubeisland.AuctionHouse.AuctionHouse.t;

public class ClaimGuiCommand extends AbstractCommand
{
    public ClaimGuiCommand(BaseCommand base)
    {
        super(base, "claim");
    }

    @Override
    public boolean execute(CommandSender sender, CommandArgs args)
    {
        if (!(sender instanceof Player player))
        {
            sender.sendMessage(t("gui_only_players"));
            return true;
        }
        if (!PermissionUtil.check(sender, "get_perm", "auctionhouse.command.getitems", "zauctionhouse.claim", "auctionhouse.*"))
        {
            return true;
        }
        MenuFactory.openClaim(AuctionHouse.getInstance(), player, ClaimBoxMenu.Mode.ALL);
        return true;
    }

    @Override
    public String getDescription()
    {
        return t("command_claim");
    }
}
