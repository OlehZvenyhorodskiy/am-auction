package de.cubeisland.AuctionHouse.gui;

import de.cubeisland.AuctionHouse.AuctionHouse;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public abstract class AbstractMenu implements InventoryHolder
{
    protected final AuctionHouse plugin;
    protected final Player viewer;
    private final Inventory inventory;

    protected AbstractMenu(AuctionHouse plugin, Player viewer, int size, String title)
    {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    @Override
    public Inventory getInventory()
    {
        return this.inventory;
    }

    public void open()
    {
        redraw();
        InventoryViewCompat.openInventory(this.viewer, this.inventory);
    }

    public abstract void redraw();

    public void onClick(InventoryClickEvent event)
    {
    }

    public void onClose(InventoryCloseEvent event)
    {
    }

    protected void clear()
    {
        this.inventory.clear();
    }

    protected ItemStack item(Material material, String name, String... lore)
    {
        ItemStack stack = new ItemStack(material == null ? Material.BARRIER : material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null)
        {
            if (name != null)
            {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            }
            if (lore != null && lore.length > 0)
            {
                List<String> lines = new ArrayList<String>();
                for (String line : lore)
                {
                    lines.add(ChatColor.translateAlternateColorCodes('&', line));
                }
                meta.setLore(lines);
            }
            meta.addItemFlags(ItemFlag.values());
            MetaCompat.setItemMeta(stack, meta);
        }
        return stack;
    }
}
