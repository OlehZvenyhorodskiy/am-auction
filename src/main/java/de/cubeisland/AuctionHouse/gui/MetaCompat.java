package de.cubeisland.AuctionHouse.gui;

import java.lang.reflect.Method;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Paper/Bukkit binary compatibility shim for ItemStack#setItemMeta.
 *
 * Some locally patched builds were compiled against an API where setItemMeta
 * had descriptor (ItemMeta)V, while Paper 1.21.x exposes it as
 * (ItemMeta)Z. A direct call can therefore throw NoSuchMethodError when a GUI
 * tries to open the details/claim menu. Reflection ignores the return type and
 * safely calls the method that is present on the running server.
 *
 * The resolved Method is cached: every menu item build calls this shim, so the
 * per-call getMethod lookup was pure overhead.
 */
final class MetaCompat
{
    private static volatile Method setItemMetaMethod;

    private MetaCompat()
    {
    }

    static boolean setItemMeta(ItemStack stack, ItemMeta meta)
    {
        if (stack == null || meta == null)
        {
            return false;
        }
        Method method = setItemMetaMethod;
        if (method == null)
        {
            try
            {
                method = ItemStack.class.getMethod("setItemMeta", ItemMeta.class);
                setItemMetaMethod = method;
            }
            catch (Throwable ignored)
            {
                return false;
            }
        }
        try
        {
            Object result = method.invoke(stack, meta);
            return !(result instanceof Boolean) || ((Boolean)result).booleanValue();
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }
}
