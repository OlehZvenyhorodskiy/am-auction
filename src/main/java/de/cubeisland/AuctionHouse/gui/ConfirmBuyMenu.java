package de.cubeisland.AuctionHouse.gui;

import de.cubeisland.AuctionHouse.Auction.Auction;
import de.cubeisland.AuctionHouse.Auction.Bidder;
import de.cubeisland.AuctionHouse.AuctionHouse;
import de.cubeisland.AuctionHouse.CrossServerSync;
import de.cubeisland.AuctionHouse.Manager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import static de.cubeisland.AuctionHouse.AuctionHouse.t;

public class ConfirmBuyMenu extends AbstractMenu
{
    private final Auction auction;

    public ConfirmBuyMenu(AuctionHouse plugin, Player viewer, Auction auction)
    {
        super(plugin, viewer, plugin.getMenuSettings().size("buyconfirm.size", 27), plugin.getMenuSettings().title("buyconfirm.title", "&8Подтверждение покупки"));
        this.auction = auction;
    }

    @Override
    public void redraw()
    {
        clear();
        for (int i = 0; i < getInventory().getSize(); i++)
        {
            getInventory().setItem(i, item(Material.GRAY_STAINED_GLASS_PANE, " "));
        }
        getInventory().setItem(11, item(Material.LIME_STAINED_GLASS_PANE, "&a" + t("gui_confirm_purchase"), "&7" + t("gui_buy_for", this.plugin.getEconomy().format(this.auction.getCurrentPrice()))));
        getInventory().setItem(13, this.auction.getItem().clone());
        getInventory().setItem(15, item(Material.RED_STAINED_GLASS_PANE, "&c" + t("gui_cancel_purchase")));
    }

    @Override
    public void onClick(InventoryClickEvent event)
    {
        event.setCancelled(true);
        if (event.getRawSlot() == 15)
        {
            MenuFactory.openAuctionDetails(this.plugin, this.viewer, this.auction);
            return;
        }
        if (event.getRawSlot() == 11)
        {
            CrossServerSync.getInstance().syncNow(false);
            // buyfix150: when Redis is running, syncNow() intentionally avoids a full reload.
            // Re-read exactly this auction from MySQL so local ghost/stale rows made on the selling
            // server cannot block purchase while another linked server can still buy it.
            this.plugin.getDB().loadAuctionById(this.auction.getId());
            Auction freshAuction = Manager.getInstance().getAuction(this.auction.getId());
            if (freshAuction == null)
            {
                this.viewer.sendMessage(t("gui_buy_already_sold"));
                this.viewer.closeInventory();
                return;
            }
            if (Manager.getInstance().buyNow(freshAuction, Bidder.getInstance(this.viewer)))
            {
                MenuFactory.openClaim(this.plugin, this.viewer, ClaimBoxMenu.Mode.PURCHASED);
            }
            else
            {
                this.viewer.closeInventory();
            }
        }
    }
}
