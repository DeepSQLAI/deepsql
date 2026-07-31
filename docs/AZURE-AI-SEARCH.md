# Azure AI Search Integration

## Overview

The DBA Agent now supports **Azure AI Search** for enhanced RAG (Retrieval-Augmented Generation) capabilities. This provides:

- **Vector Search**: Semantic similarity using embeddings
- **Hybrid Search**: Combines vector + keyword search for best results
- **Persistent Storage**: No data loss on restart (vs in-memory cache)
- **Scalability**: Handle millions of documents
- **Semantic Ranking**: Azure's AI-powered relevance scoring
- **Advanced Filtering**: Filter by connection, type, tables, etc.

## Benefits Over In-Memory Cache

| Feature | In-Memory Cache | Azure AI Search |
|---------|----------------|-----------------|
| Persistence | Lost on restart | Persistent storage |
| Scalability | Limited by RAM | Millions of documents |
| Search Quality | Cosine similarity only | Hybrid search (vector + keyword + semantic) |
| Performance | Re-calculate scores each time | Pre-indexed, optimized queries |
| Filtering | Manual in code | Native filter support |
| Distributed | Single server | Cloud-scale distributed |

## Setup

### 1. Create Azure AI Search Resource

```bash
# Create resource group (if needed)
az group create --name dba-agent-rg --location eastus

# Create Azure AI Search service
az search service create \
  --name dba-agent-search \
  --resource-group dba-agent-rg \
  --sku basic \
  --partition-count 1 \
  --replica-count 1
```

