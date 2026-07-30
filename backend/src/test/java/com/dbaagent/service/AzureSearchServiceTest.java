package com.dbaagent.service;

import com.dbaagent.model.QualifiedTableName;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.provider.api.DatabaseDialect;
import com.dbaagent.provider.api.IntrospectionProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AzureSearchServiceTest {

    @Mock
    private DatabaseProviderRegistry databaseProviderRegistry;

    @Mock
    private DatabaseDialect postgresDialect;

    @Mock
    private DatabaseDialect mysqlDialect;

    @Mock
    private IntrospectionProvider postgresIntrospection;

    @Mock
    private IntrospectionProvider mysqlIntrospection;

    private AzureSearchService service;

    @BeforeEach
    void setUp() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        service = new AzureSearchService(meterRegistry, databaseProviderRegistry);
    }

    @Nested
    class ResolveTableNameTests {

        @BeforeEach
        void setUpDialects() {
            lenient().when(databaseProviderRegistry.getDialect("postgresql")).thenReturn(postgresDialect);
            lenient().when(postgresDialect.introspection()).thenReturn(postgresIntrospection);
            lenient().when(postgresIntrospection.getDefaultSchema()).thenReturn("public");

            lenient().when(databaseProviderRegistry.getDialect("mysql")).thenReturn(mysqlDialect);
            lenient().when(mysqlDialect.introspection()).thenReturn(mysqlIntrospection);
            lenient().when(mysqlIntrospection.getDefaultSchema()).thenReturn(null);
        }

        @Test
        void bareName_returnsAsIs() {
            assertThat(service.resolveTableName("orders", null, "SCHEMA_DDL", "postgresql"))
                    .isEqualTo("orders");
        }

        @Test
        void pgDefaultSchema_strippedToBare() {
            assertThat(service.resolveTableName("public.orders", null, "SCHEMA_DDL", "postgresql"))
                    .isEqualTo("orders");
        }

        @Test
        void pgNonDefaultSchema_preserved() {
            assertThat(service.resolveTableName("sales.orders", null, "SCHEMA_DDL", "postgresql"))
                    .isEqualTo("sales.orders");
        }

        @Test
        void mysql_alwaysStrippedToBare() {
            assertThat(service.resolveTableName("mydb.orders", null, "SCHEMA_DDL", "mysql"))
                    .isEqualTo("orders");
        }

        @Test
        void mysql_bareNamePassesThrough() {
            assertThat(service.resolveTableName("orders", null, "COLUMN_VALUES", "mysql"))
                    .isEqualTo("orders");
        }

        @Test
        void columnType_usesParentObject() {
            assertThat(service.resolveTableName("orders.customer_id", "orders", "COLUMN", "postgresql"))
                    .isEqualTo("orders");
        }

        @Test
        void columnType_qualifiedParent() {
            assertThat(service.resolveTableName("sales.orders.id", "sales.orders", "COLUMN", "postgresql"))
                    .isEqualTo("sales.orders");
        }

        @Test
        void nullObjectName_returnsNull() {
            assertThat(service.resolveTableName(null, null, "SCHEMA_DDL", "postgresql"))
                    .isNull();
        }

        @Test
        void queryExample_nullTableName() {
            // QUERY_EXAMPLE docs should have tableName=null (excluded from Stage 2)
            // Caller sets tableName=null directly, but if objectName is null:
            assertThat(service.resolveTableName(null, null, "QUERY_EXAMPLE", "postgresql"))
                    .isNull();
        }

        @Test
        void pgCaseInsensitiveDefaultSchema() {
            assertThat(service.resolveTableName("PUBLIC.orders", null, "SCHEMA_DDL", "postgresql"))
                    .isEqualTo("orders");
        }
    }

    @Nested
    class MigrationPhaseTests {

        @Test
        void defaultPhase_isV1Only() {
            assertThat(service.getMigrationPhase())
                    .isEqualTo(AzureSearchService.MigrationPhase.V1_ONLY);
        }

        @Test
        void stage2Disabled_whenV1Only() {
            assertThat(service.isStage2Enabled()).isFalse();
        }
    }

    @Nested
    class QualifiedTableNameTests {

        @Test
        void bareEquality() {
            var a = new QualifiedTableName("orders", null);
            var b = new QualifiedTableName("orders", null);
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        void qualifiedEquality() {
            var a = new QualifiedTableName("orders", "sales.orders");
            var b = new QualifiedTableName("orders", "sales.orders");
            assertThat(a).isEqualTo(b);
        }

        @Test
        void bareVsQualified_notEqual() {
            var bare = new QualifiedTableName("orders", null);
            var qualified = new QualifiedTableName("orders", "sales.orders");
            assertThat(bare).isNotEqualTo(qualified);
        }

        @Test
        void differentSchemas_notEqual() {
            var sales = new QualifiedTableName("orders", "sales.orders");
            var billing = new QualifiedTableName("orders", "billing.orders");
            assertThat(sales).isNotEqualTo(billing);
        }

        @Test
        void caseInsensitiveEquality() {
            var lower = new QualifiedTableName("orders", "sales.orders");
            var upper = new QualifiedTableName("ORDERS", "Sales.Orders");
            assertThat(lower).isEqualTo(upper);
            assertThat(lower.hashCode()).isEqualTo(upper.hashCode());
        }

        @Test
        void storedForm_bare() {
            assertThat(new QualifiedTableName("orders", null).storedForm())
                    .isEqualTo("orders");
        }

        @Test
        void storedForm_qualified() {
            assertThat(new QualifiedTableName("orders", "sales.orders").storedForm())
                    .isEqualTo("sales.orders");
        }
    }

    @Nested
    class TableFilterPredicateTests {

        @Test
        void bareTable_singlePredicate() {
            var tables = new java.util.LinkedHashSet<QualifiedTableName>();
            tables.add(new QualifiedTableName("orders", null));

            String filter = service.buildTableFilterPredicate(tables);

            assertThat(filter).isEqualTo("tableName eq 'orders'");
        }

        @Test
        void qualifiedUnambiguous_addsBareNameFallback() {
            var tables = new java.util.LinkedHashSet<QualifiedTableName>();
            tables.add(new QualifiedTableName("orders", "sales.orders"));

            String filter = service.buildTableFilterPredicate(tables);

            // Should include both qualified and bare fallback
            assertThat(filter).isEqualTo(
                    "tableName eq 'sales.orders' or tableName eq 'orders'");
        }

        @Test
        void ambiguousBareNames_noBareNameFallback() {
            var tables = new java.util.LinkedHashSet<QualifiedTableName>();
            tables.add(new QualifiedTableName("orders", "sales.orders"));
            tables.add(new QualifiedTableName("orders", "billing.orders"));

            String filter = service.buildTableFilterPredicate(tables);

            // No bare fallback — "orders" is ambiguous (appears in 2 schemas)
            assertThat(filter).isEqualTo(
                    "tableName eq 'sales.orders' or tableName eq 'billing.orders'");
            assertThat(filter).doesNotContain("tableName eq 'orders'");
        }

        @Test
        void mixedAmbiguousAndUnambiguous() {
            var tables = new java.util.LinkedHashSet<QualifiedTableName>();
            tables.add(new QualifiedTableName("orders", "sales.orders"));
            tables.add(new QualifiedTableName("orders", "billing.orders"));
            tables.add(new QualifiedTableName("customers", "sales.customers"));

            String filter = service.buildTableFilterPredicate(tables);

            // orders is ambiguous → no bare fallback; customers is unambiguous → gets fallback
            assertThat(filter).contains("tableName eq 'sales.orders'");
            assertThat(filter).contains("tableName eq 'billing.orders'");
            assertThat(filter).contains("tableName eq 'sales.customers'");
            assertThat(filter).contains("tableName eq 'customers'");
            // bare "orders" must NOT appear
            assertThat(filter).doesNotContain("tableName eq 'orders'");
        }

        @Test
        void bareTableDoesNotGetDuplicateFallback() {
            // A bare table (schemaQualified=null) should produce only one predicate,
            // not a redundant bare fallback
            var tables = new java.util.LinkedHashSet<QualifiedTableName>();
            tables.add(new QualifiedTableName("orders", null));
            tables.add(new QualifiedTableName("customers", null));

            String filter = service.buildTableFilterPredicate(tables);

            assertThat(filter).isEqualTo(
                    "tableName eq 'orders' or tableName eq 'customers'");
        }

        @Test
        void specialCharsEscaped() {
            var tables = new java.util.LinkedHashSet<QualifiedTableName>();
            tables.add(new QualifiedTableName("O'Brien", "hr.O'Brien"));

            String filter = service.buildTableFilterPredicate(tables);

            assertThat(filter).contains("tableName eq 'hr.O''Brien'");
            assertThat(filter).contains("tableName eq 'O''Brien'");
        }
    }

    @Nested
    class EscapeODataTests {

        @Test
        void noSpecialChars() {
            assertThat(AzureSearchService.escapeOData("orders")).isEqualTo("orders");
        }

        @Test
        void singleQuoteEscaped() {
            assertThat(AzureSearchService.escapeOData("O'Brien")).isEqualTo("O''Brien");
        }

        @Test
        void multipleSingleQuotes() {
            assertThat(AzureSearchService.escapeOData("it's a test's")).isEqualTo("it''s a test''s");
        }

        @Test
        void nullReturnsEmpty() {
            assertThat(AzureSearchService.escapeOData(null)).isEmpty();
        }
    }
}
