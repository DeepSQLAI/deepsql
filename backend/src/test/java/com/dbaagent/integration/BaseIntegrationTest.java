package com.dbaagent.integration;

import com.dbaagent.model.DatabaseConnection;
import com.dbaagent.service.CredentialService;
import com.dbaagent.support.LlmTestSupport;
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
 *
 * <p>Provides utilities for:
 * <ul>
 *   <li>Finding and requiring test database connections</li>
 *   <li>Checking and requiring LLM configuration via {@link LlmTestSupport}</li>
 *   <li>Building API paths with the correct context path</li>
 * </ul>
 *
 * <p>For tests that require LLM, use either:
 * <ul>
 *   <li>{@code @RequiresLlm} annotation at class or method level (skips before Spring context loads)</li>
 *   <li>{@code requireChatLlm()} / {@code requireEmbeddingLlm()} methods (checks after context loads)</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {
    protected static final String CONTEXT_PATH = "/api";

    @Autowired
    protected WebApplicationContext webApplicationContext;

    @Autowired
    protected CredentialService credentialService;

    @Autowired
    protected LlmTestSupport llmTestSupport;

    protected MockMvc mockMvc;

    @Value("${test.connection.id}")
    protected String testConnectionId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
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

    /**
     * Checks if chat LLM is configured and available.
     * Uses the actual {@link com.dbaagent.llm.LlmConfigResolver} which checks
     * both environment variables and database configuration.
     *
     * @return true if chat LLM credentials are configured
     */
    protected boolean isChatLlmAvailable() {
        return llmTestSupport.isChatAvailable();
    }

    /**
     * Checks if embedding LLM is configured and available.
     * Uses the actual {@link com.dbaagent.llm.LlmConfigResolver} which checks
     * both environment variables and database configuration.
     *
     * @return true if embedding LLM credentials are configured
     */
    protected boolean isEmbeddingLlmAvailable() {
        return llmTestSupport.isEmbeddingAvailable();
    }

    /**
     * Aborts the test if chat LLM is not configured.
     *
     * @param purpose description of what the test needs chat LLM for
     * @throws TestAbortedException if chat LLM is not configured
     */
    protected void requireChatLlm(String purpose) {
        llmTestSupport.requireChat(purpose);
    }

    /**
     * Aborts the test if embedding LLM is not configured.
     *
     * @param purpose description of what the test needs embedding LLM for
     * @throws TestAbortedException if embedding LLM is not configured
     */
    protected void requireEmbeddingLlm(String purpose) {
        llmTestSupport.requireEmbedding(purpose);
    }

    /**
     * Returns a human-readable description of the LLM configuration status.
     * Useful for diagnostic output in tests.
     */
    protected String describeLlmConfiguration() {
        return llmTestSupport.describeConfiguration();
    }
}
