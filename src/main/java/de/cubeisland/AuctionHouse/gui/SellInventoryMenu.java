package de.cubeisland.AuctionHouse.gui;

import de.cubeisland.AuctionHouse.Auction.Auction;
import de.cubeisland.AuctionHouse.Auction.Bidder;
import de.cubeisland.AuctionHouse.AuctionHouse;
import de.cubeisland.AuctionHouse.AuctionHouseConfiguration;
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

public class SellInventoryMenu extends AbstractMenu
{
    private final double price;
    private int page;

    public SellInventoryMenu(AuctionHouse plugin, Player viewer, double price, int page)
    {
        super(plugin, viewer, plugin.getMenuSettings().size("sell.size", 54), plugin.getMenuSettings().title("sell.title", "&8Продажа инвентаря"));
        this.price = price;
        this.page = Math.max(0, page);
    }

    private List<Integer> sellableSlots()
    {
        List<Integer> result = new ArrayList<Integer>();
        for (int i = 0; i < this.viewer.getInventory().getSize(); i++)
        {
            ItemStack item = this.viewer.getInventory().getItem(i);
            if (item != null && item.getType() != Material.AIR)
            {
                result.add(i);
            }
        }
        return result;
    }

    @Override
    public void redraw()
    {
        clear();
        List<Integer> slots = sellableSlots();
        int perPage = Math.max(1, getInventory().getSize() - 9);
        int maxPage = Math.max(1, (int)Math.ceil(slots.size() / (double)perPage));
        if (this.page >= maxPage)
        {
            this.page = maxPage - 1;
        }
        int start = this.page * perPage;
        int end = Math.min(slots.size(), start + perPage);
        int slot = 0;
        for (int i = start; i < end; i++)
        {
            ItemStack original = this.viewer.getInventory().getItem(slots.get(i));
            ItemStack display = original.clone();
            ItemMeta meta = display.getItemMeta();
            if (meta != null)
            {
                List<String> lore = new ArrayList<String>();
                if (meta.hasLore())
                {
                    lore.addAll(meta.getLore());
                }
                lore.add(" ");
                lore.add("§7" + t("gui_sell_price") + ": §6" + this.plugin.getEconomy().format(this.price));
                lore.add("§a" + t("gui_click_list_item"));
                meta.setLore(lore);
                MetaCompat.setItemMeta(display, meta);
            }
            getInventory().setItem(slot++, display);
        }
        int base = getInventory().getSize() - 9;
        getInventory().setItem(base, this.page > 0 ? item(Material.ARROW, "&e← " + t("gui_previous_page")) : item(Material.GRAY_STAINED_GLASS_PANE, "&8" + t("gui_no_previous_page")));
        getInventory().setItem(base + 4, item(Material.BARRIER, "&c" + t("gui_back_main_menu")));
        getInventory().setItem(base + 8, end < slots.size() ? item(Material.ARROW, t("gui_next_page") + " &e→") : item(Material.GRAY_STAINED_GLASS_PANE, "&8" + t("gui_no_next_page")));
    }

    @Override
    public void onClick(InventoryClickEvent event)
    {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        List<Integer> sellable = sellableSlots();
        int perPage = Math.max(1, getInventory().getSize() - 9);
        int start = this.page * perPage;
        int end = Math.min(sellable.size(), start + perPage);
        if (slot >= 0 && slot < end - start)
        {
            int playerSlot = sellable.get(start + slot);
            ItemStack original = this.viewer.getInventory().getItem(playerSlot);
            if (original == null || original.getType() == Material.AIR)
            {
                redraw();
                return;
            }
            AuctionHouseConfiguration config = this.plugin.getConfiguration();
            if (!PermissionUtil.canCreateAuctions(this.viewer, 1))
            {
                return;
            }
            ItemStack toSell = original.clone();
            if (config.auction_blacklist.stream().anyMatch(item -> item.getType() == toSell.getType()))
            {
                this.viewer.sendMessage(t("add_blacklist"));
                return;
            }
            Auction auction = new Auction(toSell, Bidder.getInstance(this.viewer), System.currentTimeMillis() + config.auction_standardLength, this.price);
            if (Util.registerAuction(auction, Bidder.getInstance(this.viewer)))
            {
                this.viewer.getInventory().setItem(playerSlot, null);
                this.viewer.sendMessage(t("gui_sell_created", toSell.getType().toString() + " x" + toSell.getAmount(), this.plugin.getEconomy().format(this.price)));
                redraw();
            }
            else
            {
                this.viewer.sendMessage(t("add_all_stop"));
            }
            return;
        }
        int base = getInventory().getSize() - 9;
        if (slot == base && this.page > 0)
        {
            new SellInventoryMenu(this.plugin, this.viewer, this.price, this.page - 1).open();
        }
        else if (slot == base + 4)
        {
            MenuFactory.openMain(this.plugin, this.viewer);
        }
        else if (slot == base + 8 && end < sellable.size())
        {
            new SellInventoryMenu(this.plugin, this.viewer, this.price, this.page + 1).open();
        }
    }
}
