# Spring AI Migration & Feedback Learning Implementation Plan

## Executive Summary

This plan addresses three interconnected issues:
1. **Memory loss across sessions** - User learnings are forgotten after logout
2. **Missing column value context** - Low-cardinality column values not embedded
3. **Boilerplate code** - Current ChatService is 1,277 lines of manual Azure SDK code

**Solution**: Migrate to Spring AI 2.0 with custom advisors for feedback learning and column value injection.

---

## Problem Analysis

### Current User Pain Point
> "I chatted with the agent to help come up with a question for a particular business question. Had to spoonfeed some information about the right values to apply the column filters. But when I ask the same question, it seems to have forgotten the memory and I had to start from scratch."

### Root Causes

| Issue | Current State | Impact |
|-------|---------------|--------|
| **No feedback persistence** | User corrections lost on logout | Re-teaching required |
| **No column values in RAG** | Only DDL/schema embedded | LLM doesn't know valid values |
| **In-session memory only** | Chat history exists but not learnings | Context lost between sessions |

### What Needs to be Embedded (Currently Missing)

```
Example: user teaches "for booking status, use 'confirmed', 'cancelled', 'pending'"

This should be stored as:
- Embedding: "bookings.status column valid values: confirmed, cancelled, pending"
- Metadata: { connectionId, tableName, columnName, type: "COLUMN_VALUES" }
- Retrieved when: user asks about bookings or status
```

---

## Solution Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│                         Spring AI 2.0 Stack                            │
├────────────────────────────────────────────────────────────────────────┤
│  ChatClient (Fluent API)                                               │
│  ├── AzureOpenAiChatModel (auto-configured)                            │
│  ├── AzureOpenAiEmbeddingModel (auto-configured)                       │
│  └── AzureVectorStore (existing index: dba-agent-training-data)        │
├────────────────────────────────────────────────────────────────────────┤
│  Advisor Chain (executed in order)                                     │
│  ├── 1. MessageChatMemoryAdvisor (conversation history)                │
│  ├── 2. FeedbackLearningAdvisor (user corrections/teachings) [NEW]     │
│  ├── 3. ColumnValueAdvisor (low-cardinality values) [NEW]              │
│  ├── 4. SchemaContextAdvisor (existing classification logic)           │
│  ├── 5. PerformanceInsightsAdvisor (existing performance logic)        │
│  └── 6. QuestionAnswerAdvisor (RAG from training data)                 │
├────────────────────────────────────────────────────────────────────────┤
│  Tools                                                                 │
│  └── @Tool executeSql(String sql) - SQL execution                      │
└────────────────────────────────────────────────────────────────────────┘
```

---

## Implementation Phases

### Phase 1: Database Schema for Feedback & Column Values

#### 1.1 New Entity: ChatFeedback

```java
@Entity
@Table(name = "chat_feedback")
public class ChatFeedback {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String connectionId;
    private String chatId;
    private String messageId;

    @Enumerated(EnumType.STRING)
    private FeedbackType type;  // CORRECTION, TEACHING, THUMBS_UP, THUMBS_DOWN

    private String originalResponse;
    private String userCorrection;
    private String learnedContent;  // What should be remembered

    // For embedding
    @Column(columnDefinition = "TEXT")
    private String embeddingContent;  // Formatted for RAG

    private Boolean embedded;  // Has this been added to vector store?

    private LocalDateTime createdAt;
    private String createdBy;

    public enum FeedbackType {
        CORRECTION,      // User corrected a wrong answer
        TEACHING,        // User taught new information
        THUMBS_UP,       // Good response
        THUMBS_DOWN,     // Bad response
        COLUMN_VALUES    // User specified valid column values
    }
}
```

#### 1.2 New Entity: ColumnValueCache

```java
@Entity
@Table(name = "column_value_cache")
public class ColumnValueCache {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String connectionId;
    private String tableName;
    private String columnName;

    private Long distinctCount;

    @Column(columnDefinition = "TEXT")
    private String sampleValues;  // JSON array of sample values

    @Column(columnDefinition = "TEXT")
    private String allValues;  // JSON array if cardinality < 100

