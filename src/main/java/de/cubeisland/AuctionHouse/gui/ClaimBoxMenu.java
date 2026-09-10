package de.cubeisland.AuctionHouse.gui;

import de.cubeisland.AuctionHouse.Auction.AuctionItem;
import de.cubeisland.AuctionHouse.Auction.Bidder;
import de.cubeisland.AuctionHouse.AuctionBox;
import de.cubeisland.AuctionHouse.AuctionHouse;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import static de.cubeisland.AuctionHouse.AuctionHouse.t;

public class ClaimBoxMenu extends AbstractMenu
{
    public enum Mode
    {
        ALL,
        PURCHASED,
        EXPIRED
    }

    private final Mode mode;
    private int page;

    public ClaimBoxMenu(AuctionHouse plugin, Player viewer, Mode mode, int page)
    {
        super(plugin, viewer, plugin.getMenuSettings().size("claim.size", 54), title(plugin, mode, page + 1, 1));
        this.mode = mode;
        this.page = Math.max(0, page);
    }

    private static String title(AuctionHouse plugin, Mode mode, int page, int maxPage)
    {
        String raw;
        switch (mode)
        {
            case PURCHASED:
                raw = plugin.getMenuSettings().title("buying.title", "&8Купленные предметы &8(%page%/%maxPage%)");
                break;
            case EXPIRED:
                raw = plugin.getMenuSettings().title("expire.title", "&8Непроданные предметы &8(%page%/%maxPage%)");
                break;
            case ALL:
            default:
                raw = plugin.getMenuSettings().title("claim.title", "&8Хранилище &8(%page%/%maxPage%)");
                break;
        }
        return raw.replace("%page%", String.valueOf(page)).replace("%maxPage%", String.valueOf(maxPage));
    }

    private List<AuctionItem> getItems()
    {
        AuctionBox box = Bidder.getInstance(this.viewer).getBox();
        switch (this.mode)
        {
            case PURCHASED:
                return new ArrayList<AuctionItem>(box.getPurchasedItems());
            case EXPIRED:
                return new ArrayList<AuctionItem>(box.getExpiredItems());
            case ALL:
            default:
                return new ArrayList<AuctionItem>(box.getItemList());
        }
    }

    @Override
    public void redraw()
    {
        clear();
        List<AuctionItem> items = getItems();
        int perPage = Math.max(1, getInventory().getSize() - 9);
        int maxPage = Math.max(1, (int)Math.ceil(items.size() / (double)perPage));
        if (this.page >= maxPage)
        {
            this.page = maxPage - 1;
        }
        int start = this.page * perPage;
        int end = Math.min(items.size(), start + perPage);
        int slot = 0;
        for (int i = start; i < end; i++)
        {
            AuctionItem auctionItem = items.get(i);
            ItemStack stack = auctionItem.getItem().clone();
            ItemMeta meta = stack.getItemMeta();
            if (meta != null)
            {
                List<String> lore = new ArrayList<String>();
                if (meta.hasLore())
                {
                    lore.addAll(meta.getLore());
                }
                lore.add(" ");
                lore.add("§7" + t("gui_owner") + ": §f" + auctionItem.getOwner());
                lore.add("§7" + t("gui_price") + ": §6" + this.plugin.getEconomy().format(auctionItem.getPrice()));
                lore.add(" ");
                lore.add("§a" + t("gui_click_claim_one"));
                meta.setLore(lore);
                MetaCompat.setItemMeta(stack, meta);
            }
            getInventory().setItem(slot++, stack);
        }
        if (items.isEmpty())
        {
            getInventory().setItem(Math.min(22, getInventory().getSize() - 10), item(Material.PAPER, "&e" + t("gui_empty_box")));
        }
        int base = getInventory().getSize() - 9;
        getInventory().setItem(base, this.page > 0
            ? item(Material.ARROW, "&e← " + t("gui_previous_page"))
            : item(Material.GRAY_STAINED_GLASS_PANE, "&8" + t("gui_no_previous_page")));
        getInventory().setItem(base + 4, item(Material.CHEST, "&f" + t("gui_back_main_menu")));
        getInventory().setItem(base + 8, end < items.size()
            ? item(Material.ARROW, t("gui_next_page") + " &e→")
            : item(Material.GRAY_STAINED_GLASS_PANE, "&8" + t("gui_no_next_page")));
    }

    @Override
    public void onClick(InventoryClickEvent event)
    {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        List<AuctionItem> items = getItems();
        int perPage = Math.max(1, getInventory().getSize() - 9);
        int start = this.page * perPage;
        int end = Math.min(items.size(), start + perPage);
        if (slot >= 0 && slot < end - start)
        {
            AuctionItem item = items.get(start + slot);
            if (Bidder.getInstance(this.viewer).getBox().giveItem(item))
            {
                redraw();
            }
            return;
        }

        int base = getInventory().getSize() - 9;
        if (slot == base && this.page > 0)
        {
            new ClaimBoxMenu(this.plugin, this.viewer, this.mode, this.page - 1).open();
        }
        else if (slot == base + 4)
        {
            MenuFactory.openMain(this.plugin, this.viewer);
        }
        else if (slot == base + 8 && end < items.size())
        {
            new ClaimBoxMenu(this.plugin, this.viewer, this.mode, this.page + 1).open();
        }
    }
}
