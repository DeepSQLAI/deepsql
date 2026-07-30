package com.dbaagent.provider.postgres;

import com.dbaagent.model.ConnectionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PostgresConnectionProviderTest {

    private PostgresConnectionProvider provider;

    @BeforeEach
    void setUp() {
        provider = new PostgresConnectionProvider();
    }

    @Test
    void getDatabaseType_returnsPostgres() {
        assertEquals("postgres", provider.getDatabaseType());
    }

    @Test
    void getDriverClassName_returnsPostgresDriver() {
        assertEquals("org.postgresql.Driver", provider.getDriverClassName());
    }

    @Test
    void buildJdbcUrl_withoutSsl() {
        ConnectionRequest request = new ConnectionRequest();
        request.setHost("localhost");
        request.setPort(5432);
        request.setDatabase("testdb");
        request.setSsl(false);

        String url = provider.buildJdbcUrl(request, null);

        assertEquals("jdbc:postgresql://localhost:5432/testdb?sslmode=disable", url);
    }

    @Test
    void buildJdbcUrl_withSsl() {
        ConnectionRequest request = new ConnectionRequest();
        request.setHost("db.example.com");
        request.setPort(5432);
        request.setDatabase("production");
        request.setSsl(true);

        String url = provider.buildJdbcUrl(request, null);

        assertEquals("jdbc:postgresql://db.example.com:5432/production?sslmode=require", url);
    }

    @Test
    void buildJdbcUrl_withNullSsl_defaultsToDisabled() {
        ConnectionRequest request = new ConnectionRequest();
        request.setHost("localhost");
        request.setPort(5432);
        request.setDatabase("testdb");
        request.setSsl(null);

        String url = provider.buildJdbcUrl(request, null);

        assertEquals("jdbc:postgresql://localhost:5432/testdb?sslmode=disable", url);
    }

    @Test
    void buildJdbcUrl_withCustomPort() {
        ConnectionRequest request = new ConnectionRequest();
        request.setHost("localhost");
        request.setPort(5433);
        request.setDatabase("testdb");
        request.setSsl(false);

        String url = provider.buildJdbcUrl(request, null);

        assertEquals("jdbc:postgresql://localhost:5433/testdb?sslmode=disable", url);
    }

    @Test
    void buildJdbcUrl_withTunnelPort() {
        ConnectionRequest request = new ConnectionRequest();
        request.setHost("remote-host");
        request.setPort(5432);
        request.setDatabase("testdb");
        request.setSsl(false);

        // When using SSH tunnel, we connect to localhost on tunnel port
        String url = provider.buildJdbcUrl(request, 15432);

        assertEquals("jdbc:postgresql://localhost:15432/testdb?sslmode=disable", url);
    }

    @Test
    void getDefaultPort_returns5432() {
        assertEquals(5432, provider.getDefaultPort());
    }
}
