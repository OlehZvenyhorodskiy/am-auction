package de.cubeisland.AuctionHouse.gui;

import de.cubeisland.AuctionHouse.Auction.Auction;
import de.cubeisland.AuctionHouse.Auction.Bidder;
import de.cubeisland.AuctionHouse.AuctionBox;
import de.cubeisland.AuctionHouse.AuctionCategory;
import de.cubeisland.AuctionHouse.AuctionHouse;
import de.cubeisland.AuctionHouse.CrossServerSync;
import java.util.List;
import org.bukkit.entity.Player;

public final class MenuFactory
{
    private MenuFactory()
    {
    }

    private static void syncAuctions()
    {
        CrossServerSync.getInstance().syncNow(false);
    }

    public static void openMain(AuctionHouse plugin, Player player)
    {
        syncAuctions();
        new AuctionBrowserMenu(plugin, player, AuctionBrowserMenu.ViewType.ALL, null, null, null, 0).open();
    }

    public static void openSearch(AuctionHouse plugin, Player player, String query)
    {
        syncAuctions();
        new AuctionBrowserMenu(plugin, player, AuctionBrowserMenu.ViewType.SEARCH, null, query, null, 0).open();
    }

    public static void openCategory(AuctionHouse plugin, Player player, AuctionCategory category)
    {
        syncAuctions();
        new AuctionBrowserMenu(plugin, player, AuctionBrowserMenu.ViewType.CATEGORY, category, null, null, 0).open();
    }

    public static void openOwn(AuctionHouse plugin, Player player)
    {
        syncAuctions();
        new AuctionBrowserMenu(plugin, player, AuctionBrowserMenu.ViewType.OWN, null, null, Bidder.getInstance(player), 0).open();
    }

    public static void openAuctionDetails(AuctionHouse plugin, Player player, Auction auction)
    {
        new AuctionDetailsMenu(plugin, player, auction).open();
    }

    public static void openConfirmBuy(AuctionHouse plugin, Player player, Auction auction)
    {
        new ConfirmBuyMenu(plugin, player, auction).open();
    }

    public static void openCategories(AuctionHouse plugin, Player player)
    {
        new CategoriesMenu(plugin, player).open();
    }

    public static void openClaim(AuctionHouse plugin, Player player, ClaimBoxMenu.Mode mode)
    {
        new ClaimBoxMenu(plugin, player, mode, 0).open();
    }

    public static void openSellInventory(AuctionHouse plugin, Player player, double price)
    {
        new SellInventoryMenu(plugin, player, price, 0).open();
    }
}
