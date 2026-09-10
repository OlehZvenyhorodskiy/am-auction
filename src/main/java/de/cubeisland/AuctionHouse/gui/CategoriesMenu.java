package de.cubeisland.AuctionHouse.gui;

import de.cubeisland.AuctionHouse.AuctionCategory;
import de.cubeisland.AuctionHouse.AuctionHouse;
import de.cubeisland.AuctionHouse.Manager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import static de.cubeisland.AuctionHouse.AuctionHouse.t;

public class CategoriesMenu extends AbstractMenu
{
    public CategoriesMenu(AuctionHouse plugin, Player viewer)
    {
        super(plugin, viewer, plugin.getMenuSettings().size("categories.size", 54), plugin.getMenuSettings().title("categories.title", "&8Категории"));
    }

    @Override
    public void redraw()
    {
        clear();
        int[] slots = new int[]{20, 21, 22, 23, 24, 29, 30};
        int index = 0;
        for (AuctionCategory category : AuctionCategory.values())
        {
            int count = (int)Manager.getInstance().getEndingAuctions().stream().filter(a -> category.matches(a.getItem().getType())).count();
            getInventory().setItem(slots[index++], item(category.getIcon(), "&a" + t(category.getTranslationKey()),
                "&7" + t("gui_items_in_category", count),
                " ",
                "&e" + t("gui_click_open_category")));
        }
        getInventory().setItem(49, item(Material.BARRIER, "&c" + t("gui_back_main_menu")));
    }

    @Override
    public void onClick(InventoryClickEvent event)
    {
        event.setCancelled(true);
        if (event.getRawSlot() == 49)
        {
            MenuFactory.openMain(this.plugin, this.viewer);
            return;
        }
        int[] slots = new int[]{20, 21, 22, 23, 24, 29, 30};
        AuctionCategory[] values = AuctionCategory.values();
        for (int i = 0; i < slots.length && i < values.length; i++)
        {
            if (event.getRawSlot() == slots[i])
            {
                MenuFactory.openCategory(this.plugin, this.viewer, values[i]);
                return;
            }
        }
    }
}
