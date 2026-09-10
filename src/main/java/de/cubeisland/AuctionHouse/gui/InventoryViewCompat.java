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
 */
final class InventoryViewCompat
{
    private InventoryViewCompat()
    {
    }

    static boolean openInventory(Player player, Inventory inventory)
    {
        if (player == null || inventory == null)
        {
            return false;
        }
        try
        {
            Method method = ((Object)player).getClass().getMethod("openInventory", Inventory.class);
            method.invoke(player, inventory);
            return true;
        }
        catch (Throwable first)
        {
            try
            {
                Method method = Player.class.getMethod("openInventory", Inventory.class);
                method.invoke(player, inventory);
                return true;
            }
            catch (Throwable ignored)
            {
                return false;
            }
        }
    }
}
