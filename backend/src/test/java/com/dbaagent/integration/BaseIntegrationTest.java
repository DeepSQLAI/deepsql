package com.dbaagent.integration;

import com.dbaagent.model.DatabaseConnection;
import com.dbaagent.service.CredentialService;
import org.opentest4j.TestAbortedException;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Base class for integration tests that test actual controllers
 * with real database connections (no mocking).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {
    protected static final String CONTEXT_PATH = "/api";

    @Autowired
    protected WebApplicationContext webApplicationContext;

    @Autowired
    protected CredentialService credentialService;

    protected MockMvc mockMvc;

    @Value("${test.connection.id}")
    protected String testConnectionId;

    @BeforeEach
    void setUp() {
        // Configure MockMvc
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();

        // Subclasses can override this method to add custom setup
    }

    protected String getTestConnectionId() {
        return testConnectionId;
    }

    protected String requireTestConnectionId(String purpose) {
        return findPreferredTestConnection()
            .map(DatabaseConnection::getId)
            .orElseThrow(() -> new TestAbortedException(
                "No suitable test connection is available for " + purpose
                    + ". Configure test.connection.id or create a non-vault application connection in the local vault DB."
            ));
    }

    protected java.util.Optional<DatabaseConnection> findPreferredTestConnection() {
        if (testConnectionId != null && credentialService.connectionExists(testConnectionId)) {
            return java.util.Optional.of(credentialService.getConnectionEntity(testConnectionId));
        }

        return credentialService.getAllConnections().stream()
            .filter(this::isPreferredApplicationConnection)
            .findFirst()
            .or(() -> credentialService.getAllConnections().stream()
                .filter(this::isNonVaultConnection)
                .findFirst());
    }

    private boolean isPreferredApplicationConnection(DatabaseConnection connection) {
        return connection != null
            && "mysql".equalsIgnoreCase(connection.getDbType())
            && isNonVaultConnection(connection);
    }

    private boolean isNonVaultConnection(DatabaseConnection connection) {
        return connection != null
            && (connection.getConnectionName() == null
                || !"vaultdb".equalsIgnoreCase(connection.getConnectionName()));
    }

    protected String apiPath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return CONTEXT_PATH;
        }
        return path.startsWith(CONTEXT_PATH) ? path : CONTEXT_PATH + path;
    }
}