    private Boolean isLowCardinality;  // distinctCount < 100
    private Boolean embedded;  // Has this been added to vector store?

    private LocalDateTime analyzedAt;
    private LocalDateTime embeddedAt;
}
```

#### 1.3 Flyway Migration: V36__feedback_and_column_values.sql

```sql
-- Chat feedback for learning
CREATE TABLE chat_feedback (
    id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(36) NOT NULL,
    chat_id VARCHAR(36),
    message_id VARCHAR(36),
    type VARCHAR(50) NOT NULL,
    original_response TEXT,
    user_correction TEXT,
    learned_content TEXT,
    embedding_content TEXT,
    embedded BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(255)
);

CREATE INDEX idx_feedback_connection ON chat_feedback(connection_id);
CREATE INDEX idx_feedback_embedded ON chat_feedback(connection_id, embedded);
CREATE INDEX idx_feedback_type ON chat_feedback(connection_id, type);

-- Column value cache for low-cardinality columns
CREATE TABLE column_value_cache (
    id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(36) NOT NULL,
    table_name VARCHAR(255) NOT NULL,
    column_name VARCHAR(255) NOT NULL,
    distinct_count BIGINT,
    sample_values TEXT,
    all_values TEXT,
    is_low_cardinality BOOLEAN DEFAULT FALSE,
    embedded BOOLEAN DEFAULT FALSE,
    analyzed_at TIMESTAMP,
    embedded_at TIMESTAMP,
    UNIQUE(connection_id, table_name, column_name)
);

CREATE INDEX idx_colval_connection ON column_value_cache(connection_id);
CREATE INDEX idx_colval_low_card ON column_value_cache(connection_id, is_low_cardinality);
CREATE INDEX idx_colval_embedded ON column_value_cache(connection_id, embedded);
```

---

### Phase 2: Spring AI Dependencies & Configuration

#### 2.1 Maven Dependencies (pom.xml additions)

```xml
<!-- Spring AI BOM -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>2.0.0-M2</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<!-- Spring AI Azure OpenAI -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-azure-openai</artifactId>
</dependency>

<!-- Spring AI Azure Vector Store -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-azure-store</artifactId>
</dependency>
```

#### 2.2 Application Properties

```properties
# Spring AI Azure OpenAI
spring.ai.azure.openai.api-key=${azure.openai.key}
spring.ai.azure.openai.endpoint=${azure.openai.endpoint}
spring.ai.azure.openai.chat.options.deployment-name=${azure.openai.chat-deployment}
spring.ai.azure.openai.chat.options.temperature=0.7
spring.ai.azure.openai.embedding.options.deployment-name=${azure.openai.embedding-deployment}

# Spring AI Azure Vector Store
spring.ai.vectorstore.azure.url=${azure.search.endpoint}
spring.ai.vectorstore.azure.api-key=${azure.search.api-key}
spring.ai.vectorstore.azure.index-name=${azure.search.index-name}
spring.ai.vectorstore.azure.initialize-schema=false
```

#### 2.3 Spring AI Configuration

```java
@Configuration
public class SpringAIConfig {

    @Bean
    public SearchIndexClient searchIndexClient(
            @Value("${azure.search.endpoint}") String endpoint,
            @Value("${azure.search.api-key}") String apiKey) {
        return new SearchIndexClientBuilder()
            .endpoint(endpoint)
            .credential(new AzureKeyCredential(apiKey))
            .buildClient();
    }

    @Bean
    public VectorStore vectorStore(
            SearchIndexClient searchIndexClient,
            EmbeddingModel embeddingModel,
            @Value("${azure.search.index-name}") String indexName) {
        return AzureVectorStore.builder(searchIndexClient, embeddingModel)
            .indexName(indexName)
            .initializeSchema(false)  // Use existing index
            .defaultTopK(10)
            .defaultSimilarityThreshold(0.7)
            .filterMetadataFields(List.of(
                MetadataField.text("connectionId"),
                MetadataField.text("type"),
                MetadataField.text("tableName"),
                MetadataField.text("columnName")
            ))
            .build();
    }

