# Offline build for AuctionHousAqua

This project was adapted for an **offline Maven build** and resolves all required compile-time jars from `C:/1.21.8` using `systemPath` dependencies.

## Expected local jars

The `pom.xml` expects these files:

- `C:/1.21.8/paper-api-1.21.8-R0.1-SNAPSHOT.jar`
- `C:/1.21.8/Vault.jar`
- `C:/1.21.8/adventure-api-4.24.0.jar`
- `C:/1.21.8/adventure-key-4.24.0.jar`
- `C:/1.21.8/adventure-text-minimessage-4.24.0.jar`
- `C:/1.21.8/adventure-text-serializer-gson-4.24.0.jar`
- `C:/1.21.8/adventure-text-serializer-legacy-4.24.0.jar`
- `C:/1.21.8/adventure-text-serializer-plain-4.24.0.jar`
- `C:/1.21.8/adventure-text-logger-slf4j-4.24.0.jar`
- `C:/1.21.8/examination-api-1.3.0.jar`
- `C:/1.21.8/guava-33.3.1-jre.jar`
- `C:/1.21.8/gson-2.11.0.jar`
- `C:/1.21.8/snakeyaml-2.2.jar`
- `C:/1.21.8/joml-1.10.8.jar`
- `C:/1.21.8/fastutil-8.5.15.jar`
- `C:/1.21.8/log4j-api-2.24.1.jar`
- `C:/1.21.8/slf4j-api-2.0.16.jar`
- `C:/1.21.8/brigadier-1.3.10.jar`
- `C:/1.21.8/bungeecord-chat-1.21-R0.2-deprecated+build.21.jar`
- `C:/1.21.8/jspecify-1.0.0.jar`
- `C:/1.21.8/checker-qual-3.49.2.jar`
- `C:/1.21.8/mysql-connector-j-9.2.0.jar`

If your jar names are different, edit the matching properties in `pom.xml`.

## Build

```bash
mvn -o clean package
```

The build compiles against `mysql-connector-j-9.2.0.jar`. Keep that jar available in your server environment if you do not bundle dependencies separately.

## Notes

- Online repositories were removed from Maven.
- The old `libminecraft` translation dependency was replaced with an internal translation loader.
- The codebase was modernized for newer Bukkit/Paper APIs.
- Item storage now uses serialized `ItemStack` data instead of legacy numeric item ids.
- Bundled `language/de.ini` and `language/en.ini` are now exported into the plugin data folder automatically on first start.
- The database layer now supports a direct `jdbcUrl` override plus extra connection parameters for stubborn shared-hosting MySQL setups.
- If you already have an old database from the legacy plugin version, the item format in that DB is not guaranteed to be compatible and may need migration.

## Runtime database setup

The plugin uses MySQL/MariaDB at runtime. Edit `plugins/AuctionHousAqua/config.yml` and set valid values for:

- `auction.database.type`
- `auction.database.host`
- `auction.database.port`
- `auction.database.user`
- `auction.database.pass`
- `auction.database.name`
- `auction.database.jdbcUrl`
- `auction.database.connectionParameters`

You can normally leave `jdbcUrl` empty. If your hosting panel gives you a full JDBC string or requires special flags like SSL, put the whole URL there.


Version 1.21.8-1.0.2 fixes MySQL foreign key creation on hosts that enforce exact type matching for referenced columns.
