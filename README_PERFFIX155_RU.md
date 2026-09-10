# AuctionHousAqua 1.21.8-1.4.7-perffix155

Комплексное исправление производительности: устранён спам `[debug] Start loading database...`
(110 000+ строк в logs/2026-09-06-1.log), зависания Server thread на MySQL и 40-секундное
зависание при `/plugman unload` (Paper Watchdog, Database.java:346).

## Диагноз по logs/2026-09-06-1.log

1. **15 760 полных перезагрузок БД за 21.8 часа** (каждые ~5 с). На продакшене стояла связка
   `redis.enabled: false` + `auction.database.syncIntervalSeconds: 5` + `logging.debug: true`,
   поэтому fallback-поллинг `CrossServerSync` каждые 5 секунд выполнял `Database.loadDatabase()`
   прямо в Server thread и печатал 7 debug-строк.
2. `loadDatabase()` делал `SELECT *` по 5 таблицам **плюс N+1** запрос по ставкам на каждый лот,
   всё в главном потоке — потеря TPS, «сервер зависает».
3. **Утечка JDBC-Statement — причина зависания при выключении.** `Database.query()` возвращал
   `ResultSet`, но `PreparedStatement` никто не закрывал (закрытие ResultSet не закрывает
   Statement). За 15 760 синхронизаций на единственном соединении накопились сотни тысяч
   открытых Statement. При `onDisable → Connection.close()` Connector/J проходил
   `closeAllOpenStatements()` по всем утечкам — отсюда 40+ секунд блокировки Server thread и
   серии аварийных дампов Paper Watchdog (стек: `StatementImpl.doClose → ConnectionImpl.close →
   Database.closeSilently(Database.java:346)`).
4. При недоступном MySQL `ensureConnection()` на каждом клике покупки пытался переподключиться
   в главном потоке с `connectTimeoutMs=10000` **на каждого кандидата имени базы** — ещё один
   источник фризов «не может подключиться к таблице аукцион».

## Что исправлено

### Database.java
- **Утечка Statement закрыта:** `createStatement()` вызывает `PreparedStatement.closeOnCompletion()`
  (Statement закрывается вместе с ResultSet); `exec/execUpdate/update/insert/delete` закрывают
  Statement через try-with-resources. Дополнительно закрыты «голые» ResultSet в
  `Bid.java`, `Bidder.java`, `AuctionItem.java`.
- **Полная перезагрузка разделена на 2 фазы:**
  - `fetchSnapshot()` — только JDBC, на **отдельном короткоживущем соединении**, безопасно
    вызывать из async-потока (новый класс `DatabaseSnapshot`);
  - `applySnapshot(...)` — пересборка кэшей (ItemStack/OfflinePlayer), выполняется **в Server
    thread** на следующем тике, без единого SQL-запроса.
- **N+1 устранён:** ставки грузятся одним `SELECT * FROM bids ORDER BY auctionid, timestamp`
  и группируются в памяти вместо запроса на каждый лот.
- **Защита от «воскрешения» лотов:** timestamp последней локальной записи (`markWrite()` в
  exec/execUpdate/update/insert/delete); если покупка/снятие лота произошла пока снимок
  качался — устаревший снимок отбрасывается и не перезатирает кэш.
- **Circuit breaker коннекта:** после неудачного подключения `ensureConnection()` 15 секунд
  фейлится мгновенно, вместо того чтобы держать Server thread на таймаутах подключения.
- **`close(timeoutMs)`:** закрытие соединения вынесено в daemon-поток; если за 3 с не
  завершилось — соединение принудительно обрывается через `Connection.abort()`. Плюс
  `cancelTasks` в `onDisable`, чтобы pending-задачи не трогали закрываемую БД.

### CrossServerSync.java
- Fallback-поллинг переведён на `runTaskTimerAsynchronously`; `fullSyncNow()` при вызове из
  главного потока (GUI, Redis-событие FULL_RELOAD) сам переносит работу в async-пул.
- Интервал ниже 30 с автоматически поднимается до 30 с с warning в лог (каждые 5 секунд —
  это и была причина спама).
- `syncNow()` из GUI больше никогда не блокирует открытие меню: перезагрузка ставится в
  очередь (с дедупликацией через AtomicBoolean) и применяется на следующем тике.

