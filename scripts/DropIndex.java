import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class DropIndex {
    public static void main(String[] args) throws Exception {
        String url = env("PG_URL", "jdbc:postgresql://localhost:5432/dba_agent");
        String user = env("PG_USER", "postgres");
        String password = env("PG_PASSWORD", "");
        String indexName = env("INDEX_NAME", "idx_connection_table");

        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX IF EXISTS public." + quoteIdentifier(indexName));
        }

        System.out.println("Dropped index if it existed: " + indexName);
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isEmpty()) ? defaultValue : value;
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