    @Bean
    public ChatMemory chatMemory() {
        // Use database-backed memory for persistence
        return new DatabaseChatMemory(chatHistoryService);
    }

    @Bean
    public ChatClient chatClient(
            AzureOpenAiChatModel chatModel,
            ChatMemory chatMemory,
            VectorStore vectorStore,
            FeedbackLearningAdvisor feedbackAdvisor,
            ColumnValueAdvisor columnValueAdvisor,
            SchemaContextAdvisor schemaContextAdvisor,
            PerformanceInsightsAdvisor performanceAdvisor) {

        return ChatClient.builder(chatModel)
            .defaultAdvisors(
                // Order matters - lower order executes first
                MessageChatMemoryAdvisor.builder(chatMemory)
                    .order(100)
                    .build(),
                feedbackAdvisor,        // Order 200
                columnValueAdvisor,     // Order 300
                schemaContextAdvisor,   // Order 400
                performanceAdvisor,     // Order 500
                QuestionAnswerAdvisor.builder(vectorStore)
                    .order(600)
                    .build()
            )
            .build();
    }
}
```

---

### Phase 3: Custom Advisors

#### 3.1 FeedbackLearningAdvisor

```java
@Component
public class FeedbackLearningAdvisor implements CallAdvisor, StreamAdvisor {

    private final VectorStore vectorStore;
    private final ChatFeedbackRepository feedbackRepository;

    @Override
    public int getOrder() {
        return 200;  // After memory, before column values
    }

    @Override
    public String getName() {
        return "FeedbackLearningAdvisor";
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request) {
        String connectionId = request.context().get("connectionId");
        String userMessage = request.prompt().getUserMessage().getText();

        // Search for relevant learned feedback
        List<Document> relevantLearnings = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(userMessage)
                .topK(5)
                .filterExpression("connectionId == '" + connectionId + "' && " +
                                  "type in ['CORRECTION', 'TEACHING', 'COLUMN_VALUES']")
                .build()
        );

        if (relevantLearnings.isEmpty()) {
            return request;
        }

        // Build learning context
        StringBuilder learningContext = new StringBuilder();
        learningContext.append("\n\n=== LEARNED FROM PREVIOUS SESSIONS ===\n");
        learningContext.append("The user previously taught the following:\n\n");

        for (Document doc : relevantLearnings) {
            learningContext.append("- ").append(doc.getContent()).append("\n");
        }

        learningContext.append("\nApply this knowledge when answering.\n");

        // Augment system message with learnings
        return request.mutate()
            .prompt(request.prompt().augmentSystemMessage(learningContext.toString()))
            .build();
    }
}
```

#### 3.2 ColumnValueAdvisor

```java
@Component
public class ColumnValueAdvisor implements CallAdvisor, StreamAdvisor {

    private final ColumnValueCacheRepository columnValueRepository;
    private final VectorStore vectorStore;

    @Override
    public int getOrder() {
        return 300;  // After feedback, before schema
    }

    @Override
    public String getName() {
        return "ColumnValueAdvisor";
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request) {
        String connectionId = request.context().get("connectionId");
        String userMessage = request.prompt().getUserMessage().getText();

        // Search for relevant column values
        List<Document> relevantColumns = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(userMessage)
                .topK(5)
                .filterExpression("connectionId == '" + connectionId + "' && " +
                                  "type == 'COLUMN_VALUES'")
                .build()
        );

        if (relevantColumns.isEmpty()) {
            return request;
        }

        // Build column value context
        StringBuilder columnContext = new StringBuilder();
        columnContext.append("\n\n=== VALID COLUMN VALUES ===\n");
        columnContext.append("Use these exact values when filtering:\n\n");

        for (Document doc : relevantColumns) {
            columnContext.append(doc.getContent()).append("\n");
        }

        return request.mutate()
            .prompt(request.prompt().augmentSystemMessage(columnContext.toString()))
            .build();
    }
}
```

#### 3.3 SchemaContextAdvisor (refactored from existing code)

```java
@Component
public class SchemaContextAdvisor implements CallAdvisor, StreamAdvisor {

