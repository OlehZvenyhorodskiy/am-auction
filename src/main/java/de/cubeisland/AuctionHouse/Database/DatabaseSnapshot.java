package de.cubeisland.AuctionHouse.Database;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of all auction database rows.
 *
 * A snapshot is fetched with plain JDBC only (no Bukkit API calls at all), so it can be
 * produced safely on an async thread with its own short-lived connection. The snapshot is
 * later applied to the in-memory caches on the main server thread
 * (see {@link Database#applySnapshot(DatabaseSnapshot, long)}).
 *
 * Row objects intentionally mirror the old per-table SELECT * result sets of
 * {@link Database#loadDatabase()} so the apply step keeps the legacy behaviour.
 */
public final class DatabaseSnapshot
{
    /** System.currentTimeMillis() captured right before the fetch started. */
    public final long fetchedAt;

    public final List<BidderRow> bidders;
    public final List<AuctionRow> auctions;
    public final List<SubscriptionRow> subscriptions;
    public final List<BoxRow> boxes;
    public final List<PriceRow> prices;

    /**
     * Bidder id to name, including names resolved lazily for orphan references
     * (rows that point to bidder ids missing from the bidder table).
     */
    public final Map<Integer, String> bidderNames;

    public DatabaseSnapshot(long fetchedAt,
                            List<BidderRow> bidders,
                            List<AuctionRow> auctions,
                            List<SubscriptionRow> subscriptions,
                            List<BoxRow> boxes,
                            List<PriceRow> prices,
                            Map<Integer, String> bidderNames)
    {
        this.fetchedAt = fetchedAt;
        this.bidders = bidders;
        this.auctions = auctions;
        this.subscriptions = subscriptions;
        this.boxes = boxes;
        this.prices = prices;
        this.bidderNames = bidderNames;
    }

    /** One row of the bidder table. */
    public static final class BidderRow
    {
        public final int id;
        public final String name;
        public final byte notify;

        public BidderRow(int id, String name, byte notify)
        {
            this.id = id;
            this.name = name;
            this.notify = notify;
        }
    }

    /** One row of the auctions table with its bids attached in chronological order. */
    public static final class AuctionRow
    {
        public final int id;
        public final int ownerId;
        public final String item;
        public final int amount;
        public final long auctionEnd;
        public final List<BidRow> bids = new ArrayList<BidRow>();

        public AuctionRow(int id, int ownerId, String item, int amount, long auctionEnd)
        {
            this.id = id;
            this.ownerId = ownerId;
            this.item = item;
            this.amount = amount;
            this.auctionEnd = auctionEnd;
        }
    }

    /** One row of the bids table. */
    public static final class BidRow
    {
        public final int id;
        public final int bidderId;
        public final double amount;
        public final Timestamp timestamp;

        public BidRow(int id, int bidderId, double amount, Timestamp timestamp)
        {
            this.id = id;
            this.bidderId = bidderId;
            this.amount = amount;
            this.timestamp = timestamp;
        }
    }

    /** One row of the subscription table. */
    public static final class SubscriptionRow
    {
        public final int bidderId;
        /** 0 when the DB value is NULL, matching ResultSet#getInt semantics of the legacy loader. */
        public final int auctionId;
        public final int type;
        public final String item;

        public SubscriptionRow(int bidderId, int auctionId, int type, String item)
        {
            this.bidderId = bidderId;
            this.auctionId = auctionId;
            this.type = type;
            this.item = item;
        }
    }

    /** One row of the auctionbox table. */
    public static final class BoxRow
    {
        public final int id;
        public final int bidderId;
        public final String item;
        public final int amount;
        public final double price;
        public final Timestamp timestamp;
        public final int ownerId;

        public BoxRow(int id, int bidderId, String item, int amount, double price, Timestamp timestamp, int ownerId)
        {
            this.id = id;
            this.bidderId = bidderId;
            this.item = item;
            this.amount = amount;
            this.price = price;
            this.timestamp = timestamp;
            this.ownerId = ownerId;
        }
    }

    /** One row of the price table. */
    public static final class PriceRow
    {
        public final String item;
        public final double price;
        public final int amount;

        public PriceRow(String item, double price, int amount)
        {
            this.item = item;
            this.price = price;
            this.amount = amount;
        }
    }
}
