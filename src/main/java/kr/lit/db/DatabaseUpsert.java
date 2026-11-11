package kr.lit.db;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

public final class DatabaseUpsert {
    private DatabaseUpsert() {}

    /** Table names, columns and value expressions are SQL literals supplied by the caller. */
    public static void save(JdbcTemplate db, String table, String columns, String key, String values, Object... args) {
        boolean postgres = Boolean.TRUE.equals(db.execute((ConnectionCallback<Boolean>) connection ->
                "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())));
        String target = table + "(" + columns + ")";
        if (!postgres) {
            db.update("MERGE INTO " + target + " KEY(" + key + ") VALUES (" + values + ")", args);
            return;
        }

        var keyColumns = Set.of(key.split(","));
        var assignments = Arrays.stream(columns.split(","))
                .filter(column -> !keyColumns.contains(column))
                .map(column -> column + "=EXCLUDED." + column)
                .collect(Collectors.joining(","));
        String action = assignments.isEmpty() ? "DO NOTHING" : "DO UPDATE SET " + assignments;
        db.update("INSERT INTO " + target + " VALUES (" + values + ") ON CONFLICT (" + key + ") " + action, args);
    }
}
