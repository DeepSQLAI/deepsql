import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class ResetPostgres {
    public static void main(String[] args) throws Exception {
        String url = env("PG_URL", "jdbc:postgresql://localhost:5432/dba_agent");
        String user = env("PG_USER", "postgres");
        String password = env("PG_PASSWORD", "");

        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS public CASCADE");
            statement.execute("CREATE SCHEMA public");
            statement.execute("GRANT ALL ON SCHEMA public TO postgres");
            statement.execute("GRANT ALL ON SCHEMA public TO public");
        }

        System.out.println("Reset schema for " + url);
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isEmpty()) ? defaultValue : value;
    }
}
