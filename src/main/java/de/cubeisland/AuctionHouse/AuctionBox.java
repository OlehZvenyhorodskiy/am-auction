package de.cubeisland.AuctionHouse;

import de.cubeisland.AuctionHouse.Auction.Auction;
import de.cubeisland.AuctionHouse.Auction.AuctionItem;
import de.cubeisland.AuctionHouse.Auction.Bidder;
import static de.cubeisland.AuctionHouse.AuctionHouse.t;
import de.cubeisland.AuctionHouse.Database.Database;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Represents a box containing all items from finished auctions.
 */
public class AuctionBox
{
    private LinkedList<AuctionItem> itemList;
    private final Bidder bidder;
    private Economy econ = AuctionHouse.getInstance().getEconomy();
    private final Database db;

    public AuctionBox(Bidder bidder)
    {
        this.db = AuctionHouse.getInstance().getDB();
        this.bidder = bidder;
        this.itemList = new LinkedList<AuctionItem>();
    }

    public void addItem(Auction auction)
    {
        this.itemList.add(new AuctionItem(auction));
        RedisSync.getInstance().publishInventoryUpdate(this.bidder.getId());
    }

    public boolean giveNextItem()
    {
        Player player = this.bidder.getPlayer();

        if (this.itemList.isEmpty())
        {
            return false;
        }

        AuctionItem auctionItem = this.itemList.getFirst();
        ItemStack item = auctionItem.getItem();
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(auctionItem.cloneItem().getItem());
        ItemStack tmp = leftovers.isEmpty() ? null : leftovers.values().iterator().next();

        if (auctionItem.getOwner().equals(this.bidder.getName()))
        {
            player.sendMessage(t("i") + " " + t("cont_rec_ab", item.getType().toString() + "x" + item.getAmount()));
        }
        else
        {
            player.sendMessage(t("i") + " " + t("cont_rec", item.getType().toString() + "x" + item.getAmount(),
                econ.format(auctionItem.getPrice()), auctionItem.getOwner(),
                Util.formatDateUtc(auctionItem.getDate(), "MMM dd"))
            );
        }

        if (tmp == null)
        {
            db.execUpdate("DELETE FROM `auctionbox` WHERE `id`=?", auctionItem.getId());
            this.itemList.removeFirst();
            RedisSync.getInstance().publishInventoryUpdate(this.bidder.getId());
            return true;
        }
        else
        {
            player.sendMessage(t("i") + " " + t("cont_rec_remain"));
            db.execUpdate("UPDATE `auctionbox` SET `amount`=? WHERE `id`=?", tmp.getAmount(), auctionItem.getId());
            item.setAmount(tmp.getAmount());
            RedisSync.getInstance().publishInventoryUpdate(this.bidder.getId());
            return true;
        }
    }

    public boolean giveItem(AuctionItem auctionItem)
    {
        if (auctionItem == null || !this.itemList.contains(auctionItem))
        {
            return false;
        }

        Player player = this.bidder.getPlayer();
        if (player == null)
        {
            return false;
        }

        ItemStack item = auctionItem.getItem();
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(auctionItem.cloneItem().getItem());
        ItemStack tmp = leftovers.isEmpty() ? null : leftovers.values().iterator().next();

        if (auctionItem.getOwner().equals(this.bidder.getName()))
        {
            player.sendMessage(t("i") + " " + t("cont_rec_ab", item.getType().toString() + "x" + item.getAmount()));
        }
        else
        {
            player.sendMessage(t("i") + " " + t("cont_rec", item.getType().toString() + "x" + item.getAmount(),
                econ.format(auctionItem.getPrice()), auctionItem.getOwner(),
                Util.formatDateUtc(auctionItem.getDate(), "MMM dd"))
            );
        }

        if (tmp == null)
        {
            db.execUpdate("DELETE FROM `auctionbox` WHERE `id`=?", auctionItem.getId());
            this.itemList.remove(auctionItem);
            RedisSync.getInstance().publishInventoryUpdate(this.bidder.getId());
            return true;
        }
        else
        {
            player.sendMessage(t("i") + " " + t("cont_rec_remain"));
            db.execUpdate("UPDATE `auctionbox` SET `amount`=? WHERE `id`=?", tmp.getAmount(), auctionItem.getId());
            item.setAmount(tmp.getAmount());
            RedisSync.getInstance().publishInventoryUpdate(this.bidder.getId());
            return true;
        }
    }

    public List<AuctionItem> getPurchasedItems()
    {
        return this.itemList.stream().filter(item -> !item.getOwner().equals(this.bidder.getName())).collect(Collectors.toList());
    }

    public List<AuctionItem> getExpiredItems()
    {
        return this.itemList.stream().filter(item -> item.getOwner().equals(this.bidder.getName())).collect(Collectors.toList());
    }

    public LinkedList<AuctionItem> getItemList()
    {
        return this.itemList;
    }
}
