package de.cubeisland.AuctionHouse.gui;

import java.lang.reflect.Method;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * Paper/Bukkit binary compatibility shim for Player#openInventory.
 *
 * Different Paper/Bukkit API jars expose Player#openInventory(Inventory) with
 * different return descriptors in bytecode: older/local stubs may compile it as
 * void, while modern 1.21.x servers return InventoryView. A direct invokevirtual
 * compiled with the wrong descriptor crashes with NoSuchMethodError before the
 * menu can open. Reflection resolves the method by name/parameters at runtime
 * and ignores the return value, so both variants work.
 *
 * The resolved Method is cached: menus open frequently (every /ah, every click
 * navigation) and looking it up via getMethod on each open wasted CPU and kept
 * reflection metadata busy for no reason.
 */
final class InventoryViewCompat
{
    private static volatile Method openInventoryMethod;

    private InventoryViewCompat()
    {
    }

    static boolean openInventory(Player player, Inventory inventory)
    {
        if (player == null || inventory == null)
        {
            return false;
        }
        Method method = openInventoryMethod;
        if (method == null)
        {
            try
            {
                // Resolve through the interface so the found Method works for every
                // implementation class (CraftPlayer and any mock/test implementations).
                method = Player.class.getMethod("openInventory", Inventory.class);
                openInventoryMethod = method;
            }
            catch (Throwable ignored)
            {
                return false;
            }
        }
        try
        {
            method.invoke(player, inventory);
            return true;
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }
}
