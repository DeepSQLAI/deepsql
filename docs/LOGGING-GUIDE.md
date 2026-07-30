# TrainingService Logging Guide

## Overview

The TrainingService now provides detailed, structured logging to help you understand:
- **Data Source**: Azure AI Search vs In-Memory Cache
- **Performance**: Timing for each operation
- **Results**: What data is being retrieved and used
- **RAG Process**: Complete visibility into retrieval-augmented generation

## Log Formats

### 1. RAG Retrieval (Azure AI Search Enabled)

```
╔════════════════════════════════════════════════════════════
║ RAG RETRIEVAL - Starting
║ Connection: eec2fd58-df58-4c49-ad08-c458bdb9dc29
║ Question: Show me all active users from last month
║ Top-K: 5
║ Data Source: Azure AI Search (Hybrid)
╚════════════════════════════════════════════════════════════
→ Embedding created: 234ms (3072 dimensions)
→ Using Azure AI Search - Hybrid Search Mode
  ├─ Vector Search: Enabled (HNSW algorithm)
  ├─ Keyword Search: Enabled (BM25 ranking)
  └─ Filtering: connectionId = 'eec2fd58-df58-4c49-ad08-c458bdb9dc29'
→ Azure Search completed: 45ms
→ Results retrieved: 3 documents
→ Top Results:
  1. [QUERY_EXAMPLE] Show me active customers
SELECT * FROM users WHERE active = 1...
  2. [QUERY_EXAMPLE] Get all users from previous month
SELECT * FROM users WHERE created_at...
  3. [SCHEMA_DDL] Table: users
Columns:
  - id (bigint) PRIMARY KEY...
╔════════════════════════════════════════════════════════════
║ RAG RETRIEVAL - Completed (Azure AI Search)
║ Total Time: 285ms
║ Results: 3 relevant documents
║ Performance: 234ms embedding + 45ms search
╚════════════════════════════════════════════════════════════
```

### 2. RAG Retrieval (In-Memory Cache)

```
╔════════════════════════════════════════════════════════════
║ RAG RETRIEVAL - Starting
║ Connection: eec2fd58-df58-4c49-ad08-c458bdb9dc29
║ Question: List all products
║ Top-K: 5
║ Data Source: In-Memory Cache
╚════════════════════════════════════════════════════════════
→ Embedding created: 189ms (3072 dimensions)
→ Using In-Memory Cache (Azure Search disabled)
  └─ Using cached data: 47 documents
→ Calculating cosine similarity for 47 documents...
  └─ Similarity calculation: 12ms
→ Top 5 Results by Similarity:
  1. [QUERY_EXAMPLE] Score: 0.8923 - Show all products in inventory
SELECT * FROM products WHERE...
  2. [QUERY_EXAMPLE] Score: 0.8567 - Get product list
SELECT id, name FROM products...
  3. [SCHEMA_DDL] Score: 0.7834 - Table: products
Columns:
  - id (int) PRIMARY KEY...
  4. [DOCUMENTATION] Score: 0.7512 - products table contains all inventory items...
  5. [QUERY_EXAMPLE] Score: 0.7201 - Display available products
SELECT * FROM products WHERE available = true...
╔════════════════════════════════════════════════════════════
║ RAG RETRIEVAL - Completed (In-Memory Cache)
║ Total Time: 208ms
║ Results: 5 relevant documents
║ Performance: 189ms embedding + 12ms similarity
╚════════════════════════════════════════════════════════════
```

### 3. Training - Query Example (Azure Search)

```
┌─────────────────────────────────────────────────────────
│ TRAINING - Query Example
│ Question: Show me all active users
│ SQL: SELECT * FROM users WHERE active = 1
│ Storage: Azure AI Search
└─────────────────────────────────────────────────────────
  ✓ Saved to database: 244c5d11-513c-405c-bd1f-5ec93d2cac56
  ✓ Embedding created: 198ms
  ✓ Indexed to Azure AI Search
  └─ Searchable via: Vector similarity + Keyword matching
✓ Query example training completed: 244c5d11-513c-405c-bd1f-5ec93d2cac56
```

### 4. Training - Query Example (In-Memory)

```
┌─────────────────────────────────────────────────────────
│ TRAINING - Query Example
│ Question: Get customer orders
│ SQL: SELECT * FROM orders WHERE customer_id = ?
│ Storage: Database + Cache
└─────────────────────────────────────────────────────────
  ✓ Saved to database: 8a7f2c3d-9e4b-4a1c-8d2f-3b5c6e7a8f9d
  ✓ Embedding created: 203ms
  ✓ Cached in memory
✓ Query example training completed: 8a7f2c3d-9e4b-4a1c-8d2f-3b5c6e7a8f9d
```

### 5. First Query (Empty Index)

```
╔════════════════════════════════════════════════════════════
║ RAG RETRIEVAL - Starting
║ Connection: eec2fd58-df58-4c49-ad08-c458bdb9dc29
║ Question: Show me sales data
║ Top-K: 5
║ Data Source: Azure AI Search (Hybrid)
╚════════════════════════════════════════════════════════════
→ Embedding created: 221ms (3072 dimensions)
→ Using Azure AI Search - Hybrid Search Mode
  ├─ Vector Search: Enabled (HNSW algorithm)
  ├─ Keyword Search: Enabled (BM25 ranking)
  └─ Filtering: connectionId = 'eec2fd58-df58-4c49-ad08-c458bdb9dc29'
→ Azure Search completed: 38ms
→ Results retrieved: 0 documents
⚠ No matching documents found in Azure Search
  This is normal for:
  - First queries (index is empty)
  - Very unique questions (no similar examples yet)
╔════════════════════════════════════════════════════════════
║ RAG RETRIEVAL - Completed (Azure AI Search)
║ Total Time: 264ms
║ Results: 0 relevant documents
║ Performance: 221ms embedding + 38ms search
╚════════════════════════════════════════════════════════════
```

