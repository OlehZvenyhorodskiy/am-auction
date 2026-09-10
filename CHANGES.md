CHANGES
=======

Version 1.0.0
-------------
- Initial release

- 1.21.8-1.0.2: Fixed MySQL foreign key creation for bids/subscription by matching auctionid columns to auctions.id (INT UNSIGNED). Improved SQL error text.

## 1.21.8-1.1.0
- Added chest-GUI auction browser, categories, listing details, buy confirmation, AuctionBox claim menu, purchased/expired views, and sell-inventory menu.
- Added zAuctionHouse-style command aliases and permission aliases.
- Added menus.yml and expanded Russian/English/German translations for GUI flows.

## Redis cross-server sync update

- Added built-in Redis pub/sub synchronization in one plugin, without requiring a separate Redis addon.
- MySQL remains the source of truth for auctions, bids, claim inventories and prices.
- Redis now broadcasts auction create/update/remove events, inventory updates and transaction notifications.
- Added a short Redis distributed lock for instant-buy actions to prevent the same lot from being bought on two servers at the same time.
- Disabled expensive full MySQL polling by default (`auction.database.syncIntervalSeconds: 0`). Redis events now keep servers in sync.
- GUI opening no longer forces a full MySQL reload when Redis is enabled, preventing TPS drops from repeated table scans.
- Added `server.info` support. Every server automatically gets a unique Redis server id; do not copy this file between servers.

## 1.21.8-1.4.7-perffix155
- Performance: full MySQL reloads are now fetched on an async thread with a dedicated connection and applied on the main thread (new DatabaseSnapshot + Database.fetchSnapshot/applySnapshot).
- Fixed the JDBC Statement leak (closeOnCompletion + try-with-resources + closing bare ResultSets in Bid/Bidder/AuctionItem) that made Connection#close hang for 40+ seconds on shutdown.
- onDisable: scheduler tasks cancelled first, database closed on a daemon thread with a 3s bound and forced abort fallback.
- CrossServerSync: async polling timer, self-routing off the main thread, minimum fallback interval raised to 30s, stale-snapshot guard prevents resurrecting just-sold auctions.
- Database: single-query bid loading (removed N+1 per auction), connect circuit breaker (15s fail-fast while MySQL is unreachable).
- RedisSync: command circuit breaker (15s fail-fast after a connection failure), distributed buy-lock degrades to fail-open when Redis is down.
- GUI: InventoryViewCompat/MetaCompat now cache the resolved reflection Methods.
- Fixed AuctionItem(ItemStack, Bidder) INSERT using a non-existent `playerid` column.
- config.yml: real Redis host/password replaced with placeholders (rotate them!), production notes for logging.debug and syncIntervalSeconds.
