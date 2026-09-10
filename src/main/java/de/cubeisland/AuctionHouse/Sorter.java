package de.cubeisland.AuctionHouse;

import de.cubeisland.AuctionHouse.Auction.Auction;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Sorts auction lists. The old comparators returned only -1/1 and returned 1
 * even when both values were equal. Java TimSort rejects such comparators with
 * "Comparison method violates its general contract", which is exactly what
 * happens after manual MySQL cleanup leaves many equal/partial rows. These
 * comparators are total, null-safe and deterministic.
 */
public enum Sorter
{
    ID, PRICE, DATE, QUANTITY;

    private static final Comparator<Auction> compareId = new Comparator<Auction>()
    {
        public int compare(Auction a1, Auction a2)
        {
            int n = compareNulls(a1, a2);
            if (n != Integer.MIN_VALUE) return n;
            return Integer.compare(a2.getId(), a1.getId());
        }
    };

    private static final Comparator<Auction> comparePrice = new Comparator<Auction>()
    {
        public int compare(Auction a1, Auction a2)
        {
            int n = compareNulls(a1, a2);
            if (n != Integer.MIN_VALUE) return n;
            int byPrice = Double.compare(safePrice(a2), safePrice(a1));
            if (byPrice != 0) return byPrice;
            return Integer.compare(a2.getId(), a1.getId());
        }
    };

    private static final Comparator<Auction> compareDate = new Comparator<Auction>()
    {
        public int compare(Auction a1, Auction a2)
        {
            int n = compareNulls(a1, a2);
            if (n != Integer.MIN_VALUE) return n;
            int byDate = Long.compare(a2.getAuctionEnd(), a1.getAuctionEnd());
            if (byDate != 0) return byDate;
            return Integer.compare(a2.getId(), a1.getId());
        }
    };

    private static final Comparator<Auction> compareQuantity = new Comparator<Auction>()
    {
        public int compare(Auction a1, Auction a2)
        {
            int n = compareNulls(a1, a2);
            if (n != Integer.MIN_VALUE) return n;
            int byAmount = Integer.compare(safeAmount(a2), safeAmount(a1));
            if (byAmount != 0) return byAmount;
            return Integer.compare(a2.getId(), a1.getId());
        }
    };

    private static int compareNulls(Auction a1, Auction a2)
    {
        if (a1 == a2) return 0;
        if (a1 == null) return 1;
        if (a2 == null) return -1;
        return Integer.MIN_VALUE;
    }

    private static double safePrice(Auction auction)
    {
        try
        {
            return auction.getCurrentPrice();
        }
        catch (Throwable ignored)
        {
            return 0.0D;
        }
    }

    private static int safeAmount(Auction auction)
    {
        try
        {
            return auction.getItemAmount();
        }
        catch (Throwable ignored)
        {
            return 0;
        }
    }

    public void sortAuction(List<Auction> auctionlist)
    {
        if (auctionlist == null || auctionlist.size() < 2)
        {
            return;
        }
        if (this == Sorter.ID)
        {
            Collections.sort(auctionlist, compareId);
        }
        else if (this == Sorter.PRICE)
        {
            Collections.sort(auctionlist, comparePrice);
        }
        else if (this == Sorter.DATE)
        {
            Collections.sort(auctionlist, compareDate);
        }
        else if (this == Sorter.QUANTITY)
        {
            Collections.sort(auctionlist, compareQuantity);
        }
    }

    public List<Auction> sortAuction(List<Auction> auctionlist, int quantity)
    {
        this.sortAuction(auctionlist);

        if (this == Sorter.QUANTITY)
        {
            if (auctionlist == null || auctionlist.isEmpty())
            {
                return null;
            }
            while (!auctionlist.isEmpty() && safeAmount(auctionlist.get(auctionlist.size() - 1)) < quantity)
            {
                auctionlist.remove(auctionlist.size() - 1);
            }
            if (auctionlist.isEmpty())
            {
                return null;
            }
        }
        return auctionlist;
    }
}
