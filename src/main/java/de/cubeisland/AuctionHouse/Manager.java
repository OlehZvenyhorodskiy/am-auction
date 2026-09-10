package de.cubeisland.AuctionHouse;

import de.cubeisland.AuctionHouse.Auction.Auction;
import de.cubeisland.AuctionHouse.Auction.Bidder;
import static de.cubeisland.AuctionHouse.AuctionHouse.t;
import de.cubeisland.AuctionHouse.Auction.ServerBidder;
import de.cubeisland.AuctionHouse.Database.Database;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Manages all Auctions
 *
 * @author Faithcaio
 */
public class Manager
{
    private static Manager instance = null;
    private final List<Auction> auctions;
    private final Stack<Integer> freeIds;
    private final HashSet<Integer> reservedIds;
    private static final AuctionHouse plugin = AuctionHouse.getInstance();
    private static final AuctionHouseConfiguration config = plugin.getConfiguration();
    private HashMap<Bidder, Bidder> remBidderConfirm = new HashMap();
    private HashSet<Bidder> remAllConfirm = new HashSet();
    private HashMap<Bidder, Integer> remSingleConfirm = new HashMap();
    private final Database db;
    private Price price = new Price();

/**
 * Init Manager
 */    
    private Manager()
    {
        this.db = AuctionHouse.getInstance().getDB();
        int maxAuctions = config.auction_maxAuctions_overall;
        if (maxAuctions <= 0)
        {
            maxAuctions = 1;
        }
        this.auctions = new ArrayList<Auction>();
        this.freeIds = new Stack<Integer>();
        this.reservedIds = new HashSet<Integer>();
        for (int i = maxAuctions; i > 0; --i)
        {
            this.freeIds.push(i);
        }
    }
    
/**
 * @return Manager or create new
 */  
    public static Manager getInstance()
    {
        if (instance == null)
        {
            instance = new Manager();
        }
        return instance;
    }

    public void clearForDatabaseReload()
    {
        this.auctions.clear();
        this.freeIds.clear();
        this.reservedIds.clear();
        int maxAuctions = config.auction_maxAuctions_overall;
        if (maxAuctions <= 0)
        {
            maxAuctions = 1;
        }
        for (int i = maxAuctions; i > 0; --i)
        {
            this.freeIds.push(i);
        }
        this.remBidderConfirm.clear();
        this.remAllConfirm.clear();
        this.remSingleConfirm.clear();
        this.price = new Price();
    }
    
/**
 * @return Manager or create new
 */  
    public Auction getAuction(int id) //Get Auction with ID
    {
        Auction auction = null;
        int size = this.auctions.size();
        for (int i = 0; i < size; i++)
        {
            if (this.auctions.get(i).getId() == id)
            {
                auction = this.auctions.get(i);
            }
        }
        return auction;
    }

/**
 * @return All auctions
 */
    public List<Auction> getAuctions()
    {
        return new ArrayList<Auction>(auctions);
    }
    
/**
 * @return auction with id
 */
    public Auction getIndexAuction(int id)
    {
        return auctions.get(id);
    }

/**
 * @return true if no freeId availiable
 */
    public synchronized boolean isEmpty()
    {
        repairFreeIds();
        return false;
    }

    /**
     * Returns the amount of reusable ids currently known to the manager.
     * Dynamic ids above auction.maxAuctions.overall can still be allocated if needed.
     */
    public synchronized int getFreeIdCount()
    {
        repairFreeIds();
        return this.freeIds.size();
    }

    /**
     * Reserves an auction id for a new Auction instance.
     * Repairs stale ids first and, when the configured range is exhausted,
     * allocates the next safe id above the known range instead of failing /ah sell.
     */
    public synchronized int reserveAuctionId()
    {
        repairFreeIds();
        Integer id = null;
        if (!this.freeIds.isEmpty())
        {
            id = this.freeIds.pop();
        }
        else
        {
            id = Integer.valueOf(findHighestKnownAuctionId() + 1);
        }
        if (id == null || id.intValue() <= 0)
        {
            return -1;
        }
        this.reservedIds.add(id);
        return id.intValue();
    }