    private final SchemaClassificationService classificationService;
    private final TableClassificationRepository tableRepository;

    @Override
    public int getOrder() {
        return 400;
    }

    @Override
    public String getName() {
        return "SchemaContextAdvisor";
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request) {
        String connectionId = request.context().get("connectionId");

        // Reuse existing buildClassificationContext logic
        String classificationContext = buildClassificationContext(connectionId);

        if (classificationContext.isEmpty()) {
            return request;
        }

        return request.mutate()
            .prompt(request.prompt().augmentSystemMessage(classificationContext))
            .build();
    }

    // Move existing ChatService.buildClassificationContext() here
    private String buildClassificationContext(String connectionId) {
        // ... existing logic from ChatService lines 553-714
    }
}
```

---

### Phase 4: Column Value Collection Service

#### 4.1 ColumnValueCollectionService

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class ColumnValueCollectionService {

    private final ConnectionService connectionService;
    private final ColumnValueCacheRepository repository;
    private final VectorStore vectorStore;

    private static final int LOW_CARDINALITY_THRESHOLD = 100;
    private static final int SAMPLE_SIZE = 20;

    /**
     * Analyze and cache column values for a connection.
     * Called after Key Column Analysis or on-demand.
     */
    @Transactional
    public void analyzeColumnValues(String connectionId, HttpServletRequest request) {
        JdbcTemplate jdbc = connectionService.getJdbcTemplate(connectionId, request);
        String dbType = connectionService.getDbType(connectionId);

        // Get columns with low cardinality from key_column_analysis
        List<KeyColumnAnalysis> lowCardColumns = keyColumnRepository
            .findByConnectionIdAndDistinctCountLessThan(connectionId, LOW_CARDINALITY_THRESHOLD);

        for (KeyColumnAnalysis column : lowCardColumns) {
            try {
                collectAndCacheValues(jdbc, connectionId, column, dbType);
            } catch (Exception e) {
                log.warn("Failed to collect values for {}.{}: {}",
                    column.getTableName(), column.getColumnName(), e.getMessage());
            }
        }
    }

    private void collectAndCacheValues(JdbcTemplate jdbc, String connectionId,
                                       KeyColumnAnalysis column, String dbType) {
        String tableName = column.getTableName();
        String columnName = column.getColumnName();

        // Query distinct values
        String sql = String.format(
            "SELECT DISTINCT %s FROM %s WHERE %s IS NOT NULL ORDER BY %s LIMIT %d",
            columnName, tableName, columnName, columnName, LOW_CARDINALITY_THRESHOLD
        );

        List<String> values = jdbc.queryForList(sql, String.class);

        // Create or update cache entry
        ColumnValueCache cache = repository
            .findByConnectionIdAndTableNameAndColumnName(connectionId, tableName, columnName)
            .orElse(new ColumnValueCache());

        cache.setConnectionId(connectionId);
        cache.setTableName(tableName);
        cache.setColumnName(columnName);
        cache.setDistinctCount((long) values.size());
        cache.setIsLowCardinality(values.size() < LOW_CARDINALITY_THRESHOLD);
        cache.setAllValues(objectMapper.writeValueAsString(values));
        cache.setSampleValues(objectMapper.writeValueAsString(
            values.subList(0, Math.min(SAMPLE_SIZE, values.size()))
        ));
        cache.setAnalyzedAt(LocalDateTime.now());
        cache.setEmbedded(false);  // Mark for embedding

        repository.save(cache);

        // Embed into vector store
        embedColumnValues(cache);
    }

    private void embedColumnValues(ColumnValueCache cache) {
        // Format for embedding
        String content = String.format(
            "Table: %s, Column: %s\nValid values: %s\n" +
            "Use these exact values when filtering on %s.%s",
            cache.getTableName(),
            cache.getColumnName(),
            cache.getAllValues(),
            cache.getTableName(),
            cache.getColumnName()
        );

        Document doc = new Document(content, Map.of(
            "connectionId", cache.getConnectionId(),
            "type", "COLUMN_VALUES",
            "tableName", cache.getTableName(),
            "columnName", cache.getColumnName()
        ));

        vectorStore.add(List.of(doc));

        cache.setEmbedded(true);
        cache.setEmbeddedAt(LocalDateTime.now());
        repository.save(cache);

        log.info("Embedded column values for {}.{} ({} values)",
            cache.getTableName(), cache.getColumnName(), cache.getDistinctCount());
    }
}
```

