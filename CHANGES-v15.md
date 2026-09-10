# AuctionHousAqua 1.21.8-1.3.1

Изменения:
- добавлен тихий режим консоли через `logging.pluginActivity` и `logging.debug`;
- по умолчанию debug выключен;
- исправлены названия меню/кнопок, где оставался AuctionBox;
- исправлены заголовки хранилища с `%page%/%maxPage%`;
- неактивные кнопки страниц теперь серые и не выглядят рабочими;
- в пустых меню показывается понятная надпись.

Добавить в config.yml:
```yaml
logging:
  pluginActivity: false
  debug: false
```
