package de.cubeisland.AuctionHouse;

import org.bukkit.Material;

public enum AuctionCategory
{
    BLOCKS("blocks", "gui_category_blocks_name", Material.GRASS_BLOCK),
    WEAPONS("weapons", "gui_category_weapons_name", Material.DIAMOND_SWORD),
    TOOLS("tools", "gui_category_tools_name", Material.IRON_PICKAXE),
    ARMOR("armor", "gui_category_armor_name", Material.DIAMOND_CHESTPLATE),
    FOOD("food", "gui_category_food_name", Material.GOLDEN_CARROT),
    POTIONS("potions", "gui_category_potions_name", Material.POTION),
    MISC("misc", "gui_category_misc_name", Material.CHEST);

    private final String id;
    private final String translationKey;
    private final Material icon;

    AuctionCategory(String id, String translationKey, Material icon)
    {
        this.id = id;
        this.translationKey = translationKey;
        this.icon = icon;
    }

    public String getId()
    {
        return this.id;
    }

    public String getTranslationKey()
    {
        return this.translationKey;
    }

    public Material getIcon()
    {
        return this.icon;
    }

    public boolean matches(Material material)
    {
        if (material == null)
        {
            return false;
        }

        switch (this)
        {
            case BLOCKS:
                return material.isBlock();
            case WEAPONS:
                return endsWith(material, "_SWORD", "_AXE", "_MACE", "_BOW", "_CROSSBOW", "TRIDENT");
            case TOOLS:
                return endsWith(material, "_PICKAXE", "_AXE", "_SHOVEL", "_HOE", "_SHEARS", "FISHING_ROD", "FLINT_AND_STEEL");
            case ARMOR:
                return endsWith(material, "_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS", "ELYTRA", "SHIELD");
            case FOOD:
                return material.isEdible();
            case POTIONS:
                return endsWith(material, "POTION", "SPLASH_POTION", "LINGERING_POTION", "TIPPED_ARROW", "BREWING_STAND", "BLAZE_ROD");
            case MISC:
            default:
                return !BLOCKS.matches(material) && !WEAPONS.matches(material) && !TOOLS.matches(material)
                    && !ARMOR.matches(material) && !FOOD.matches(material) && !POTIONS.matches(material);
        }
    }

    private static boolean endsWith(Material material, String... suffixes)
    {
        String name = material.name();
        for (String suffix : suffixes)
        {
            if (name.endsWith(suffix) || name.equals(suffix))
            {
                return true;
            }
        }
        return false;
    }

    public static AuctionCategory byId(String id)
    {
        if (id == null)
        {
            return null;
        }
        for (AuctionCategory category : values())
        {
            if (category.id.equalsIgnoreCase(id))
            {
                return category;
            }
        }
        return null;
    }
}
