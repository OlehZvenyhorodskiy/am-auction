package de.cubeisland.AuctionHouse.gui;

import de.cubeisland.AuctionHouse.Auction.Auction;
import de.cubeisland.AuctionHouse.Auction.Bidder;
import de.cubeisland.AuctionHouse.AuctionCategory;
import de.cubeisland.AuctionHouse.AuctionHouse;
import de.cubeisland.AuctionHouse.Manager;
import de.cubeisland.AuctionHouse.PermissionUtil;
import de.cubeisland.AuctionHouse.Util;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import static de.cubeisland.AuctionHouse.AuctionHouse.t;

public class AuctionBrowserMenu extends AbstractMenu
{
    public enum ViewType
    {
        ALL,
        SEARCH,
        CATEGORY,
        OWN
    }

    private final ViewType viewType;
    private final AuctionCategory category;
    private final String query;
    private final Bidder owner;
    private int page;

    public AuctionBrowserMenu(AuctionHouse plugin, Player viewer, ViewType viewType, AuctionCategory category, String query, Bidder owner, int page)
    {
        super(plugin, viewer, plugin.getMenuSettings().size("auction.size", 54), buildTitle(plugin, viewType, category, query, page + 1, 1));
        this.viewType = viewType;
        this.category = category;
        this.query = query;
        this.owner = owner;
        this.page = Math.max(0, page);
    }

    private static String buildTitle(AuctionHouse plugin, ViewType viewType, AuctionCategory category, String query, int page, int maxPage)
    {
        String key;
        switch (viewType)
        {
            case SEARCH:
                key = "search.title";
                break;
            case CATEGORY:
                key = "category.title";
                break;
            case OWN:
                key = "items.title";
                break;
            case ALL:
            default:
                key = "auction.title";
                break;
        }
        String title = plugin.getMenuSettings().title(key, "&8Аукцион &8(%page%/%maxPage%)");
        title = title.replace("%page%", String.valueOf(page)).replace("%maxPage%", String.valueOf(maxPage));
        if (category != null)
        {
            title = title.replace("%category%", t(category.getTranslationKey()));
        }
        if (query != null)
        {
            title = title.replace("%query%", query);
        }
        return title;
    }

    private List<Auction> getAuctions()
    {
        List<Auction> auctions = new ArrayList<Auction>(Manager.getInstance().getEndingAuctions());
        auctions.removeIf(auction -> auction.getAuctionEnd() <= System.currentTimeMillis());
        if (this.viewType == ViewType.OWN && this.owner != null)
        {
            auctions.removeIf(auction -> !auction.getOwner().equals(this.owner));
        }
        if (this.viewType == ViewType.CATEGORY && this.category != null)
        {
            auctions.removeIf(auction -> !this.category.matches(auction.getItem().getType()));
        }
        if (this.viewType == ViewType.SEARCH && this.query != null && !this.query.isBlank())
        {
            String q = this.query.toLowerCase(Locale.ROOT);
            auctions.removeIf(auction -> !matchesQuery(auction, q));
        }
        return auctions;
    }

    private boolean matchesQuery(Auction auction, String q)
    {
        if (auction.getItemType().toLowerCase(Locale.ROOT).contains(q))
        {
            return true;
        }
        ItemMeta meta = auction.getItem().getItemMeta();
        if (meta != null)
        {
            if (meta.hasDisplayName() && meta.getDisplayName().toLowerCase(Locale.ROOT).contains(q))
            {
                return true;
            }
            if (meta.hasLore())
            {
                for (String line : meta.getLore())
                {
                    if (line.toLowerCase(Locale.ROOT).contains(q))
                    {
                        return true;
                    }
                }
            }
        }
        return auction.getOwner().getName().toLowerCase(Locale.ROOT).contains(q);
    }