---

### Phase 5: Feedback Capture API

#### 5.1 ChatFeedbackController

```java
@RestController
@RequestMapping("/api/chat/feedback")
@RequiredArgsConstructor
public class ChatFeedbackController {

    private final ChatFeedbackService feedbackService;

    /**
     * Submit feedback on a chat response.
     * Called when user clicks thumbs up/down or provides correction.
     */
    @PostMapping
    public ResponseEntity<ChatFeedback> submitFeedback(
            @RequestBody FeedbackRequest request) {
        ChatFeedback feedback = feedbackService.saveFeedback(request);
        return ResponseEntity.ok(feedback);
    }

    /**
     * Teach the agent something new.
     * Called when user explicitly teaches column values or business rules.
     */
    @PostMapping("/teach")
    public ResponseEntity<ChatFeedback> teach(
            @RequestBody TeachRequest request) {
        ChatFeedback feedback = feedbackService.saveTeaching(request);
        return ResponseEntity.ok(feedback);
    }
}

// Request DTOs
record FeedbackRequest(
    String connectionId,
    String chatId,
    String messageId,
    ChatFeedback.FeedbackType type,
    String originalResponse,
    String userCorrection
) {}

record TeachRequest(
    String connectionId,
    String tableName,
    String columnName,
    List<String> validValues,
    String additionalContext
) {}
```

