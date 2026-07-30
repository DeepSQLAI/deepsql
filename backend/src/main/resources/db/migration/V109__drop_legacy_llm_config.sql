-- Drops the pre-BYO-LLM configuration keys, superseded by the provider-namespaced
-- scheme llm.<role>.<provider>.<field> that LlmConfigResolver reads.
--
-- ─────────────────────────────────────────────────────────────────────────────
-- READ THIS BEFORE ASSUMING IT RAN: this repository has no Flyway.
-- ─────────────────────────────────────────────────────────────────────────────
-- backend/pom.xml declares no flyway-core dependency and `mvn dependency:list`
-- finds no org.flywaydb artifact, so nothing under db/migration/ is executed at
-- startup. Schema is managed by Hibernate `spring.jpa.hibernate.ddl-auto=update`
-- (see application.properties and the comments in SchemaColumnCompatibilityInitializer,
-- BrainInitSchemaCompatibilityInitializer and PgVectorRagStoreInitializer). The
-- directory is a hand-maintained changelog: it even carries three duplicate version
-- numbers (V31, V63, V103) that a real Flyway runtime would refuse to start on.
--
-- Apply this by hand against the vault DB if you ever ran the pre-BYO onboarding
-- wizard. Note that DB_URL is a *JDBC* URL (jdbc:postgresql://host:port/db), which psql
-- cannot parse — pass the parts instead:
--
--     psql -h localhost -p 5432 -U postgres -d dba_agent \
--          -f backend/src/main/resources/db/migration/V109__drop_legacy_llm_config.sql
--
-- For the docker-compose stack, run it inside the postgres container:
--
--     docker compose exec -T postgres psql -U postgres -d dba_agent \
--       < backend/src/main/resources/db/migration/V109__drop_legacy_llm_config.sql
--
-- ─────────────────────────────────────────────────────────────────────────────
-- Why deleting these rows is safe
-- ─────────────────────────────────────────────────────────────────────────────
-- Every key below is exact-matched. None of them is a key LlmConfigResolver reads:
-- it reads `llm.<role>.provider` (llm.chat.provider, llm.embedding.provider) and
-- `llm.<role>.<providerId>.<field>` (e.g. llm.chat.openai.api-key). No key in this
-- list can collide with that namespace, so this cannot delete a configuration that
-- is currently in use — including one an operator hand-inserted to reach the DB tier.
--
-- The keys below are written and read only by SetupController's /setup/llm-config
-- endpoints, which target the flat pre-BYO namespace. Nothing in the resolution path
-- consumes them. Leaving them is worse than deleting them: an operator editing them
-- through the wizard sees neither an effect nor an error.
--
-- SetupController has since been reconciled with the resolver: /setup/llm-config now
-- writes llm.chat.provider / llm.chat.<providerId>.<field> (and the llm.embedding.*
-- twins), so the wizard no longer recreates the rows below.
--
-- DELETE with an exact-match WHERE, not TRUNCATE: system_config also holds SMTP,
-- telemetry and setup state that must survive. Re-running is a no-op.

DELETE FROM system_config
 WHERE key IN (
    'llm.provider',
    'llm.openai.api-key',
    'llm.openai.endpoint',
    'llm.chat-model',
    'llm.embedding-model',
    'llm.chat-temperature',
    'llm.use-responses-api'
 );
