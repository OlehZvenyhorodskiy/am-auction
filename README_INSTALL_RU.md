# Установка AuctionHousAqua20-buyfix150

1. Удали старые `AuctionHousAqua*.jar` из папки `plugins/`.
2. Положи только `AuctionHousAqua20-buyfix150.jar`.
3. Сделай полный рестарт сервера. Не используй PlugMan для проверки запуска.

Что исправлено:
- запуск на Paper 1.21.11: исправлены сигнатуры Bukkit API `PluginManager` и `ServicesManager`;
- `/ah` больше не падает на сортировке после ручной/частичной чистки MySQL;
- `/ah clearall confirm` полностью очищает MySQL/Redis и сбрасывает память плагина;
- команда очистки добавлена в tab-complete;
- `/ah sell <price>` должен работать после полной очистки;
- восстановлены `freeIds` и пересоздание bidder-записи после удаления данных из MySQL;
- покупатель получает предмет через AuctionBox, как в AuctionHousAqua9; GUI открывает раздел купленных предметов.