### RedisSync.java
- **Circuit breaker:** после сбоя соединения команды Redis 15 секунд не открывают сокет, а
  фейлятся мгновенно (раньше каждая покупка могла ждать connect/read таймауты в Server thread).
- **Распределённый лок fail-open:** если Redis недоступен, покупка продолжается без
  распределённого лока (как при `redis.enabled: false`) вместо падения/зависания.
  Итоговую актуальность всё равно проверяет `loadAuctionById` в `buyNow`.

### GUI (InventoryViewCompat / MetaCompat)
- Совместимость с Paper 1.21.x сохранена (reflection по `openInventory(Inventory)` /
  `setItemMeta(ItemMeta)` решает проблему дескрипторов `void` vs `InventoryView`/`boolean`).
- Найденный `Method` теперь кэшируется — раньше `getMethod()` вызывался при каждом открытии
  меню и на каждом построенном предмете.
- Скрытых утечек в самом GUI нет: меню нигде не регистрируются (MenuListener держит ссылки
  только внутри обработчиков события). Реальные утечки были в JDBC-слое — устранены выше.
  Осталось известное «кэш-поведение»: `Bidder.getInstances()` растёт с числом уникальных
  игроков (по объекту на игрока + box) — это кэш, а не утечка; чистится при каждой полной
  перезагрузке и в onDisable.

### Попутно
- `AuctionItem(ItemStack, Bidder)`: INSERT писал в несуществующую колонку `playerid`
  (в схеме — `bidderid`), ошибка молча глоталась. Исправлено.

## Конфигурация для продакшена (AquaMix)

```yaml
logging:
  pluginActivity: false
  debug: false                # ОБЯЗАТЕЛЬНО false! true + polling = 110k строк в сутки

auction:
  database:
    syncIntervalSeconds: 0    # 0 = поллинг выключен (штатный режим при работающем Redis)

redis:
  enabled: true               # основной канал синхронизации между серверами сети
  host: "127.0.0.1"           # реальный адрес — только в серверном config.yml, не в git!
  port: 6379
  password: "..."
```

Правила выбора режима:

| Сценарий | redis.enabled | syncIntervalSeconds |
|---|---|---|
| Сеть серверов AquaMix (штатный) | `true` | `0` |
| Один сервер / Redis недоступен | `false` | `30`–`120` |
| Отладка на тестовом сервере | любое | любое (минимум 30 форсируется кодом) |

Значения `syncIntervalSeconds < 30` больше не поддерживаются — код поднимает интервал до 30 с
и пишет warning. С Redis события применяются точечно (`loadAuctionById`,
`reloadAuctionBoxForBidder`) без полных перезагрузок.

## ⚠️ Безопасность

В `config.yml` в репозитории был закоммичен **реальный адрес и пароль Redis**
(`45.93.200.125` / `iINi$OCMEY`). Они заменены на плейсхолдеры, но считайте их
скомпрометированными: **смените пароль Redis** (`requirepass` / ACL), проверьте firewall
(6379 не должен быть открыт наружу) и историю git. То же касается любых паролей, попадавших
в коммиты.

## Сборка

Как и раньше — офлайн-Maven по `OFFLINE-BUILD.md`:

```bash
mvn -o clean package
```

Версия поднята до `1.21.8-1.4.7-perffix155` (pom.xml + plugin.yml). Jar из корня репозитория
не пересобран (в этом окружении нет JDK и локальных jar из `C:/1.21.8`) — соберите на своей
машине.

Установка: удалить старые `AuctionHousAqua*.jar`, положить новый, **полный рестарт** (не
PlugMan). `/ah reload` работает, но предпочтителен рестарт: статический `Manager` после
reload держит старый объект Database и тот лениво переподключается (наследуемое поведение,
не усилено этим фиксом).

## Что проверить после установки

1. В логе больше нет повторяющихся `[debug] Start loading database...` каждые 5 секунд.
2. `/ah` открывается мгновенно даже при лежащем MySQL (в логе — не более одного
   `Full MySQL auction sync failed` на интервал).
3. `/plugman unload` (на тесте!) completes без дампов Watchdog — закрытие БД либо мгновенное,
   либо через 3 с принудительный abort.
4. Покупка на двух серверах одновременно: Redis-лок не даёт продать лот дважды; при
   отключённом Redis покупка продолжает работать в односерверном режиме.
