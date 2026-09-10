package de.cubeisland.AuctionHouse.Commands;

import de.cubeisland.AuctionHouse.AbstractCommand;
import de.cubeisland.AuctionHouse.AuctionHouse;
import de.cubeisland.AuctionHouse.BaseCommand;
import de.cubeisland.AuctionHouse.CommandArgs;
import org.bukkit.command.CommandSender;

public class VersionGuiCommand extends AbstractCommand
{
    public VersionGuiCommand(BaseCommand base)
    {
        super(base, "version");
    }

    @Override
    public boolean execute(CommandSender sender, CommandArgs args)
    {
        sender.sendMessage("§eAuctionHousAqua §7v" + AuctionHouse.getInstance().getDescription().getVersion());
        return true;
    }

    @Override
    public String getDescription()
    {
        return "§7Показывает версию плагина.";
    }
}
