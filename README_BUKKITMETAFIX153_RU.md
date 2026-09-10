# AuctionHousAqua23 bukkitmetafix153

Исправление поверх vaultbuyfix152 для Paper 1.21.11.

## Что исправлено

- Исправлен краш GUI при открытии карточки лота/купленных предметов:
  `NoSuchMethodError: void org.bukkit.inventory.ItemStack.setItemMeta(ItemMeta)`.
- Все GUI-вызовы `ItemStack#setItemMeta` переведены на бинарно-совместимый `MetaCompat`, который вызывает метод через reflection и работает с вариантом Paper, где метод возвращает `boolean`.
- Сохранён путь покупки из `vaultbuyfix152`: купленный предмет попадает в AuctionBox/`Купленные предметы`, как на старой ветке `AuctionHousAqua9-timefix`.

## Установка

Убрать старые `AuctionHousAqua*.jar` из `plugins`, положить `AuctionHousAqua23-bukkitmetafix153.jar`, затем сделать полный рестарт сервера. PlugMan reload может оставлять старые классы и старые команды в памяти.
