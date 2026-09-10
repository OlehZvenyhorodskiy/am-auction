package de.cubeisland.AuctionHouse.Auction;

import de.cubeisland.AuctionHouse.AuctionHouse;
import static de.cubeisland.AuctionHouse.AuctionHouse.t;
import de.cubeisland.AuctionHouse.AuctionHouseConfiguration;
import de.cubeisland.AuctionHouse.Database.Database;
import de.cubeisland.AuctionHouse.Database.DatabaseEntity;
import de.cubeisland.AuctionHouse.Database.EntityIdentifier;
import de.cubeisland.AuctionHouse.Database.EntityProperty;
import de.cubeisland.AuctionHouse.Manager;
import de.cubeisland.AuctionHouse.Perm;
import de.cubeisland.AuctionHouse.Util;
import java.sql.Timestamp;
import java.util.Stack;
import org.bukkit.inventory.ItemStack;

/**
 * Represents an auction
 *
 * @author Faithcaio
 */
public class Auction implements DatabaseEntity
{
    
    @EntityIdentifier
    private int id;
    @EntityProperty
    private final ItemStack item;
    @EntityProperty
    private Bidder owner;
    @EntityProperty
    private final long auctionEnd;
    @EntityProperty
    private final Stack<Bid> bids;
    
    
    private static final AuctionHouse plugin = AuctionHouse.getInstance();
    private static final AuctionHouseConfiguration config = plugin.getConfiguration();
    private final Database db;
    private final double startBid;
    
/**
 * Creates an new auction. The initial bid is created after the auction row is saved.
 */
    public Auction(ItemStack item, Bidder owner, long auctionEnd, double startBid)
    {
        this.db = AuctionHouse.getInstance().getDB();
        this.id = Manager.getInstance().reserveAuctionId();
        this.item = item;
        this.owner = owner;
        this.auctionEnd = auctionEnd;
        this.startBid = startBid;
        this.bids = new Stack<Bid>();
    }

/**
 * Load in auction from DataBase
 */
    public Auction(int id,ItemStack item, Bidder owner, long auctionEnd)
    {
        Manager.getInstance().getFreeIds().removeElement(id);
        this.db = AuctionHouse.getInstance().getDB();
        this.id = id;
        this.item = item;
        this.owner = owner;
        this.auctionEnd = auctionEnd;
        this.startBid = 0.0D;
        this.bids = new Stack<Bid>();
    }

    public void createInitialBidIfMissing()
    {
        if (this.bids.isEmpty())
        {
            this.bids.push(new Bid(this.owner, this.startBid, this));
        }
    }

/**
 * Adds a bid to auction
 * @return true if bidded succesfully
 */
    public boolean bid(final Bidder bidder, final double amount)//evtl nicht bool / bessere Unterscheidung
    {
        if (amount <= 0)
        {
            bidder.getPlayer().sendMessage(t("e")+" "+t("auc_bid_low1"));
            return false;
        }
        if (amount <= this.bids.peek().getAmount())
        {
            bidder.getPlayer().sendMessage(t("i")+" "+t("auc_bid_low2"));
            return false;
        }
        if ((AuctionHouse.getInstance().getEconomy().getBalance(bidder.getName()) >= amount)
                || Perm.command_bid_infinite.check(bidder.getPlayer()))
        {
            if (AuctionHouse.getInstance().getEconomy().getBalance(bidder.getName()) - bidder.getTotalBidAmount() >= amount
                    || Perm.command_bid_infinite.check(bidder.getPlayer()))
            {
                this.bids.push(new Bid(bidder, amount, this));
                de.cubeisland.AuctionHouse.RedisSync.getInstance().publishAuctionUpsert(this.id);
                return true;
            }
            bidder.getPlayer().sendMessage(t("e")+" "+t("auc_bid_money1"));
            return false;
        }
        bidder.getPlayer().sendMessage(t("e")+" "+t("auc_bid_money2"));
        return false;
    }
    
/**
 * reverts a bid if allowed
 * @return true if reverted succesfully
 */
    public boolean undobid(final Bidder bidder)
    {
        
        Bid bid = this.bids.peek();
        if (bidder != bid.getBidder())
        {
            bidder.getPlayer().sendMessage(t("e")+" "+t("undo_bidder"));
            return false;
        }
        if (bidder == this.owner)
        {
            bidder.getPlayer().sendMessage(t("pro")+" "+t("undo_pro2"));
            return false;
        }
        long undoTime = config.auction_undoTime;
        if (undoTime < 0) //Infinite UndoTime
        {
            undoTime = this.auctionEnd - bid.getTimestamp();
        }
        if ((System.currentTimeMillis() - bid.getTimestamp()) > undoTime)
        {
            bidder.getPlayer().sendMessage(t("e")+" "+t("undo_time"));
            return false;
        }
        //else: Undo Last Bid
        db.execUpdate("DELETE FROM `bids` WHERE `bidderid`=? AND `auctionid`=? AND `timestamp`=?"
                      ,bidder.getId(), this.id, bid.getTimestamp());
        this.bids.pop();
        de.cubeisland.AuctionHouse.RedisSync.getInstance().publishAuctionUpsert(this.id);
        return true;
    }