    /**
     * Releases an id reserved by a failed auction registration.
     */
    public synchronized void releaseAuctionId(int auctionId)
    {
        if (auctionId <= 0)
        {
            return;
        }
        this.reservedIds.remove(Integer.valueOf(auctionId));
        if (isAuctionIdInUse(auctionId))
        {
            return;
        }
        if (!this.freeIds.contains(Integer.valueOf(auctionId)))
        {
            this.freeIds.push(Integer.valueOf(auctionId));
            Collections.sort(this.freeIds);
            Collections.reverse(this.freeIds);
        }
    }

    private synchronized void repairFreeIds()
    {
        int maxAuctions = Math.max(getConfiguredMaxAuctions(), findHighestKnownAuctionId());
        HashSet<Integer> used = new HashSet<Integer>();
        for (Auction auction : this.auctions)
        {
            if (auction != null && auction.getId() > 0)
            {
                used.add(Integer.valueOf(auction.getId()));
            }
        }
        used.addAll(this.reservedIds);

        this.freeIds.clear();
        for (int i = maxAuctions; i > 0; --i)
        {
            Integer id = Integer.valueOf(i);
            if (!used.contains(id))
            {
                this.freeIds.push(id);
            }
        }
    }

    private int getConfiguredMaxAuctions()
    {
        int maxAuctions = config.auction_maxAuctions_overall;
        return maxAuctions <= 0 ? 1 : maxAuctions;
    }

    private int findHighestKnownAuctionId()
    {
        int highest = getConfiguredMaxAuctions();
        for (Auction auction : this.auctions)
        {
            if (auction != null && auction.getId() > highest)
            {
                highest = auction.getId();
            }
        }
        for (Integer id : this.reservedIds)
        {
            if (id != null && id.intValue() > highest)
            {
                highest = id.intValue();
            }
        }
        for (Integer id : this.freeIds)
        {
            if (id != null && id.intValue() > highest)
            {
                highest = id.intValue();
            }
        }
        return highest;
    }

    private boolean isAuctionIdInUse(int auctionId)
    {
        for (Auction auction : this.auctions)
        {
            if (auction != null && auction.getId() == auctionId)
            {
                return true;
            }
        }
        return false;
    }
    
/**
 * @return amount of auctions
 */
    public int size()
    {
        return auctions.size();
    }
    
/**
 * @return All auctions with material
 */
    public List<Auction> getAuctionItem(ItemStack material)
    {
        List<Auction> auctionlist = new ArrayList<Auction>();
        int size = this.auctions.size();
        for (int i = 0; i < size; i++)
        {
            Auction auction = this.auctions.get(i);
            if (auction == null)
            {
                return null;
            }
            if (auction.getItem().getType() == material.getType())
            //    && (this.auctions.get(i).getItemData() == material.getDurability())
            {
                auctionlist.add(auction);
            }
        }
        return auctionlist;
    }
    
/**
 * @return All auctions with material and without bidder
 */
    public List<Auction> getAuctionItem(ItemStack material, Bidder bidder)
    {
        List<Auction> auctionlist = this.getAuctionItem(material);
        for (Auction auction : bidder.getActiveBids())
        {
            if (auction.getOwner() == bidder)
            {
                auctionlist.remove(auction);
            }
        }
        return auctionlist;
    }
    
/**
 * @return All auctions sorted by EndDate
 */
    public List<Auction> getEndingAuctions()
    {
        List<Auction> endingActions = new ArrayList<Auction>();
        int size = this.auctions.size();
        for (int i = 0; i < size; ++i)
        {
            endingActions.add(this.auctions.get(i));
        }
        Sorter.DATE.sortAuction(endingActions);
        return endingActions;
    }
    
/**
 * removes Auction completly
 */
    public boolean cancelAuction(Auction auction, boolean win)
    {
        this.freeIds.push(auction.getId());
        Collections.sort(this.freeIds);
        Collections.reverse(this.freeIds);

        if (!(auction.getOwner() instanceof ServerBidder))
        {
            auction.getOwner().removeAuction(auction);
            while (!(auction.getBids().isEmpty()))
            {
                Bidder.getInstance(auction.getBids().peek().getBidder().getOffPlayer()).removeAuction(auction);
                auction.getBids().pop();
            }
            if (!win)
                auction.getOwner().getBox().addItem(auction);
        }
        else
        {
            ServerBidder.getInstance().removeAuction(auction);
        }
        db.execUpdate("DELETE FROM `auctions` WHERE `id`=?", auction.getId());
        //clean up DataBase just in case
        db.execUpdate("DELETE FROM `subscription` WHERE `auctionid`=?", auction.getId());
        db.execUpdate("DELETE FROM `bids` WHERE `auctionid`=?", auction.getId());
        this.auctions.remove(auction);
        RedisSync.getInstance().publishAuctionRemove(auction.getId());
        return true;
    }

/**
 * Adds an auction
 */
    public void addAuction(Auction auction)
    {
        if (auction == null)
        {
            return;
        }
        Auction existing = this.getAuction(auction.getId());
        if (existing != null && existing != auction)
        {
            removeLocalAuction(existing);
        }
        if (!this.auctions.contains(auction))
        {
            this.auctions.add(auction);
        }
    }

