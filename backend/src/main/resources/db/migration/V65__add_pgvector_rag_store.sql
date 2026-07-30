-- V65: Local RAG vector store using pgvector
-- Used when vector.store.type=pgvector (self-hosted deployments without Azure AI Search).
-- Gracefully degrades to TEXT storage if the pgvector extension is not installed.
--
-- To install pgvector on your PostgreSQL host:
--   Debian/Ubuntu: apt-get install postgresql-17-pgvector
--   Docker:        use pgvector/pgvector image instead of postgres
--   Homebrew:      brew install pgvector
--   Managed DBs:   AWS RDS, Supabase, Neon support pgvector natively

-- 1. Attempt to enable the pgvector extension (requires superuser or pg_extension_owner)
DO $$ BEGIN
    CREATE EXTENSION IF NOT EXISTS vector;
    RAISE NOTICE 'pgvector extension enabled';
EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'pgvector extension not available — install postgresql-XX-pgvector. '
        'Falling back to TEXT embedding column (no vector similarity search). '
        'Error: %', SQLERRM;
END $$;

-- 2. Create rag_documents table with correct embedding column type
DO $$ DECLARE
    has_vector BOOLEAN;
    has_halfvec BOOLEAN;
BEGIN
    SELECT COUNT(*) > 0 INTO has_vector
    FROM pg_extension WHERE extname = 'vector';

    SELECT COUNT(*) > 0 INTO has_halfvec
    FROM pg_type WHERE typname = 'halfvec';

    IF has_vector THEN
        -- Native vector type: full similarity search support
        EXECUTE $sql$
            CREATE TABLE IF NOT EXISTS rag_documents (
                id               VARCHAR(128)  PRIMARY KEY,
                connection_id    VARCHAR(64)   NOT NULL,
                type             VARCHAR(32)   NOT NULL,
                content          TEXT,
                natural_language TEXT,
                sql_text         TEXT,
                object_name      VARCHAR(512),
                table_name       VARCHAR(512),
                description      TEXT,
                business_terms   TEXT,
                tables_used      TEXT,
                db_type          VARCHAR(32),
                embedding        vector(3072),
                metadata         TEXT,
                created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
                successful       BOOLEAN,
                execution_time_ms BIGINT
            )
        $sql$;

        IF has_halfvec THEN
            -- 3072-dim embeddings exceed pgvector's vector-HNSW limit, so index the halfvec cast instead.
            EXECUTE $sql$
                CREATE INDEX IF NOT EXISTS idx_rag_docs_embedding
                ON rag_documents USING hnsw (((embedding)::halfvec(3072)) halfvec_cosine_ops)
                WITH (m = 16, ef_construction = 64)
            $sql$;
            RAISE NOTICE 'Created rag_documents with vector(3072) storage and halfvec-backed HNSW search';
        ELSE
            RAISE WARNING 'pgvector is installed but halfvec is unavailable; using exact vector search without ANN index';
        END IF;
    ELSE
        -- Fallback: TEXT column — keyword search only via to_tsvector
        EXECUTE $sql$
            CREATE TABLE IF NOT EXISTS rag_documents (
                id               VARCHAR(128)  PRIMARY KEY,
                connection_id    VARCHAR(64)   NOT NULL,
                type             VARCHAR(32)   NOT NULL,
                content          TEXT,
                natural_language TEXT,
                sql_text         TEXT,
                object_name      VARCHAR(512),
                table_name       VARCHAR(512),
                description      TEXT,
                business_terms   TEXT,
                tables_used      TEXT,
                db_type          VARCHAR(32),
                embedding        TEXT,
                metadata         TEXT,
                created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
                successful       BOOLEAN,
                execution_time_ms BIGINT
            )
        $sql$;

        RAISE WARNING 'Created rag_documents with TEXT embedding — install pgvector for vector search';
    END IF;
END $$;

-- 3. Shared indexes (always created regardless of pgvector availability)
CREATE INDEX IF NOT EXISTS idx_rag_docs_connection
    ON rag_documents (connection_id);

CREATE INDEX IF NOT EXISTS idx_rag_docs_type
    ON rag_documents (type);

CREATE INDEX IF NOT EXISTS idx_rag_docs_table
    ON rag_documents (table_name);

CREATE INDEX IF NOT EXISTS idx_rag_docs_connection_type
    ON rag_documents (connection_id, type);

-- 4. Full-text search index for keyword-based fallback
CREATE INDEX IF NOT EXISTS idx_rag_docs_content_fts
    ON rag_documents USING gin (
        to_tsvector('english',
            COALESCE(content, '') || ' ' ||
            COALESCE(natural_language, '') || ' ' ||
            COALESCE(description, '')
        )
    );