    @Override
    public void redraw()
    {
        clear();
        List<Auction> auctions = getAuctions();
        int perPage = Math.max(1, getInventory().getSize() - 9);
        int maxPage = Math.max(1, (int)Math.ceil(auctions.size() / (double)perPage));
        if (this.page >= maxPage)
        {
            this.page = maxPage - 1;
        }
        int start = this.page * perPage;
        int end = Math.min(auctions.size(), start + perPage);
        int base = getInventory().getSize() - 9;

        getInventory().clear();
        getInventory().setItem(base, this.page > 0
            ? item(Material.ARROW, "&e← " + t("gui_previous_page"))
            : item(Material.GRAY_STAINED_GLASS_PANE, "&8" + t("gui_no_previous_page")));
        getInventory().setItem(base + 1, item(Material.CHEST, "&f" + t("gui_all_auctions")));
        getInventory().setItem(base + 2, item(Material.GRASS_BLOCK, "&a" + t("gui_categories")));
        getInventory().setItem(base + 3, item(Material.HOPPER, "&b" + t("gui_my_items")));
        getInventory().setItem(base + 4, item(Material.CLOCK, "&6" + t("gui_claim_all")));
        getInventory().setItem(base + 5, item(Material.EMERALD, "&a" + t("gui_purchased_items")));
        getInventory().setItem(base + 6, item(Material.CHEST_MINECART, "&e" + t("gui_expired_items")));
        getInventory().setItem(base + 7, item(Material.BARRIER, "&c" + t("gui_close")));
        getInventory().setItem(base + 8, end < auctions.size()
            ? item(Material.ARROW, t("gui_next_page") + " &e→")
            : item(Material.GRAY_STAINED_GLASS_PANE, "&8" + t("gui_no_next_page")));

        if (auctions.isEmpty())
        {
            getInventory().setItem(Math.min(22, getInventory().getSize() - 10), item(Material.PAPER, "&e" + t("gui_empty_auctions")));
            return;
        }

        int slot = 0;
        for (int i = start; i < end; i++)
        {
            Auction auction = auctions.get(i);
            ItemStack stack = auction.getItem().clone();
            ItemMeta meta = stack.getItemMeta();
            if (meta != null)
            {
                List<String> lore = new ArrayList<String>();
                if (meta.hasLore())
                {
                    lore.addAll(meta.getLore());
                }
                lore.add(" ");
                lore.add("§7" + t("gui_price") + ": §6" + this.plugin.getEconomy().format(auction.getCurrentPrice()));
                lore.add("§7" + t("gui_seller") + ": §f" + auction.getOwner().getName());
                lore.add("§7" + t("gui_time_left") + ": §f" + Util.convertTime(auction.getAuctionEnd() - System.currentTimeMillis()));
                lore.add(" ");
                if (auction.getOwner().equals(Bidder.getInstance(this.viewer)))
                {
                    lore.add("§e" + t("gui_click_manage_listing"));
                    lore.add("§c" + t("gui_right_click_remove_listing"));
                }
                else
                {
                    lore.add("§a" + t("gui_click_open_listing"));
                }
                meta.setLore(lore);
                MetaCompat.setItemMeta(stack, meta);
            }
            getInventory().setItem(slot++, stack);
        }
    }

    @Override
    public void onClick(InventoryClickEvent event)
    {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player))
        {
            return;
        }
        int slot = event.getRawSlot();
        List<Auction> auctions = getAuctions();
        int perPage = Math.max(1, getInventory().getSize() - 9);
        int start = this.page * perPage;
        int end = Math.min(auctions.size(), start + perPage);
        if (slot >= 0 && slot < end - start)
        {
            Auction auction = auctions.get(start + slot);
            if (auction.getOwner().equals(Bidder.getInstance(this.viewer)) && event.getClick().isRightClick())
            {
                if (PermissionUtil.hasAny(this.viewer, "auctionhouse.command.delete.id", "zauctionhouse.admin", "auctionhouse.*"))
                {
                    Manager.getInstance().cancelAuction(auction, false);
                    this.viewer.sendMessage(t("rem_id", auction.getId(), this.viewer.getName()));
                    redraw();
                }
                return;
            }
            MenuFactory.openAuctionDetails(this.plugin, this.viewer, auction);
            return;
        }

        int base = getInventory().getSize() - 9;
        if (slot == base && this.page > 0)
        {
            new AuctionBrowserMenu(this.plugin, this.viewer, this.viewType, this.category, this.query, this.owner, this.page - 1).open();
        }
        else if (slot == base + 1)
        {
            MenuFactory.openMain(this.plugin, this.viewer);
        }
        else if (slot == base + 2)
        {
            MenuFactory.openCategories(this.plugin, this.viewer);
        }
        else if (slot == base + 3)
        {
            MenuFactory.openOwn(this.plugin, this.viewer);
        }
        else if (slot == base + 4)
        {
            MenuFactory.openClaim(this.plugin, this.viewer, ClaimBoxMenu.Mode.ALL);
        }
        else if (slot == base + 5)
        {
            MenuFactory.openClaim(this.plugin, this.viewer, ClaimBoxMenu.Mode.PURCHASED);
        }
        else if (slot == base + 6)
        {
            MenuFactory.openClaim(this.plugin, this.viewer, ClaimBoxMenu.Mode.EXPIRED);
        }
        else if (slot == base + 7)
        {
            this.viewer.closeInventory();
        }
        else if (slot == base + 8 && end < auctions.size())
        {
            new AuctionBrowserMenu(this.plugin, this.viewer, this.viewType, this.category, this.query, this.owner, this.page + 1).open();
        }
    }
}