    /**
     * Removes an auction only from local memory after a Redis event.
     * This method must not write to MySQL and must not create claim-box items.
     */
    public void removeLocalAuction(int auctionId)
    {
        Auction auction = this.getAuction(auctionId);
        if (auction != null)
        {
            removeLocalAuction(auction);
        }
        else if (!this.freeIds.contains(auctionId))
        {
            this.freeIds.push(auctionId);
            Collections.sort(this.freeIds);
            Collections.reverse(this.freeIds);
        }
    }

    private void removeLocalAuction(Auction auction)
    {
        if (auction == null)
        {
            return;
        }
        int auctionId = auction.getId();
        if (!this.freeIds.contains(auctionId))
        {
            this.freeIds.push(auctionId);
            Collections.sort(this.freeIds);
            Collections.reverse(this.freeIds);
        }
        this.auctions.remove(auction);
        for (Bidder bidder : Bidder.getInstances().values())
        {
            bidder.getActiveBids().remove(auction);
            bidder.getSubs().remove(auction);
        }
    }
    
/**
 * @return Stack of Free AuctionIds
 */
    public Stack<Integer> getFreeIds()
    {
        return this.freeIds;
    }
    
/**
 * @return HashMap for remove Bidder-auction confirmation
 */
    public HashMap<Bidder, Bidder> getBidderConfirm()
    {
        return this.remBidderConfirm;
    }
    
/**
 * @return HashMap for remove all auction confirmation
 */
    public HashSet<Bidder> getAllConfirm()
    {
        return this.remAllConfirm;
    }

/**
 * @return HashMap for remove single auction confirmation
 */
    public HashMap<Bidder, Integer> getSingleConfirm()
    {
        return this.remSingleConfirm;
    }
    
/**
 * removes old auctions after starting Server
 */
    public void removeOldAuctions()
    {
        List<Auction> t_auctions = new ArrayList<Auction>(this.auctions);
        for (Auction auction : t_auctions)
        {
            if (auction.getAuctionEnd() < System.currentTimeMillis())
                this.cancelAuction(auction, false);
        }
    }
    
/**
 * @return average Price of item
 */
    public double getPrice(ItemStack item)
    {
       return this.price.getPrice(item);
    }
    
