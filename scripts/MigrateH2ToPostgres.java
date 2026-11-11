import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;

/** Copies a stopped H2 backup into an initialized, empty PostgreSQL public schema. */
class MigrateH2ToPostgres {
    private static final int BATCH_SIZE = 200;

    public static void main(String[] args) throws Exception {
        String sourceUrl = requireEnv("LIT_H2_SOURCE_URL");
        String targetUrl = requireEnv("DATABASE_URL");
        if (!sourceUrl.startsWith("jdbc:h2:file:") || sourceUrl.contains(";")
                || !targetUrl.startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("Use a plain H2 file URL and a PostgreSQL JDBC URL.");
        }

        try (var source = DriverManager.getConnection(sourceUrl + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r",
                     System.getenv().getOrDefault("LIT_H2_SOURCE_USER", "sa"),
                     System.getenv().getOrDefault("LIT_H2_SOURCE_PASSWORD", ""));
             var target = DriverManager.getConnection(targetUrl, requireEnv("DATABASE_USER"),
                     System.getenv().getOrDefault("DATABASE_PASSWORD", ""))) {
            target.setAutoCommit(false);
            try {
                migrate(source, target);
                target.commit();
                System.out.println("Migration committed.");
            } catch (Exception ex) {
                target.rollback();
                throw ex;
            }
        }
    }

    private static void migrate(Connection source, Connection target) throws SQLException {
        var sourceTables = getTables(source, "PUBLIC");
        var targetTables = getTables(target, "public");
        if (!sourceTables.containsKey("app_users") || !sourceTables.containsKey("submissions")
                || !targetTables.keySet().containsAll(sourceTables.keySet())) {
            throw new IllegalStateException("Source or target schema does not match LIT.");
        }
        try (var lock = target.createStatement()) {
            lock.execute("LOCK TABLE " + String.join(",", targetTables.values().stream()
                    .map(MigrateH2ToPostgres::targetTable).toList()) + " IN ACCESS EXCLUSIVE MODE NOWAIT");
        }
        for (String table : targetTables.values()) {
            if (count(target, targetTable(table)) != 0) {
                throw new IllegalStateException("Target table is not empty: " + table);
            }
        }

        var copied = new LinkedHashMap<String,Long>();
        for (String table : getCopyOrder(target, sourceTables.keySet())) {
            long rows = copyTable(source, target, sourceTables.get(table), targetTables.get(table));
            if (rows != count(source, sourceTable(sourceTables.get(table)))
                    || rows != count(target, targetTable(targetTables.get(table)))) {
                throw new IllegalStateException("Row count differs: " + table);
            }
            copied.put(table, rows);
        }
        copied.forEach((table, rows) -> System.out.println(table + ": " + rows));
    }

    private static Map<String,String> getTables(Connection db, String schema) throws SQLException {
        var tables = new TreeMap<String,String>();
        try (var rows = db.getMetaData().getTables(null, schema, "%", new String[]{"TABLE", "BASE TABLE"})) {
            while (rows.next()) {
                String name = rows.getString("TABLE_NAME");
                tables.put(name.toLowerCase(Locale.ROOT), name);
            }
        }
        return tables;
    }

    private static List<String> getCopyOrder(Connection target, Set<String> tables) throws SQLException {
        var dependencies = new TreeMap<String,Set<String>>();
        for (String table : tables) {
            var parents = new HashSet<String>();
            try (var rows = target.getMetaData().getImportedKeys(null, "public", table)) {
                while (rows.next()) {
                    String parent = rows.getString("PKTABLE_NAME");
                    if (!table.equals(parent) && tables.contains(parent)) parents.add(parent);
                }
            }
            dependencies.put(table, parents);
        }

        var ordered = new ArrayList<String>();
        var copied = new HashSet<String>();
        while (!dependencies.isEmpty()) {
            int before = dependencies.size();
            for (var iterator = dependencies.entrySet().iterator(); iterator.hasNext();) {
                var entry = iterator.next();
                if (copied.containsAll(entry.getValue())) {
                    ordered.add(entry.getKey());
                    copied.add(entry.getKey());
                    iterator.remove();
                }
            }
            if (before == dependencies.size()) {
                throw new IllegalStateException("Cyclic table dependencies: " + dependencies.keySet());
            }
        }
        return ordered;
    }

    private static long copyTable(Connection source, Connection target, String from, String to) throws SQLException {
        try (var read = source.createStatement();
             var rows = read.executeQuery("SELECT * FROM " + sourceTable(from))) {
            var metadata = rows.getMetaData();
            int columnCount = metadata.getColumnCount();
            var columns = new ArrayList<String>();
            var identities = new ArrayList<String>();
            for (int column = 1; column <= columnCount; column++) {
                String name = metadata.getColumnName(column).toLowerCase(Locale.ROOT);
                columns.add(quote(name));
                if (metadata.isAutoIncrement(column)) identities.add(name);
            }
            String table = targetTable(to);
            long rowCount = 0;
            String sql = "INSERT INTO " + table + " (" + String.join(",", columns) + ") VALUES ("
                    + String.join(",", Collections.nCopies(columnCount, "?")) + ")";
            try (var insert = target.prepareStatement(sql)) {
                while (rows.next()) {
                    for (int column = 1; column <= columnCount; column++) {
                        Object value = switch (metadata.getColumnType(column)) {
                            case Types.TIMESTAMP_WITH_TIMEZONE -> rows.getObject(column, OffsetDateTime.class);
                            case Types.CLOB, Types.NCLOB -> rows.getString(column);
                            case Types.BLOB -> rows.getBytes(column);
                            default -> rows.getObject(column);
                        };
                        insert.setObject(column, value);
                    }
                    insert.addBatch();
                    if (++rowCount % BATCH_SIZE == 0) insert.executeBatch();
                }
                if (rowCount % BATCH_SIZE != 0) insert.executeBatch();
            }
            for (String column : identities) restartIdentity(source, target, from, table, column);
            return rowCount;
        }
    }

    private static void restartIdentity(Connection source, Connection target, String from, String table,
                                        String column) throws SQLException {
        long next;
        try (var sql = source.prepareStatement("""
                SELECT IDENTITY_BASE FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME=? AND LOWER(COLUMN_NAME)=?
                """)) {
            sql.setString(1, from);
            sql.setString(2, column);
            try (var value = sql.executeQuery()) {
                if (!value.next() || value.getObject(1) == null)
                    throw new IllegalStateException("Cannot determine next identity: " + from + "." + column);
                next = value.getLong(1);
            }
        }
        try (var sql = target.createStatement()) {
            try (var rows = sql.executeQuery("SELECT COALESCE(MAX(" + quote(column) + "),0) FROM " + table)) {
                rows.next();
                next = Math.max(next, Math.addExact(rows.getLong(1), 1));
            }
            sql.execute("ALTER TABLE " + table + " ALTER COLUMN " + quote(column) + " RESTART WITH " + next);
        }
    }

    private static long count(Connection db, String table) throws SQLException {
        try (var sql = db.createStatement(); var rows = sql.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static String quote(String name) { return "\"" + name.replace("\"", "\"\"") + "\""; }
    private static String sourceTable(String name) { return quote("PUBLIC") + "." + quote(name); }
    private static String targetTable(String name) { return quote("public") + "." + quote(name); }
    private static String requireEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }
}