    public double getCurrentPrice()
    {
        if (this.bids.isEmpty())
        {
            return 0.0D;
        }
        return this.bids.peek().getAmount();
    }

    public boolean hasExternalBidder()
    {
        return !this.bids.isEmpty() && !this.bids.peek().getBidder().equals(this.owner);
    }

    public boolean addDirectBid(Bidder bidder, double amount)
    {
        if (bidder == null || bidder.equals(this.owner))
        {
            return false;
        }
        this.bids.push(new Bid(bidder, amount, this));
        return true;
    }

/**
 * @return id as int
 */   
    public int getId()
    {
       return this.id; 
    }
    
/**
 * sets the AuctionID
 * @param int id
 */   
    public void setId(int id)
    {
        this.id = id;
    }
    
/**
 * @return item as ItemStack
 */       
    public ItemStack getItem()
    {
        return this.item;
    }   
    
/**
 * @return owner as Bidder
 */      
    public Bidder getOwner()
    {
        return this.owner;
    }
    
/**
 * @return auctionEnd in Milliseconds
 */     
    public long getAuctionEnd()
    {
        return this.auctionEnd;
    }
    
/**
 * @return all bids
 */       
    public Stack<Bid> getBids()
    {
        return this.bids;
    }
    
/**
 * gives owner (and last/initial bid) to Server
 */    
    public void giveServer()
    {
        this.owner = ServerBidder.getInstance();
        this.bids.peek().giveServer();
    }
    
/**
 * 
 * @return DataBase Id of the owner
 */
    public int getOwnerId()
    {
        return this.owner.getId();
    }
    
/**
 * 
 * @return Amount of item in this auction
 */
    public int getItemAmount()
    {
        return this.item.getAmount();
    }
    
/**
 * 
 * @return item as String for DataBase
 */
    public String getConvertItem()
    {
        return Util.convertItem(this.item);
    }
    
/**
 * 
 * @return DataBase Timestamp of AuctionEnd
 */
    public Timestamp getEndTimestamp()
    {
        return new Timestamp(this.auctionEnd);
    }
    
/**
 * 
 * @return ItemType of item as String
 */
    public String getItemType()
    {
        return this.item.getType().toString();
    }
    
/**
 * 
 * @return DataValue / DamageValue of item
 */
    public short getItemData()
    {
        return Util.getItemData(this.item);
    }

/**
 *  @return TableName in Database
 */ 
    public String getTable()
    {
        return "auction";
    }
    
/**
 *  @return TableName for Database
 */ 
    public String getDBTable()
    {
        return "`"+this.getTable()+"`";
    }
}