Or use the [Azure Portal](https://portal.azure.com):
1. Search for "Azure AI Search"
2. Click "Create"
3. Choose resource group, name, and region
4. Select pricing tier (Basic for dev, Standard+ for production)

### 2. Get Connection Details

```bash
# Get admin API key
az search admin-key show \
  --service-name dba-agent-search \
  --resource-group dba-agent-rg
```

Or from Azure Portal:
1. Go to your Search service
2. Click "Keys" in left menu
3. Copy the **Admin Key**
4. Note your **Search Endpoint** (format: `https://<name>.search.windows.net`)

### 3. Configure Application

Update `application.properties` or set environment variables:

```properties
# Enable Azure AI Search
azure.search.enabled=true

# Connection details
azure.search.endpoint=https://<your-search-resource>.search.windows.net
azure.search.api-key=YOUR_ADMIN_API_KEY_HERE
azure.search.index-name=dba-agent-training-data
```

Or using environment variables:

```bash
export AZURE_SEARCH_ENABLED=true
export AZURE_SEARCH_ENDPOINT=https://<your-search-resource>.search.windows.net
export AZURE_SEARCH_API_KEY=your-admin-key
```

### 4. Start the Application

The search index will be automatically created on startup with:
- Vector search configuration (3072 dimensions for text-embedding-3-large)
- HNSW algorithm for fast approximate nearest neighbor search
- Hybrid search capabilities
- Semantic ranking enabled

## How It Works

### Indexing Flow

```
User Query → ChatService → TrainingService
                              ↓
                        EmbeddingService (Azure OpenAI)
                              ↓
                        Create Embedding (3072-dim vector)
                              ↓
                 ┌───────────────────────┐
                 │  Azure AI Search      │
                 │  ✓ Store document     │
                 │  ✓ Index vector       │
                 │  ✓ Index keywords     │
                 └───────────────────────┘
```

### Retrieval Flow (Hybrid Search)

```
User Question → Create Embedding
                     ↓
      ┌──────────────────────────────┐
      │   Azure AI Search            │
      │   Hybrid Search:             │
      │   1. Vector similarity       │
      │   2. Keyword matching        │
      │   3. Semantic ranking        │
      └──────────────────────────────┘
                     ↓
        Top-K Most Relevant Results
                     ↓
            ChatService (Context)
```

## Usage Examples

### 1. Training with Schema (Auto-indexed)

```java
// Automatically indexes to Azure Search when enabled
trainingService.trainWithSchema(connectionId);
```

This:
- Scans database schema
- Creates embeddings for each table
- Indexes to Azure Search with:
  - Full DDL as searchable text
  - Vector embedding
  - Metadata (table name, column count)
  - Filterable by connectionId

### 2. Training with Query Examples

```java
trainingService.trainWithQueryExample(
    connectionId,
    "Show me all active users",
    "SELECT * FROM users WHERE active = 1",
    queryResult,
    userId
);
```

Indexed with:
- Natural language question (searchable)
- SQL query (searchable, optimized analyzer)
- Execution metrics
- Tables used (filterable)

### 3. Retrieving Relevant Context (Hybrid Search)

```java
List<TrainingDataEmbedding> relevant =
    trainingService.retrieveRelevant(
        connectionId,
        "How many orders were placed last month?",
        topK: 5
    );
```

Azure Search returns:
- **Vector matches**: Similar questions/schemas
- **Keyword matches**: Exact term matches (e.g., "orders", "month")
- **Semantic ranking**: AI-powered relevance scoring
- **Combined**: Best of all three methods

## Advanced Features

### Vector-Only Search

For pure semantic similarity without keyword matching:

```java
List<TrainingDataSearchDocument> results =
    azureSearchService.vectorSearch(
        connectionId,
        queryVector,
        topK: 10,
        typeFilter: "QUERY_EXAMPLE" // Optional
    );
```

### Filtered Search

```java
// Only search documentation for specific tables
String filter = "connectionId eq 'conn-123' and type eq 'DOCUMENTATION' and tablesUsed eq 'users'";
```

### Statistics

```java
Map<String, Long> stats = azureSearchService.getConnectionStats(connectionId);
// Returns: { "schema_ddl": 50, "query_example": 120, "documentation": 30 }
```

## Pricing

Azure AI Search pricing depends on tier:

| Tier | Storage | Price/Month (approx) | Best For |
|------|---------|---------------------|----------|
| Free | 50 MB | $0 | Development/Testing |
| Basic | 2 GB | $75 | Small production |
| Standard S1 | 25 GB | $250 | Production |
| Standard S2 | 100 GB | $1,000 | Large scale |

**Note**: Vector search available on Basic tier and above.

[Azure AI Search Pricing](https://azure.microsoft.com/en-us/pricing/details/search/)

## Monitoring

View index statistics in Azure Portal:
1. Go to your Search service
2. Click "Indexes"
3. Select "dba-agent-training-data"
4. View document count, storage size, query stats

## Fallback Behavior

If Azure Search is disabled or unavailable:
- ✅ Automatically falls back to in-memory cache
- ✅ No functionality loss
- ⚠️ Performance degradation for large datasets
- ⚠️ Data lost on restart

## Migration

### From In-Memory to Azure Search

1. Enable Azure Search in config
2. Restart application (index created automatically)
3. Re-train existing connections:

```bash
# Call training endpoints for each connection
curl -X POST http://localhost:8080/api/training/schema/{connectionId}
```

### From Azure Search to In-Memory

1. Set `azure.search.enabled=false`
2. Restart application
3. Data automatically loaded from database on first query

## Troubleshooting

### Index Creation Fails

**Error**: "Index creation failed"

**Solution**: Check admin API key permissions

```bash
# Verify key works
curl -X GET "https://<service>.search.windows.net/indexes?api-version=2023-11-01" \
     -H "api-key: YOUR_KEY"
```

### Search Returns No Results

**Possible causes**:
1. No data indexed yet → Train with schema/examples
2. Wrong connectionId filter → Check logs
3. Vector dimensions mismatch → Verify embedding model (3072 for text-embedding-3-large)

### High Latency

**Solutions**:
- Increase replica count for read throughput
- Use Standard tier for better performance
- Reduce `topK` parameter
- Add more specific filters

## Best Practices

1. **Index Regularly**: Re-index when schema changes
2. **Use Filters**: Always filter by connectionId for multi-tenant scenarios
3. **Batch Operations**: Use `indexDocuments()` for bulk indexing
4. **Monitor Costs**: Set up Azure cost alerts
5. **Semantic Search**: Enable for best relevance
6. **Hybrid Search**: Use for highest accuracy
7. **Vector-Only**: Use when keywords don't matter

## Resources

- [Azure AI Search Documentation](https://learn.microsoft.com/en-us/azure/search/)
- [Vector Search Overview](https://learn.microsoft.com/en-us/azure/search/vector-search-overview)
- [Hybrid Search](https://learn.microsoft.com/en-us/azure/search/hybrid-search-overview)
- [Java SDK Reference](https://learn.microsoft.com/en-us/java/api/overview/azure/search-documents-readme)
