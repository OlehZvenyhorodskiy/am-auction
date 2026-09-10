# AuctionHousAqua22 vaultbuyfix152

Исправление покупки лотов на том же сервере, где боты выставляют предметы через `/ah sell`.

## Причина

На сервере-продавце покупка доходила до Redis-lock (`AUCTION_INTERACT`), но дальше могла тихо остановиться до выдачи предмета и удаления лота. Главная опасная точка — вызовы Vault economy по старым `String`-overload методам (`withdrawPlayer(String,double)`, `depositPlayer(String,double)`, `getBalance(String)`). На разных узлах связки Vault/economy может отличаться: старый сервер с `AuctionHousAqua9-timefix` покупает лоты, а новая ветка на сервере ботов может не пройти локальную экономику/проверку и не довести сделку до `AUCTION_REMOVE`.

## Что изменено

- финальная покупка всегда перечитывает конкретный `auctionId` из MySQL перед списанием денег;
- добавлен `EconomyCompat`: Vault вызывается через reflection, сначала `OfflinePlayer`, затем `Player`, затем `String`;
- прямых вызовов `withdrawPlayer(String,double)` и `depositPlayer(String,double)` в buy path больше нет;
- покупка не отменяется из-за ошибки выплаты продавцу-боту, но ошибка пишется в лог;
- добавлены подробные логи `[BuyFix152]` для каждой причины блокировки покупки;
- купленный предмет кладётся в AuctionBox покупателя, как в старой ветке `AuctionHousAqua9-timefix`.

## Установка

Убрать старые `AuctionHousAqua*.jar` из `plugins`, положить `AuctionHousAqua22-vaultbuyfix152.jar`, затем сделать полный рестарт сервера.

После тестовой покупки в консоли должна появиться строка:

`[BuyFix152] auction #ID purchased by ...`

Если покупка всё ещё не пройдёт, в консоли будет точная причина: `stale/ghost`, `buyer equals owner`, `not enough money` или `Vault withdraw failed`.