### 6. Error Scenario

```
╔════════════════════════════════════════════════════════════
║ RAG RETRIEVAL - FAILED
║ Error: Connection timeout to Azure Search
╚════════════════════════════════════════════════════════════
com.azure.core.exception.ServiceException: Connection timeout
    at com.azure.search.documents.SearchClient...
```

## Log Levels

### INFO Level (Default)
Shows the complete RAG process:
- Query details
- Data source (Azure Search vs Cache)
- Performance metrics
- Retrieved results
- Success/failure status

### DEBUG Level
Additional details:
- Raw embeddings
- All similarity scores
- Cache operations
- Database queries

### ERROR Level
Only errors and failures:
- Connection issues
- Indexing failures
- Embedding errors

## Configuration

Set log level in `application.properties`:

```properties
# Show detailed RAG logs
logging.level.com.dbaagent.service.TrainingService=INFO

# Debug level for troubleshooting
logging.level.com.dbaagent.service.TrainingService=DEBUG

# Errors only
logging.level.com.dbaagent.service.TrainingService=ERROR
```

## Understanding the Logs

### Data Source Indicators

**Azure AI Search (Hybrid)**
- Vector Search with HNSW algorithm
- Keyword Search with BM25 ranking
- Combined scoring for best results

**In-Memory Cache**
- Cosine similarity calculation
- All documents scored in-memory
- Sorted by similarity score

### Performance Metrics

**Good Performance:**
- Embedding: 150-300ms (depends on Azure OpenAI latency)
- Azure Search: 20-100ms (depends on index size)
- In-Memory: 5-50ms (depends on cache size)

**Slow Performance:**
- Embedding: >500ms → Check Azure OpenAI connection
- Azure Search: >200ms → Check index size or network
- In-Memory: >100ms → Too many cached documents

### Result Quality Indicators

**High Quality Match:**
- Score > 0.85 (in-memory)
- Top result is a QUERY_EXAMPLE with similar question

**Medium Quality Match:**
- Score 0.70-0.85
- Results include SCHEMA_DDL or DOCUMENTATION

**Low Quality Match:**
- Score < 0.70
- Index might need more training data

## Monitoring Tips

### Watch for These Patterns

**1. Index Building Up:**
```
Query 1: 0 results
Query 2: 0 results
Query 3: 1 result
Query 4: 2 results
...
```
✅ Normal - index is accumulating data

**2. Consistent Results:**
```
All queries: 3-5 results with scores > 0.80
```
✅ Good - well-trained index

**3. Always Empty:**
```
All queries: 0 results
```
⚠ Issue - check if Azure Search is enabled and indexing is working

**4. Slow Search:**
```
Azure Search completed: 500ms+
```
⚠ Issue - check network or index configuration

## Grep Patterns for Troubleshooting

**See all RAG retrievals:**
```bash
grep "RAG RETRIEVAL" dba-agent.log
```

**See training operations:**
```bash
grep "TRAINING" dba-agent.log
```

**See Azure Search usage:**
```bash
grep "Azure AI Search" dba-agent.log
```

**See performance issues:**
```bash
grep "Total Time" dba-agent.log | awk '{print $(NF-1)}'
```

**See empty results:**
```bash
grep "Results: 0" dba-agent.log
```

## Log Rotation

Logs can grow large. Use log rotation:

```bash
# logrotate config
/path/to/dba-agent.log {
    daily
    rotate 7
    compress
    missingok
    notifempty
    create 0644 user group
}
```

## Example Complete Flow

```
# User asks question
2025-12-28 16:00:03 [ChatService] Processing question: Show me active users

# RAG retrieval starts
2025-12-28 16:00:03 ╔═══ RAG RETRIEVAL - Starting
2025-12-28 16:00:03 ║ Question: Show me active users
2025-12-28 16:00:03 ║ Data Source: Azure AI Search (Hybrid)
2025-12-28 16:00:03 ╚════════════════════════════════

# Embedding created
2025-12-28 16:00:03 → Embedding created: 234ms

# Search executed
2025-12-28 16:00:04 → Using Azure AI Search - Hybrid Search Mode
2025-12-28 16:00:04 → Azure Search completed: 45ms
2025-12-28 16:00:04 → Results retrieved: 2 documents

# Results shown
2025-12-28 16:00:04 → Top Results:
2025-12-28 16:00:04   1. [QUERY_EXAMPLE] Show active customers...
2025-12-28 16:00:04   2. [SCHEMA_DDL] Table: users...

# RAG complete
2025-12-28 16:00:04 ╔═══ RAG RETRIEVAL - Completed
2025-12-28 16:00:04 ║ Total Time: 285ms
2025-12-28 16:00:04 ║ Results: 2 relevant documents
2025-12-28 16:00:04 ╚════════════════════════════════

# SQL generated using context
2025-12-28 16:00:05 [ChatService] SQL Generated: SELECT * FROM users WHERE active = 1

# Query executed
2025-12-28 16:00:05 [ChatService] Query executed: 23 rows, 8ms

# Result stored for future training
2025-12-28 16:00:06 ┌─ TRAINING - Query Example
2025-12-28 16:00:06 │ Question: Show me active users
2025-12-28 16:00:06 │ Storage: Azure AI Search
2025-12-28 16:00:06 └────────────────────────────
2025-12-28 16:00:06   ✓ Indexed to Azure AI Search
2025-12-28 16:00:06 ✓ Query example training completed
```

This complete flow shows the entire RAG lifecycle from question to answer to training!
