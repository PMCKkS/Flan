package top.pmckk.flan.storage;

import top.pmckk.flan.config.MessageException;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import top.pmckk.flan.model.*;

public final class SqlStorage implements Storage {
    private final DataSource source;
    private final String prefix, type;
    private final AutoCloseable pool;
    public SqlStorage(DataSource source, String prefix, String type, AutoCloseable pool) throws SQLException {
        if (!prefix.matches("[A-Za-z][A-Za-z0-9_]{0,30}")) throw new MessageException("errors.table-prefix");
        this.source = source; this.prefix = prefix; this.type = type; this.pool = pool;
        try (Connection c = source.getConnection(); Statement s = c.createStatement()) {
            if (type.equals("SQLITE")) { s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA busy_timeout=10000"); }
            String suffix = type.equals("MYSQL") ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "";
            s.execute("CREATE TABLE IF NOT EXISTS " + prefix + "claims (id VARCHAR(36) PRIMARY KEY, world_id VARCHAR(36) NOT NULL, owner_id VARCHAR(36), parent_id VARCHAR(36), payload " + (type.equals("MYSQL") ? "LONGTEXT" : "TEXT") + " NOT NULL)" + suffix);
            s.execute("CREATE TABLE IF NOT EXISTS " + prefix + "players (id VARCHAR(36) PRIMARY KEY, name VARCHAR(64) NOT NULL, allowance BIGINT NOT NULL, accrual_seconds BIGINT NOT NULL)" + suffix);
        }
    }
    public String type() { return type; }
    public Snapshot load() throws Exception {
        List<Claim> claims = new ArrayList<>(); List<PlayerAccount> players = new ArrayList<>();
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT id, payload FROM " + prefix + "claims")) {
                    while (rs.next()) {
                        Claim claim = YamlCodec.claim(rs.getString("payload"));
                        if (!claim.id().toString().equals(rs.getString("id"))) throw new MessageException("errors.claim-id-mismatch");
                        claims.add(claim);
                    }
                }
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM " + prefix + "players")) {
                    while (rs.next()) players.add(new PlayerAccount(UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getLong("allowance"), rs.getLong("accrual_seconds")));
                }
                c.commit();
            } catch (Exception e) { c.rollback(); throw e; }
        }
        return new Snapshot(claims, players);
    }
    public void save(Snapshot snapshot) throws Exception {
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (Statement s = c.createStatement()) { s.executeUpdate("DELETE FROM " + prefix + "claims"); s.executeUpdate("DELETE FROM " + prefix + "players"); }
                try (PreparedStatement s = c.prepareStatement("INSERT INTO " + prefix + "claims (id,world_id,owner_id,parent_id,payload) VALUES (?,?,?,?,?)")) {
                    for (Claim claim : snapshot.claims()) {
                        s.setString(1, claim.id().toString()); s.setString(2, claim.world().toString());
                        s.setString(3, claim.owner() == null ? null : claim.owner().toString());
                        s.setString(4, claim.parent() == null ? null : claim.parent().toString());
                        s.setString(5, YamlCodec.claim(claim)); s.addBatch();
                    } s.executeBatch();
                }
                try (PreparedStatement s = c.prepareStatement("INSERT INTO " + prefix + "players (id,name,allowance,accrual_seconds) VALUES (?,?,?,?)")) {
                    for (PlayerAccount p : snapshot.players()) {
                        s.setString(1, p.id().toString()); s.setString(2, p.name()); s.setLong(3, p.allowance()); s.setLong(4, p.accrualSeconds()); s.addBatch();
                    } s.executeBatch();
                } c.commit();
            } catch (Exception e) { c.rollback(); throw e; }
        }
    }
    public void close() throws Exception { if (pool != null) pool.close(); }
}
