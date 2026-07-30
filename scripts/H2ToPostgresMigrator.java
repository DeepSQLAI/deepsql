import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class H2ToPostgresMigrator {
    private static final int BATCH_SIZE = 500;

    public static void main(String[] args) throws Exception {
        String h2Url = env("H2_URL", "jdbc:h2:file:./backend/data/vault;AUTO_SERVER=TRUE");
        String h2User = env("H2_USER", "sa");
        String h2Password = env("H2_PASSWORD", "");
        String pgUrl = env("PG_URL", "jdbc:postgresql://localhost:5432/dba_agent");
        String pgUser = env("PG_USER", "postgres");
        String pgPassword = env("PG_PASSWORD", "");

        ensureDatabaseExists(pgUrl, pgUser, pgPassword);
        boolean migrateData = !"false".equalsIgnoreCase(env("MIGRATE_DATA", "true"));
        if (!migrateData) {
            System.out.println("Database ensured. MIGRATE_DATA=false, skipping data copy.");
            return;
        }

        try (Connection h2 = DriverManager.getConnection(h2Url, h2User, h2Password);
             Connection pg = DriverManager.getConnection(pgUrl, pgUser, pgPassword)) {
            pg.setAutoCommit(false);
            setReplicationRole(pg, "replica");

            List<String> tables = fetchTables(h2);
            for (String table : tables) {
                migrateTable(h2, pg, table);
            }

            setReplicationRole(pg, "origin");
        }
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        return value;
    }

    private static void setReplicationRole(Connection pg, String role) throws SQLException {
        try (Statement statement = pg.createStatement()) {
            statement.execute("SET session_replication_role = " + role);
        }
    }

    private static void ensureDatabaseExists(String pgUrl, String pgUser, String pgPassword) throws SQLException {
        String dbName = extractDatabaseName(pgUrl);
        if (dbName == null || dbName.isEmpty()) {
            return;
        }

        String adminUrl = toAdminUrl(pgUrl);
        try (Connection admin = DriverManager.getConnection(adminUrl, pgUser, pgPassword);
             PreparedStatement exists = admin.prepareStatement(
                 "SELECT 1 FROM pg_database WHERE datname = ?")) {
            exists.setString(1, dbName);
            try (ResultSet resultSet = exists.executeQuery()) {
                if (resultSet.next()) {
                    return;
                }
            }
        }

        if (!dbName.matches("[a-zA-Z0-9_]+")) {
            throw new SQLException("Unsafe database name: " + dbName);
        }

        try (Connection admin = DriverManager.getConnection(adminUrl, pgUser, pgPassword);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + quotePg(dbName));
        }
    }

    private static String extractDatabaseName(String pgUrl) {
        URI uri = URI.create(pgUrl.substring("jdbc:".length()));
        String path = uri.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return "";
        }
        return path.substring(1);
    }

    private static String toAdminUrl(String pgUrl) {
        URI uri = URI.create(pgUrl.substring("jdbc:".length()));
        StringBuilder builder = new StringBuilder("jdbc:")
            .append(uri.getScheme())
            .append("://")
            .append(uri.getHost());
        if (uri.getPort() > 0) {
            builder.append(":").append(uri.getPort());
        }
        builder.append("/postgres");
        if (uri.getQuery() != null && !uri.getQuery().isEmpty()) {
            builder.append("?").append(uri.getQuery());
        }
        return builder.toString();
    }

    private static List<String> fetchTables(Connection h2) throws SQLException {
        List<String> tables = new ArrayList<>();
        String sql = "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                     "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE' " +
                     "ORDER BY TABLE_NAME";
        try (Statement statement = h2.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                tables.add(resultSet.getString("TABLE_NAME"));
            }
        }
        return tables;
    }

    private static List<ColumnInfo> fetchColumns(Connection h2, String tableName) throws SQLException {
        List<ColumnInfo> columns = new ArrayList<>();
        String sql = "SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, " +
                     "NUMERIC_PRECISION, NUMERIC_SCALE, IS_NULLABLE " +
                     "FROM INFORMATION_SCHEMA.COLUMNS " +
                     "WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ? " +
                     "ORDER BY ORDINAL_POSITION";
        try (PreparedStatement statement = h2.prepareStatement(sql)) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.add(new ColumnInfo(
                        resultSet.getString("COLUMN_NAME"),
                        resultSet.getString("DATA_TYPE"),
                        (Long) resultSet.getObject("CHARACTER_MAXIMUM_LENGTH"),
                        (Integer) resultSet.getObject("NUMERIC_PRECISION"),
                        (Integer) resultSet.getObject("NUMERIC_SCALE"),
                        "YES".equalsIgnoreCase(resultSet.getString("IS_NULLABLE"))
                    ));
                }
            }
        }
        return columns;
    }

    private static void migrateTable(Connection h2, Connection pg, String tableName) throws SQLException {
        List<ColumnInfo> columns = fetchColumns(h2, tableName);
        if (columns.isEmpty()) {
            return;
        }

        String pgTable = normalizeIdentifier(tableName);
        List<String> primaryKeys = fetchPrimaryKeys(h2, tableName);
        ensureTable(pg, pgTable, columns, primaryKeys);
        String selectSql = buildH2Select(tableName, columns);
        String insertSql = buildPgInsert(pgTable, columns);

        try (Statement truncate = pg.createStatement()) {
            truncate.execute("TRUNCATE TABLE " + quotePg(pgTable) + " RESTART IDENTITY CASCADE");
            pg.commit();
        }

        int rowCount = 0;
        try (Statement select = h2.createStatement()) {
            select.setFetchSize(BATCH_SIZE);
            try (ResultSet resultSet = select.executeQuery(selectSql);
                 PreparedStatement insert = pg.prepareStatement(insertSql)) {
                ResultSetMetaData meta = resultSet.getMetaData();
                int columnCount = meta.getColumnCount();

                while (resultSet.next()) {
                    for (int i = 1; i <= columnCount; i++) {
                        insert.setObject(i, resultSet.getObject(i));
                    }
                    insert.addBatch();
                    rowCount++;
                    if (rowCount % BATCH_SIZE == 0) {
                        insert.executeBatch();
                        pg.commit();
                    }
                }
                insert.executeBatch();
                pg.commit();
            }
        }

        System.out.println("Migrated " + rowCount + " rows into " + pgTable);
    }

    private static String buildH2Select(String tableName, List<ColumnInfo> columns) {
        StringBuilder builder = new StringBuilder("SELECT ");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(quoteH2(columns.get(i).name));
        }
        builder.append(" FROM \"PUBLIC\".").append(quoteH2(tableName));
        return builder.toString();
    }

    private static String buildPgInsert(String tableName, List<ColumnInfo> columns) {
        StringBuilder builder = new StringBuilder("INSERT INTO ");
        builder.append(quotePg(tableName)).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(quotePg(normalizeIdentifier(columns.get(i).name)));
        }
        builder.append(") VALUES (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append("?");
        }
        builder.append(")");
        return builder.toString();
    }

    private static List<String> fetchPrimaryKeys(Connection h2, String tableName) throws SQLException {
        List<String> keys = new ArrayList<>();
        String sql = "SELECT k.COLUMN_NAME " +
                     "FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS t " +
                     "JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE k " +
                     "ON t.CONSTRAINT_NAME = k.CONSTRAINT_NAME " +
                     "AND t.TABLE_NAME = k.TABLE_NAME " +
                     "WHERE t.TABLE_SCHEMA = 'PUBLIC' " +
                     "AND t.TABLE_NAME = ? " +
                     "AND t.CONSTRAINT_TYPE = 'PRIMARY KEY' " +
                     "ORDER BY k.ORDINAL_POSITION";
        try (PreparedStatement statement = h2.prepareStatement(sql)) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    keys.add(resultSet.getString("COLUMN_NAME"));
                }
            }
        }
        return keys;
    }

    private static void ensureTable(Connection pg,
                                    String tableName,
                                    List<ColumnInfo> columns,
                                    List<String> primaryKeys) throws SQLException {
        if (tableExists(pg, tableName)) {
            return;
        }

        StringBuilder ddl = new StringBuilder("CREATE TABLE ");
        ddl.append(quotePg(tableName)).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            ColumnInfo column = columns.get(i);
            if (i > 0) {
                ddl.append(", ");
            }
            ddl.append(quotePg(normalizeIdentifier(column.name))).append(" ")
               .append(toPostgresType(column));
            if (!column.nullable) {
                ddl.append(" NOT NULL");
            }
        }

        if (!primaryKeys.isEmpty()) {
            ddl.append(", PRIMARY KEY (");
            for (int i = 0; i < primaryKeys.size(); i++) {
                if (i > 0) {
                    ddl.append(", ");
                }
                ddl.append(quotePg(normalizeIdentifier(primaryKeys.get(i))));
            }
            ddl.append(")");
        }

        ddl.append(")");

        try (Statement statement = pg.createStatement()) {
            statement.execute(ddl.toString());
            pg.commit();
        }
    }

    private static boolean tableExists(Connection pg, String tableName) throws SQLException {
        String sql = "SELECT 1 FROM information_schema.tables " +
                     "WHERE table_schema = 'public' AND table_name = ?";
        try (PreparedStatement statement = pg.prepareStatement(sql)) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static String toPostgresType(ColumnInfo column) {
        String type = column.dataType == null ? "" : column.dataType.toUpperCase(Locale.ROOT);
        if ("CHARACTER VARYING".equals(type)) {
            if (column.characterLength == null || column.characterLength > 10000) {
                return "TEXT";
            }
            return "VARCHAR(" + column.characterLength + ")";
        }
        if ("CHARACTER".equals(type)) {
            if (column.characterLength == null || column.characterLength > 10000) {
                return "TEXT";
            }
            return "CHAR(" + column.characterLength + ")";
        }
        if ("BOOLEAN".equals(type)) {
            return "BOOLEAN";
        }
        if ("INTEGER".equals(type)) {
            return "INTEGER";
        }
        if ("BIGINT".equals(type)) {
            return "BIGINT";
        }
        if ("SMALLINT".equals(type)) {
            return "SMALLINT";
        }
        if ("TINYINT".equals(type)) {
            return "SMALLINT";
        }
        if ("DOUBLE".equals(type) || "DOUBLE PRECISION".equals(type)) {
            return "DOUBLE PRECISION";
        }
        if ("REAL".equals(type)) {
            return "REAL";
        }
        if ("DECIMAL".equals(type) || "NUMERIC".equals(type)) {
            if (column.numericPrecision != null && column.numericScale != null) {
                return "NUMERIC(" + column.numericPrecision + "," + column.numericScale + ")";
            }
            return "NUMERIC";
        }
        if ("DATE".equals(type)) {
            return "DATE";
        }
        if ("TIMESTAMP".equals(type)) {
            return "TIMESTAMP";
        }
        if ("TIMESTAMP WITH TIME ZONE".equals(type)) {
            return "TIMESTAMPTZ";
        }
        if ("ENUM".equals(type)) {
            return "VARCHAR(255)";
        }
        if ("BLOB".equals(type)) {
            return "BYTEA";
        }
        if ("CLOB".equals(type)) {
            return "TEXT";
        }
        return "TEXT";
    }

    private static String normalizeIdentifier(String identifier) {
        return identifier.toLowerCase(Locale.ROOT);
    }

    private static String quoteH2(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String quotePg(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static class ColumnInfo {
        private final String name;
        private final String dataType;
        private final Long characterLength;
        private final Integer numericPrecision;
        private final Integer numericScale;
        private final boolean nullable;

        private ColumnInfo(String name,
                           String dataType,
                           Long characterLength,
                           Integer numericPrecision,
                           Integer numericScale,
                           boolean nullable) {
            this.name = name;
            this.dataType = dataType;
            this.characterLength = characterLength;
            this.numericPrecision = numericPrecision;
            this.numericScale = numericScale;
            this.nullable = nullable;
        }
    }
}
