package de.cubeisland.AuctionHouse;

import java.io.File;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

public final class MenuSettings
{
    private final YamlConfiguration config;

    public MenuSettings(File file)
    {
        this.config = YamlConfiguration.loadConfiguration(file);
    }

    public String title(String path, String def)
    {
        String title = this.config.getString(path, def).replace('&', '§');
        title = title.replace("AuctionBox", AuctionHouse.t("gui_claim_all"));
        return title;
    }

    public int size(String path, int def)
    {
        int size = this.config.getInt(path, def);
        if (size < 9)
        {
            size = 9;
        }
        if (size > 54)
        {
            size = 54;
        }
        if (size % 9 != 0)
        {
            size = ((size / 9) + 1) * 9;
        }
        return size;
    }

    public Material material(String path, Material def)
    {
        String value = this.config.getString(path);
        if (value == null || value.trim().isEmpty())
        {
            return def;
        }
        Material material = Material.matchMaterial(value.trim());
        return material != null ? material : def;
    }
}
