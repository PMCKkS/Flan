package top.pmckk.flan.storage;

import top.pmckk.flan.config.MessageException;

import com.mysql.cj.jdbc.MysqlDataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;
import org.sqlite.SQLiteDataSource;
import java.nio.file.*;
import java.util.Locale;

public final class StorageFactory {
    private StorageFactory() {}
    public static Storage open(Path directory, FileConfiguration c, String type) throws Exception {
        Files.createDirectories(directory);
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "YAML" -> new YamlStorage(directory.resolve("data.yml"));
            case "SQLITE" -> {
                Path file = directory.resolve(c.getString("storage.sqlite.file", "flan.db")).normalize();
                if (!file.startsWith(directory.normalize())) throw new MessageException("errors.sqlite-path");
                Files.createDirectories(file.getParent());
                SQLiteDataSource ds = new SQLiteDataSource(); ds.setUrl("jdbc:sqlite:" + file);
                yield new SqlStorage(ds, "flan_", "SQLITE", null);
            }
            case "MYSQL" -> {
                String prefix = c.getString("storage.mysql.table-prefix", "flan_");
                if (!prefix.matches("[A-Za-z][A-Za-z0-9_]{0,30}")) throw new MessageException("errors.mysql-prefix");
                MysqlDataSource ds = mysqlDataSource(c);
                HikariConfig config = new HikariConfig(); config.setDataSource(ds); config.setPoolName("Flan-MySQL");
                int max = Math.clamp(c.getInt("storage.mysql.pool.maximum-size", 10), 1, 100);
                config.setMaximumPoolSize(max); config.setMinimumIdle(Math.clamp(c.getInt("storage.mysql.pool.minimum-idle", 2), 0, max));
                config.setConnectionTimeout(Math.max(250, c.getLong("storage.mysql.pool.connection-timeout-ms", 10000)));
                config.setMaxLifetime(Math.max(30000, c.getLong("storage.mysql.pool.max-lifetime-ms", 1800000)));
                HikariDataSource pool = new HikariDataSource(config);
                try { yield new SqlStorage(pool, prefix, "MYSQL", pool); }
                catch (Exception e) { pool.close(); throw e; }
            }
            default -> throw new MessageException("errors.storage-type");
        };
    }

    static MysqlDataSource mysqlDataSource(FileConfiguration c) throws java.sql.SQLException {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setServerName(c.getString("storage.mysql.host", "localhost")); ds.setPortNumber(c.getInt("storage.mysql.port", 3306));
        String database = c.getString("storage.mysql.database", "flan");
        if (!database.matches("[A-Za-z0-9_-]{1,64}")) throw new MessageException("errors.mysql-database");
        ds.setDatabaseName(database); ds.setUser(c.getString("storage.mysql.username", "root")); ds.setPassword(c.getString("storage.mysql.password", ""));
        ds.setUseSSL(c.getBoolean("storage.mysql.use-ssl"));
        String params = c.getString("storage.mysql.parameters", "characterEncoding=utf8&serverTimezone=UTC");
        ds.setURL("jdbc:mysql://" + ds.getServerName() + ":" + ds.getPortNumber() + "/" + database + "?useSSL=" + c.getBoolean("storage.mysql.use-ssl") + "&allowPublicKeyRetrieval=" + c.getBoolean("storage.mysql.allow-public-key-retrieval", false) + "&" + params);
        return ds;
    }
}