#### 5.2 ChatFeedbackService

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatFeedbackService {

    private final ChatFeedbackRepository repository;
    private final VectorStore vectorStore;

    @Transactional
    public ChatFeedback saveFeedback(FeedbackRequest request) {
        ChatFeedback feedback = ChatFeedback.builder()
            .connectionId(request.connectionId())
            .chatId(request.chatId())
            .messageId(request.messageId())
            .type(request.type())
            .originalResponse(request.originalResponse())
            .userCorrection(request.userCorrection())
            .createdAt(LocalDateTime.now())
            .embedded(false)
            .build();

        // Format for embedding if it's a correction or teaching
        if (request.type() == FeedbackType.CORRECTION ||
            request.type() == FeedbackType.TEACHING) {

            String embeddingContent = formatForEmbedding(request);
            feedback.setEmbeddingContent(embeddingContent);
            feedback.setLearnedContent(request.userCorrection());

            // Embed immediately
            embedFeedback(feedback);
        }

        return repository.save(feedback);
    }

    @Transactional
    public ChatFeedback saveTeaching(TeachRequest request) {
        // Format column values teaching
        String learnedContent = String.format(
            "For %s.%s, the valid values are: %s",
            request.tableName(),
            request.columnName(),
            String.join(", ", request.validValues())
        );

        String embeddingContent = String.format(
            "Table: %s, Column: %s\n" +
            "Valid filter values: %s\n" +
            "Context: %s\n" +
            "Always use these exact values when filtering on %s.%s",
            request.tableName(),
            request.columnName(),
            String.join(", ", request.validValues()),
            request.additionalContext() != null ? request.additionalContext() : "",
            request.tableName(),
            request.columnName()
        );

        ChatFeedback feedback = ChatFeedback.builder()
            .connectionId(request.connectionId())
            .type(FeedbackType.COLUMN_VALUES)
            .learnedContent(learnedContent)
            .embeddingContent(embeddingContent)
            .createdAt(LocalDateTime.now())
            .embedded(false)
            .build();

        embedFeedback(feedback);
        return repository.save(feedback);
    }

    private void embedFeedback(ChatFeedback feedback) {
        Document doc = new Document(feedback.getEmbeddingContent(), Map.of(
            "connectionId", feedback.getConnectionId(),
            "type", feedback.getType().name(),
            "feedbackId", feedback.getId()
        ));

        vectorStore.add(List.of(doc));
        feedback.setEmbedded(true);

        log.info("Embedded feedback: {} for connection {}",
            feedback.getType(), feedback.getConnectionId());
    }

    private String formatForEmbedding(FeedbackRequest request) {
        return String.format(
            "User correction: When asked about '%s', the correct answer is: %s",
            extractQuestion(request.originalResponse()),
            request.userCorrection()
        );
    }
}
```

---

### Phase 6: New ChatService with Spring AI

#### 6.1 SpringAIChatService (replaces ChatService)

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class SpringAIChatService {

    private final ChatClient chatClient;
    private final ChatHistoryService chatHistoryService;
    private final SchemaScannerService schemaScannerService;
    private final QueryExecutorService queryExecutorService;

    public ChatResponse processMessage(String connectionId, String message,
                                       String chatId, String userId) {
        try {
            // Get or create chat session
            String actualChatId = chatId;
            if (actualChatId == null) {
                var activeChat = chatHistoryService.getOrCreateActiveChat(connectionId);
                actualChatId = activeChat.getId();
            }

            // Get schema for system prompt
            SchemaMetadata schema = schemaScannerService.scanSchema(connectionId);
            String systemPrompt = buildSystemPrompt(schema);

            // Use Spring AI ChatClient with all advisors
            String response = chatClient.prompt()
                .system(systemPrompt)
                .user(message)
                .advisors(advisor -> advisor
                    .param("connectionId", connectionId)
                    .param("chatId", actualChatId)
                    .param(ChatMemory.CONVERSATION_ID, actualChatId)
                )
                .call()
                .content();

            // Extract and execute SQL if present
            String sql = extractSqlFromResponse(response);
            QueryResult queryResult = null;

            if (sql != null && isExecutableQuery(sql)) {
                queryResult = executeAndSummarize(connectionId, sql, response);
            }

            // Save to history
            chatHistoryService.addMessage(actualChatId, MessageRole.USER, message, null);
            chatHistoryService.addMessage(actualChatId, MessageRole.ASSISTANT, response, sql);

            return ChatResponse.builder()
                .message(response)
                .success(true)
                .chatId(actualChatId)
                .sql(sql)
                .data(queryResult)
                .build();

        } catch (Exception e) {
            log.error("Error processing chat message", e);
            return ChatResponse.builder()
                .message("Error: " + e.getMessage())
                .success(false)
                .build();
        }
    }

    private String buildSystemPrompt(SchemaMetadata schema) {
        // Simplified - advisors handle most context injection
        return String.format("""
            You are an expert Database Administrator (DBA) assistant for %s.

            MANDATORY SQL RULES:
            1. ALWAYS use table-qualified column names (table.column or alias.column)
            2. Use appropriate syntax for %s database
            3. Include SQL in markdown code blocks (```sql ... ```)

            Schema: %s
            """,
            schema.getDbType().toUpperCase(),
            schema.getDbType(),
            buildSchemaContext(schema)
        );
    }
}
```

---

### Phase 7: Frontend Changes

#### 7.1 Feedback UI Component (React)