    private boolean deliverPurchasedItemNow(Auction auction, Bidder buyer)
    {
        if (auction == null || buyer == null)
        {
            return false;
        }

        if (buyer.isOnline())
        {
            Player player = buyer.getPlayer();
            if (player != null)
            {
                try
                {
                    ItemStack item = auction.getItem().clone();
                    Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
                    if (leftovers == null || leftovers.isEmpty())
                    {
                        AuctionHouse.debug("[BuyFix152] auction #" + auction.getId() + " delivered directly to " + buyer.getName() + " item=" + auction.getItemType() + " x" + auction.getItemAmount());
                        return true;
                    }

                    int dropped = 0;
                    for (ItemStack leftover : leftovers.values())
                    {
                        if (leftover != null && leftover.getAmount() > 0)
                        {
                            player.getWorld().dropItemNaturally(player.getLocation(), leftover.clone());
                            dropped += leftover.getAmount();
                        }
                    }

                    if (dropped > 0)
                    {
                        player.sendMessage(t("i") + " Купленный предмет не поместился полностью в инвентарь: остаток выпал рядом с вами.");
                        AuctionHouse.log("[BuyFix152] auction #" + auction.getId() + " delivered to " + buyer.getName() + " with dropped overflow=" + dropped + " item=" + auction.getItemType());
                        return true;
                    }
                }
                catch (Throwable t)
                {
                    AuctionHouse.log("[BuyFix152] direct delivery failed for auction #" + auction.getId() + " buyer=" + buyer.getName() + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    return false;
                }
            }
        }

        // Only non-GUI/offline paths should reach this. Keep the old auction-box fallback for safety.
        buyer.getBox().addItem(auction);
        AuctionHouse.debug("[BuyFix152] auction #" + auction.getId() + " buyer=" + buyer.getName() + " is offline; item placed into auction box");
        return true;
    }

/**
 * adjust average Price for item
 */
    public double adjustPrice(ItemStack item, double price)
    {
        return this.price.adjustPrice(item, price);
    }
    
/**
 * set average Price for item
 */
    public double setPrice(ItemStack item, double price, int amount)
    {
        return this.price.setPrice(item, price, amount);
    }

    public boolean buyNow(Auction auction, Bidder buyer)
    {
        if (auction == null || buyer == null)
        {
            AuctionHouse.log("[BuyFix152] buy blocked: null auction or buyer auction=" + (auction == null ? "null" : auction.getId()) + " buyer=" + (buyer == null ? "null" : buyer.getName()));
            return false;
        }

        int auctionId = auction.getId();
        boolean locked = RedisSync.getInstance().tryLockAuction(auctionId, buyer.getName());
        if (!locked)
        {
            if (buyer.isOnline())
            {
                buyer.getPlayer().sendMessage(t("gui_buy_already_sold"));
            }
            AuctionHouse.log("[BuyFix152] buy blocked: redis lock busy auction=" + auctionId + " buyer=" + buyer.getName());
            return false;
        }

        try
        {
            // Always trust MySQL for the clicked lot at the final buy point. Redis keeps the
            // browser fast, but the selling node can keep a stale local Java object after bot
            // /ah sell or after another server touches the same auction. This refresh also makes
            // the selling node behave like the older AuctionHousAqua9 node that successfully buys.
            boolean refreshed = false;
            try
            {
                refreshed = db.loadAuctionById(auctionId);
            }
            catch (RuntimeException ex)
            {
                AuctionHouse.log("[BuyFix152] final MySQL refresh failed auction=" + auctionId + " buyer=" + buyer.getName() + ": " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
            Auction fresh = this.getAuction(auctionId);
            boolean exists = false;
            try
            {
                exists = db.auctionExists(auctionId);
            }
            catch (RuntimeException ex)
            {
                AuctionHouse.log("[BuyFix152] final MySQL exists check failed auction=" + auctionId + " buyer=" + buyer.getName() + ": " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
            if (fresh != null)
            {
                auction = fresh;
            }
            if (auction == null || fresh == null || !exists)
            {
                this.removeLocalAuction(auctionId);
                if (buyer.isOnline())
                {
                    buyer.getPlayer().sendMessage(t("gui_buy_already_sold"));
                }
                AuctionHouse.log("[BuyFix152] buy blocked: stale/ghost auction=" + auctionId + " buyer=" + buyer.getName() + " refreshed=" + refreshed + " dbExists=" + exists + " localFresh=" + (fresh != null));
                return false;
            }
            if (auction.getBids() == null || auction.getBids().isEmpty())
            {
                if (buyer.isOnline())
                {
                    buyer.getPlayer().sendMessage(t("gui_buy_already_sold"));
                }
                AuctionHouse.log("[BuyFix152] buy blocked: auction has no price/bid row auction=" + auctionId + " buyer=" + buyer.getName() + " seller=" + safeName(auction.getOwner()));
                return false;
            }
            if (auction.getOwner().equals(buyer))
            {
                if (buyer.isOnline())
                {
                    buyer.getPlayer().sendMessage(t("gui_buy_own"));
                }
                AuctionHouse.log("[BuyFix152] buy blocked: buyer equals owner auction=" + auctionId + " buyer=" + buyer.getName() + " seller=" + safeName(auction.getOwner()) + " buyerId=" + buyer.getId() + " ownerId=" + auction.getOwnerId());
                return false;
            }

            double price = auction.getCurrentPrice();
            Economy econ = AuctionHouse.getInstance().getEconomy();
            double balance = EconomyCompat.getBalance(econ, buyer);
            if (balance < price)
            {
                if (buyer.isOnline())
                {
                    buyer.getPlayer().sendMessage(t("gui_buy_not_enough_money", econ.format(price)));
                }
                AuctionHouse.log("[BuyFix152] buy blocked: not enough money auction=" + auctionId + " buyer=" + buyer.getName() + " balance=" + balance + " price=" + price + " provider=" + (econ == null ? "null" : econ.getName()));
                return false;
            }

            Bidder previousLeader = auction.getBids().peek().getBidder();

            EconomyCompat.Result withdraw = EconomyCompat.withdraw(econ, buyer, price);
            if (!withdraw.isSuccess())
            {
                if (buyer.isOnline())
                {
                    buyer.getPlayer().sendMessage(t("gui_buy_not_enough_money", econ.format(price)));
                }
                AuctionHouse.log("[BuyFix152] buy blocked: Vault withdraw failed buyer=" + buyer.getName() + " auction=" + auctionId + " price=" + price + " invoked=" + withdraw.wasInvoked() + " signature=" + withdraw.getSignature() + " error=" + withdraw.getError());
                return false;
            }

            double paidToOwner = 0.0D;
            boolean paidOwner = false;
            if (!(auction.getOwner() instanceof ServerBidder))
            {
                double commission = price * config.auction_comission / 100.0D;
                paidToOwner = price - commission;
                EconomyCompat.Result deposit = EconomyCompat.deposit(econ, auction.getOwner(), paidToOwner);
                if (!deposit.isSuccess())
                {
                    // Fake-player sellers may not have an economy account on this node. The item sale
                    // must still complete, exactly like on the older linked AuctionHousAqua9 server.
                    AuctionHouse.log("[BuyFix152] seller payout failed but sale continues buyer=" + buyer.getName() + " auction=" + auctionId + " seller=" + auction.getOwner().getName() + " amount=" + paidToOwner + " invoked=" + deposit.wasInvoked() + " signature=" + deposit.getSignature() + " error=" + deposit.getError());
                    paidOwner = false;
                }
                else
                {
                    paidOwner = true;
                }
            }

            auction.addDirectBid(buyer, price);

            // Keep the legacy AuctionHousAqua9 transaction path. Purchased items go to the
            // buyer auction box first; then the GUI opens the purchased-items claim screen.
            buyer.getBox().addItem(auction);
            AuctionHouse.log("[BuyFix152] auction #" + auctionId + " purchased by " + buyer.getName() + " from " + auction.getOwner().getName() + " item=" + auction.getItemType() + " x" + auction.getItemAmount() + " price=" + price + " withdraw=" + withdraw.getSignature());

            if (paidOwner && auction.getOwner().isOnline())
            {
                auction.getOwner().getPlayer().sendMessage(t("gui_owner_sold_direct", auction.getItemType() + " x" + auction.getItemAmount(), econ.format(paidToOwner)));
            }

            this.adjustPrice(auction.getItem(), price);
            if (buyer.isOnline())
            {
                buyer.getPlayer().sendMessage(t("gui_buy_success", auction.getItemType() + " x" + auction.getItemAmount(), econ.format(price)));
            }
            if (previousLeader != null && previousLeader != auction.getOwner() && previousLeader != buyer && previousLeader.isOnline())
            {
                previousLeader.getPlayer().sendMessage(t("gui_bid_lost_buy_now", auction.getId()));
            }
            this.cancelAuction(auction, true);
            RedisSync.getInstance().publishTransactionUpdate(auctionId);
            return true;
        }
        finally
        {
            RedisSync.getInstance().releaseAuctionLock(auctionId);
        }
    }

    private String safeName(Bidder bidder)
    {
        return bidder == null ? "null" : bidder.getName();
    }

}
