# **DBA Agent Brain - Extended Design**

## **1\. Purpose**

The **Brain** is the intelligence layer of the DBA Agent. Its role is to build a deep, evolving understanding of the database by jointly reasoning over:

- **Schema structure**
- **Data characteristics**
- **Query workload**
- **Explicit human knowledge (rules / hints)**

The Brain produces an explainable health score, a semantic ER model, schema classification (Star, Snowflake, etc.), and highly accurate optimization recommendations.

## **2\. Core Principles**

- **Explainable by default** - no black-box decisions
- **Workload-aware** - real usage beats theoretical best practices
- **Human-in-the-loop** - accepts non-obvious domain rules
- **Safe & incremental** - recommendations before automation
- **Continuously learning** - schema + workload drift over time

## **3\. Inputs**

### **3.1 Schema Metadata**

Collected from system catalogs:

- Tables, columns, data types
- Primary keys, foreign keys (explicit)
- Indexes (type, order, cardinality)
- Constraints
- Partitioning
- Row counts and table sizes

### **3.2 Data Statistics (Sampled)**

- Column cardinality
- NULL ratio
- Data skew (top-N values)
- Histograms (if available)
- Growth rate over time

### **3.3 Query Workload**

Normalized from slow logs / stats:

- Query fingerprints
- Frequency
- Avg / P95 latency
- Rows examined vs returned
- Execution plan features (scan type, temp tables, filesort)

### **3.4 User-Defined Rules & Hints**

Declarative inputs supplied by users to encode domain knowledge that is not inferable from metadata.

Examples:

- "This table is an immutable fact table"
- "This column is a soft foreign key"
- "These tables must always be joined"
- "Do not recommend indexes on this table"

Rules are versioned, scoped, auditable, and treated as high-confidence signals.

## **4\. Internal Architecture**

Schema Extractor

↓

Schema Graph Builder

↓

Statistics Profiler

↓

Workload Analyzer

↓

Rule Engine

↓

Heuristics Engine

↓

Scoring Engine

↓

Outputs & Recommendations

## **5\. Schema Graph & ER Model**

### **5.1 Graph Construction**

- Nodes = tables
- Edges = relationships (FKs or **inferred joins**)
- Edge metadata: direction, cardinality, confidence

### **5.2 ER Diagram Generation**

The Brain generates ER diagrams that:

- Label **fact**, **dimension**, **bridge**, and **lookup** tables
- Show cardinality (1:1, 1:N, N:M)
- Distinguish explicit vs inferred foreign keys
- Annotate join frequency from workload

The ER diagram is a **semantic artifact**, not just a visualization.

## **6\. Schema Classification**

### **6.1 Global Classification**

Brain classifies the overall schema as:

- Star
- Snowflake
- Hybrid (Star + Snowflake)
- OLTP-style normalized
- Reporting / data-mart
- Anti-pattern (cycles, unclear ownership)

### **6.2 Sub-Graph Classification**

Within the same database, Brain identifies:

- Individual star clusters
- Snowflaked dimensions
- Orphaned tables
- Reporting-only aggregates

Each classification is backed by explainable structural signals (fan-out, depth, cardinality).

## **7\. Column & Key Intelligence**

For each column, Brain tracks:

- Cardinality & selectivity
- Skew
- NULL ratio
- Join frequency
- Filter frequency
- Group-by / order-by usage

This allows Brain to infer:

- True keys vs accidental keys
- Misused low-cardinality columns
- High-value index candidates
- Partitioning candidates

## **8\. Query Quality Analysis**

### **8.1 Query Fingerprinting**

Queries are normalized to structural fingerprints to aggregate behavior.

### **8.2 Anti-Pattern Detection**

Examples:

- Functions on filter columns (e.g. STR_TO_DATE)
- SELECT \* in analytical queries
- DISTINCT masking join errors
- JOINs without predicates
- High rows-examined / rows-returned ratio

Query impact is weighted by frequency and latency.

## **9\. Index & Access Path Reasoning**

Brain correlates:

- Access patterns from workload
- Existing indexes
- Column cardinalities

It detects:

- Missing indexes
- Incorrect composite index ordering
- Over-indexing
- Unused or redundant indexes

Recommendations are justified with workload evidence.

## **10\. Scalability & Growth Reasoning**

Brain simulates future states (2×, 5×, 10× data growth) using:

- Current execution plans
- Index depth
- Partition pruning effectiveness

It flags designs that will fail catastrophically under growth.

## **11\. Scoring Model**

Brain produces the following scores (0-100):

| **Score** | **Meaning** |
| --- | --- |
| Schema Design Score | Structural soundness |
| --- | --- |
| Query Quality Score | Efficiency & correctness |
| --- | --- |
| Index & Access Score | Workload alignment |
| --- | --- |
| Scalability Score | Future resilience |
| --- | --- |

**Brain Score** = Weighted aggregate of the above.

Scores are explainable, traceable, and trendable over time.

## **12\. Outputs**

### **12.1 Brain Report**

- Brain Score + sub-scores
- Schema classification summary
- Key risks & anti-patterns
- Prioritized recommendations

### **12.2 ER Diagram**

- Renderable graph format
- Annotated with semantics and workload signals

### **12.3 Programmatic APIs**

- JSON outputs for automation
- Artifacts consumable by dashboards and agents

## **13\. Evolution Over Time**

Brain continuously tracks:

- Schema drift
- Query debt
- Index debt
- Score trends

This enables alerts like:

- "Schema health regressed by 12%"
- "New workload pattern invalidates existing indexes"

## **14\. Strategic Value**

The Brain is not a monitoring feature-it is a **semantic model of the data layer**.

It enables:

- Autonomous DBA actions
- Accurate infra scaling decisions
- Developer-friendly data intelligence
- A strong product moat through explainable reasoning
