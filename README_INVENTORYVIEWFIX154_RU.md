# AuctionHousAqua24 inventoryviewfix154

Исправление для Paper 1.21.11: убран прямой бинарный вызов `Player#openInventory(Inventory)` из GUI.

На сервере падало при `/ah`:

`NoSuchMethodError: 'void org.bukkit.entity.Player.openInventory(org.bukkit.inventory.Inventory)'`

Причина: сборка была скомпилирована против API/stub, где `openInventory` имеет descriptor `void`, а на Paper 1.21.11 метод возвращает `InventoryView`. JVM учитывает return type в descriptor, поэтому прямой вызов не находится.

Что сделано:
- добавлен `InventoryViewCompat`, открытие меню идёт через reflection;
- сохранён предыдущий `MetaCompat` для `ItemStack#setItemMeta`;
- сохранены buyfix/vaultbuyfix: купленные предметы должны попадать в AuctionBox / «Купленные предметы».

Установка: убрать старые `AuctionHousAqua*.jar`, положить этот JAR, сделать полный рестарт сервера. PlugMan для проверки нежелателен.