```javascript
// src/components/ChatFeedback.js
import { useState } from 'react'
import { ThumbsUp, ThumbsDown, MessageSquare } from 'lucide-react'
import { chatFeedbackAPI } from '@/lib/api/client'

export function ChatFeedback({ connectionId, chatId, messageId, response }) {
  const [showCorrection, setShowCorrection] = useState(false)
  const [correction, setCorrection] = useState('')

  const handleFeedback = async (type) => {
    await chatFeedbackAPI.submit({
      connectionId,
      chatId,
      messageId,
      type,
      originalResponse: response
    })
  }

  const handleCorrection = async () => {
    await chatFeedbackAPI.submit({
      connectionId,
      chatId,
      messageId,
      type: 'CORRECTION',
      originalResponse: response,
      userCorrection: correction
    })
    setShowCorrection(false)
    setCorrection('')
  }

  return (
    <div className="flex items-center gap-2 mt-2 text-gray-500">
      <button onClick={() => handleFeedback('THUMBS_UP')}
              className="hover:text-green-600">
        <ThumbsUp size={16} />
      </button>
      <button onClick={() => handleFeedback('THUMBS_DOWN')}
              className="hover:text-red-600">
        <ThumbsDown size={16} />
      </button>
      <button onClick={() => setShowCorrection(!showCorrection)}
              className="hover:text-blue-600">
        <MessageSquare size={16} />
      </button>

      {showCorrection && (
        <div className="mt-2">
          <textarea
            value={correction}
            onChange={(e) => setCorrection(e.target.value)}
            placeholder="Provide the correct answer..."
            className="w-full p-2 border rounded"
          />
          <button onClick={handleCorrection}
                  className="mt-1 px-3 py-1 bg-black text-white rounded">
            Submit Correction
          </button>
        </div>
      )}
    </div>
  )
}
```

#### 7.2 Teach Column Values UI

```javascript
// src/components/TeachColumnValues.js
export function TeachColumnValues({ connectionId, tableName, columnName }) {
  const [values, setValues] = useState('')
  const [context, setContext] = useState('')

  const handleTeach = async () => {
    await chatFeedbackAPI.teach({
      connectionId,
      tableName,
      columnName,
      validValues: values.split(',').map(v => v.trim()),
      additionalContext: context
    })
  }

  return (
    <div className="p-4 border rounded">
      <h3 className="font-semibold mb-2">Teach Valid Values</h3>
      <p className="text-sm text-gray-600 mb-2">
        {tableName}.{columnName}
      </p>
      <input
        value={values}
        onChange={(e) => setValues(e.target.value)}
        placeholder="value1, value2, value3"
        className="w-full p-2 border rounded mb-2"
      />
      <textarea
        value={context}
        onChange={(e) => setContext(e.target.value)}
        placeholder="Additional context (optional)"
        className="w-full p-2 border rounded mb-2"
      />
      <button onClick={handleTeach}
              className="px-4 py-2 bg-black text-white rounded">
        Teach Agent
      </button>
    </div>
  )
}
```

---

## Migration Strategy

### Step 1: Parallel Implementation (Low Risk)
- Add Spring AI dependencies
- Create new `SpringAIChatService` alongside existing `ChatService`
- Feature flag to switch between implementations

### Step 2: Test with New Features
- Deploy feedback capture
- Deploy column value collection
- Test with subset of users

### Step 3: Gradual Rollout
- Enable Spring AI for new connections
- Monitor performance and accuracy
- Migrate existing connections

### Step 4: Deprecate Old Service
- Remove old `ChatService` code
- Clean up unused Azure SDK direct usage
- Update documentation

---

## Success Metrics

| Metric | Current | Target |
|--------|---------|--------|
| Cross-session memory retention | 0% | 100% |
| Column value accuracy | Manual | Automatic |
| ChatService code lines | 1,277 | ~200 |
| User re-teaching frequency | High | Low |
| Response accuracy (user feedback) | Unknown | Track via thumbs up/down |

---

## Timeline Estimate

| Phase | Description | Effort |
|-------|-------------|--------|
| Phase 1 | Database schema | 1 day |
| Phase 2 | Spring AI config | 1 day |
| Phase 3 | Custom advisors | 2 days |
| Phase 4 | Column value collection | 1 day |
| Phase 5 | Feedback API | 1 day |
| Phase 6 | New ChatService | 2 days |
| Phase 7 | Frontend changes | 1 day |
| Testing & Migration | Parallel running | 2 days |
| **Total** | | **~11 days** |

---

## Questions for Clarification

1. **Auto-collect column values?** Should we automatically collect values during Key Column Analysis, or only when user teaches them?

2. **Feedback UI location?** Should feedback buttons appear on every message, or only on AI responses?

3. **Teaching UI location?** Should the "Teach Column Values" UI be in Brain tab, or accessible from chat?

4. **Migration approach?** Feature flag per connection, or gradual percentage rollout?
