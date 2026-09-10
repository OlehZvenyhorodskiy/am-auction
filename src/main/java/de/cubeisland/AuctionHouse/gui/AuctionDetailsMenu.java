package de.cubeisland.AuctionHouse.gui;

import de.cubeisland.AuctionHouse.Auction.Auction;
import de.cubeisland.AuctionHouse.Auction.Bidder;
import de.cubeisland.AuctionHouse.AuctionHouse;
import de.cubeisland.AuctionHouse.CrossServerSync;
import de.cubeisland.AuctionHouse.Manager;
import de.cubeisland.AuctionHouse.PermissionUtil;
import de.cubeisland.AuctionHouse.Util;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import static de.cubeisland.AuctionHouse.AuctionHouse.t;

public class AuctionDetailsMenu extends AbstractMenu
{
    private final Auction auction;

    public AuctionDetailsMenu(AuctionHouse plugin, Player viewer, Auction auction)
    {
        super(plugin, viewer, plugin.getMenuSettings().size("show.size", 27), plugin.getMenuSettings().title("show.title", "&8Лот"));
        this.auction = auction;
    }

    @Override
    public void redraw()
    {
        clear();
        ItemStack display = this.auction.getItem().clone();
        ItemMeta meta = display.getItemMeta();
        if (meta != null)
        {
            List<String> lore = new ArrayList<String>();
            if (meta.hasLore())
            {
                lore.addAll(meta.getLore());
            }
            lore.add(" ");
            lore.add("§7ID: §f#" + this.auction.getId());
            lore.add("§7" + t("gui_seller") + ": §f" + this.auction.getOwner().getName());
            lore.add("§7" + t("gui_price") + ": §6" + this.plugin.getEconomy().format(this.auction.getCurrentPrice()));
            lore.add("§7" + t("gui_time_left") + ": §f" + Util.convertTime(this.auction.getAuctionEnd() - System.currentTimeMillis()));
            if (this.auction.hasExternalBidder())
            {
                lore.add("§7" + t("gui_current_bidder") + ": §f" + this.auction.getBids().peek().getBidder().getName());
            }
            meta.setLore(lore);
            MetaCompat.setItemMeta(display, meta);
        }
        getInventory().setItem(13, display);
        getInventory().setItem(15, item(Material.BARRIER, "&c" + t("gui_back_main_menu")));
        if (this.auction.getOwner().equals(Bidder.getInstance(this.viewer)))
        {
            getInventory().setItem(11, item(Material.REDSTONE, "&c" + t("gui_remove_listing"), "&7" + t("gui_click_remove_listing")));
        }
        else
        {
            getInventory().setItem(11, item(Material.EMERALD, "&a" + t("gui_buy_now"), "&7" + t("gui_buy_for", this.plugin.getEconomy().format(this.auction.getCurrentPrice()))));
        }
    }

    @Override
    public void onClick(InventoryClickEvent event)
    {
        event.setCancelled(true);
        if (event.getRawSlot() == 15)
        {
            MenuFactory.openMain(this.plugin, this.viewer);
            return;
        }
        if (event.getRawSlot() != 11)
        {
            return;
        }
        CrossServerSync.getInstance().syncNow(false);
        // buyfix150: exact MySQL revalidation for the clicked lot. This keeps Redis fast but
        // prevents local stale/ghost cache entries on the selling server from rejecting purchases.
        this.plugin.getDB().loadAuctionById(this.auction.getId());
        Auction freshAuction = Manager.getInstance().getAuction(this.auction.getId());
        if (freshAuction == null)
        {
            this.viewer.sendMessage(t("gui_buy_already_sold"));
            MenuFactory.openMain(this.plugin, this.viewer);
            return;
        }
        if (freshAuction.getOwner().equals(Bidder.getInstance(this.viewer)))
        {
            if (PermissionUtil.hasAny(this.viewer, "auctionhouse.command.delete.id", "zauctionhouse.admin", "auctionhouse.*"))
            {
                Manager.getInstance().cancelAuction(freshAuction, false);
                this.viewer.sendMessage(t("rem_id", freshAuction.getId(), this.viewer.getName()));
                MenuFactory.openOwn(this.plugin, this.viewer);
            }
        }
        else
        {
            MenuFactory.openConfirmBuy(this.plugin, this.viewer, freshAuction);
        }
    }
}
