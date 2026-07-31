package com.dbaagent.service;

import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.CompanyKnowledgeEntry;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.model.TrainingDataEmbedding;
import com.dbaagent.repository.CompanyKnowledgeEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyKnowledgeServiceTest {

    @Mock private CompanyKnowledgeEntryRepository companyKnowledgeEntryRepository;
    @Mock private TrainingService trainingService;
    @Mock private SchemaScannerService schemaScannerService;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;

    private CompanyKnowledgeService companyKnowledgeService;

    @BeforeEach
    void setUp() {
        companyKnowledgeService = new CompanyKnowledgeService(
            companyKnowledgeEntryRepository,
            trainingService,
            schemaScannerService,
            eventPublisher
        );
        // Tunables — @Value defaults aren't injected in plain unit tests, so set them explicitly.
        ReflectionTestUtils.setField(companyKnowledgeService, "charBudget", 12000);
        ReflectionTestUtils.setField(companyKnowledgeService, "maxEntries", 12);
        ReflectionTestUtils.setField(companyKnowledgeService, "scoreFloor", 0.30);
        ReflectionTestUtils.setField(companyKnowledgeService, "linkBonusTable", 1.20);
        ReflectionTestUtils.setField(companyKnowledgeService, "linkBonusColumn", 0.90);
        ReflectionTestUtils.setField(companyKnowledgeService, "coverageBonusComplete", 0.24);
        ReflectionTestUtils.setField(companyKnowledgeService, "coverageBonusPartial", 0.12);
        ReflectionTestUtils.setField(companyKnowledgeService, "businessRuleBonus", 0.18);
        ReflectionTestUtils.setField(companyKnowledgeService, "linkFloorBaseScore", 0.50);
    }

    private static TrainingDataEmbedding ckHit(String entryId, double score) {
        return TrainingDataEmbedding.builder()
            .id(entryId)
            .type(TrainingDataEmbedding.TrainingDataType.COMPANY_KNOWLEDGE)
            .score(score)
            .build();
    }

    @Test
    void listEntries_onlyValidatesExplicitAnnotations() throws Exception {
        CompanyKnowledgeEntry entry = CompanyKnowledgeEntry.builder()
            .id("entry-1")
            .connectionId("conn-1")
            .title("MRR calculation")
            .entryType(CompanyKnowledgeEntry.EntryType.BUSINESS_RULE)
            .content("""
                Use PRODUCT_PRICING.amount / ACCOUNTS.account_billing_interval_months for MRR.
                Anchor this to @@PRODUCT_PRICING.amount and @@ACCOUNTS.account_billing_interval_months.
                The plain-text ACCOUNTS.Property_status note should not be treated as an invalid reference.
                """)
            .linkedTables(List.of("ACCOUNTS"))
            .linkedColumns(List.of("PRODUCT_PRICING.amount", "ACCOUNTS.account_billing_interval_months"))
            .build();

        when(companyKnowledgeEntryRepository.findByConnectionIdOrderByRecency("conn-1"))
            .thenReturn(List.of(entry));
        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema());

        List<CompanyKnowledgeEntry> entries = companyKnowledgeService.listEntries("conn-1");

        assertThat(entries).singleElement().satisfies(annotated -> {
            assertThat(annotated.getCoverageStatus()).isEqualTo("COMPLETE");
            assertThat(annotated.getJoinCoverageStatus()).isEqualTo("NOT_APPLICABLE");
            assertThat(annotated.getMentionedColumns()).contains("PRODUCT_PRICING.amount", "ACCOUNTS.account_billing_interval_months");
            assertThat(annotated.getUnlinkedMentions()).isEmpty();
            assertThat(annotated.getInvalidMentions()).isEmpty();
        });
    }

    @Test
    void selectFromRagHits_splitsHintsFromDiagnostics() throws Exception {
        CompanyKnowledgeEntry entry = CompanyKnowledgeEntry.builder()
            .id("entry-1")
            .connectionId("conn-1")
            .title("MRR calculation")
            .entryType(CompanyKnowledgeEntry.EntryType.BUSINESS_RULE)
            .content("Use @@PRODUCT_PRICING.amount / @@ACCOUNTS.account_billing_interval_months for MRR.")
            .linkedTables(List.of("ACCOUNTS"))
            .linkedColumns(List.of("PRODUCT_PRICING.amount", "ACCOUNTS.account_billing_interval_months"))
            .build();

        when(companyKnowledgeEntryRepository.findAllById(argThat(ids -> ids != null && ids.iterator().hasNext())))
            .thenReturn(List.of(entry));

        CompanyKnowledgeService.RelevantKnowledgeContext context = companyKnowledgeService.selectFromRagHits(
            "conn-1",
            List.of(ckHit("entry-1", 0.85)),
            List.of("ACCOUNTS"),
            schema()
        );

        assertThat(context.hintContext())
            .contains("=== COMPANY KNOWLEDGE HINTS ===")
            .contains("MRR calculation")
            .contains("@@PRODUCT_PRICING.amount / @@ACCOUNTS.account_billing_interval_months")
            .contains("Linked tables: ACCOUNTS")
            .doesNotContain("coverage=")
            .doesNotContain("Invalid or unknown references");
        assertThat(context.diagnosticsContext())
            .contains("=== COMPANY KNOWLEDGE DIAGNOSTICS ===")
            .contains("coverage=COMPLETE")
            .doesNotContain("joins=")
            .doesNotContain("Invalid or unknown references");
    }

    @Test
    void selectFromRagHits_preservesTrailingClausesOfLongRules() throws Exception {
        String longRule = "For calculating the MRR, we should refer to the subscription amount in "
            + "@@PRODUCT_PRICING.amount column. The billing interval should come from "
            + "@@ACCOUNTS.account_billing_interval_months. Amount / billing interval is the MRR. "
            + "Please note that CUSTOMERS PRICING is at the customer level and the ACCOUNTS are at the "
            + "group level. CUSTOMERS table should have the association of customer id and group id. "
            + "So for every customer, you should do Amount / Billing interval to get the MRR. "
            + "Please also note that for all non indian accounts, the amount might be in USD. "
            + "That should be converted to INR (1 USD = 92 INR).";
        assertThat(longRule.length()).isGreaterThan(420);

        CompanyKnowledgeEntry entry = CompanyKnowledgeEntry.builder()
            .id("entry-long")
            .connectionId("conn-1")
            .title("MRR calculation")
            .entryType(CompanyKnowledgeEntry.EntryType.BUSINESS_RULE)
            .content(longRule)
            .linkedTables(List.of("ACCOUNTS"))
            .linkedColumns(List.of("PRODUCT_PRICING.amount", "ACCOUNTS.account_billing_interval_months"))
            .build();

        when(companyKnowledgeEntryRepository.findAllById(argThat(ids -> ids != null && ids.iterator().hasNext())))
            .thenReturn(List.of(entry));

        CompanyKnowledgeService.RelevantKnowledgeContext context = companyKnowledgeService.selectFromRagHits(
            "conn-1",
            List.of(ckHit("entry-long", 0.80)),
            List.of("ACCOUNTS"),
            schema()
        );

        assertThat(context.hintContext())
            .contains("non indian accounts")
            .contains("1 USD = 92 INR")
            .doesNotContain("...");
        assertThat(context.diagnosticsContext())
            .contains("non indian accounts")
            .contains("1 USD = 92 INR")
            .doesNotContain("...");
    }

    @Test
    void selectFromRagHits_dropsHitsBelowScoreFloor() throws Exception {
        CompanyKnowledgeEntry entry = CompanyKnowledgeEntry.builder()
            .id("entry-weak")
            .connectionId("conn-1")
            .title("Unrelated rule")
            .entryType(CompanyKnowledgeEntry.EntryType.COMPANY_CONTEXT)
            .content("Tangential context not relevant to the question.")
            .build();

        // No focus tables → link-floor pass is a no-op. Only the RAG hit feeds the
        // selector, and its score (0.10) is below the configured 0.30 floor.
        CompanyKnowledgeService.RelevantKnowledgeContext context = companyKnowledgeService.selectFromRagHits(
            "conn-1",
            List.of(ckHit("entry-weak", 0.10)),
            List.of(),
            schema()
        );

        assertThat(context.entries()).isEmpty();
        assertThat(context.hintContext()).isEmpty();
        // The repository should not be queried when no candidate survives the floor and
        // there are no focus tables to drive the link-floor pass.
        verify(companyKnowledgeEntryRepository, never()).findAllById(any());
        verify(companyKnowledgeEntryRepository, never())
            .findByLinkedTablesAny(anyString(), any(String[].class));
    }

    @Test
    void selectFromRagHits_recallsLinkFloorEntriesEvenWithoutRagHit() throws Exception {
        // The entry has a strong table link to the focus set but no embedding hit.
        // The link-floor pass should still surface it.
        CompanyKnowledgeEntry entry = CompanyKnowledgeEntry.builder()
            .id("entry-link")
            .connectionId("conn-1")
            .title("ACCOUNTS retention rule")
            .entryType(CompanyKnowledgeEntry.EntryType.BUSINESS_RULE)
            .content("Inactive @ACCOUNTS rows older than 24 months are excluded from MRR rollups.")
            .linkedTables(List.of("ACCOUNTS"))
            .build();

        when(companyKnowledgeEntryRepository.findByLinkedTablesAny(
            eq("conn-1"), argThat(arr -> arr != null && arr.length > 0)))
            .thenReturn(List.of(entry));
        when(companyKnowledgeEntryRepository.findAllById(argThat(ids -> ids != null && ids.iterator().hasNext())))
            .thenReturn(List.of(entry));

        CompanyKnowledgeService.RelevantKnowledgeContext context = companyKnowledgeService.selectFromRagHits(
            "conn-1",
            List.of(),                  // no RAG hits
            List.of("ACCOUNTS"),        // focus tables drive the link-floor pass
            schema()
        );

        assertThat(context.entries()).extracting(CompanyKnowledgeEntry::getId).containsExactly("entry-link");
        assertThat(context.hintContext()).contains("ACCOUNTS retention rule");
    }

    @Test
    void selectFromRagHits_appliesCharBudget_andStopsBeforeOverflow() throws Exception {
        // Tighten the budget so a single sizable entry fits but a second one would push
        // us past it.
        ReflectionTestUtils.setField(companyKnowledgeService, "charBudget", 700);

        String body = "x".repeat(400);
        CompanyKnowledgeEntry first = CompanyKnowledgeEntry.builder()
            .id("entry-first")
            .connectionId("conn-1")
            .title("First rule")
            .entryType(CompanyKnowledgeEntry.EntryType.BUSINESS_RULE)
            .content(body)
            .build();
        CompanyKnowledgeEntry second = CompanyKnowledgeEntry.builder()
            .id("entry-second")
            .connectionId("conn-1")
            .title("Second rule")
            .entryType(CompanyKnowledgeEntry.EntryType.BUSINESS_RULE)
            .content(body)
            .build();

        when(companyKnowledgeEntryRepository.findAllById(argThat(ids -> ids != null && ids.iterator().hasNext())))
            .thenReturn(List.of(first, second));

        CompanyKnowledgeService.RelevantKnowledgeContext context = companyKnowledgeService.selectFromRagHits(
            "conn-1",
            // Order both above the score floor; first ranks higher so it wins the budget.
            List.of(ckHit("entry-first", 0.90), ckHit("entry-second", 0.85)),
            List.of(),
            schema()
        );

        assertThat(context.entries()).extracting(CompanyKnowledgeEntry::getId)
            .containsExactly("entry-first");
        assertThat(context.hintContext()).contains("First rule").doesNotContain("Second rule");
    }

    @Test
    void createEntry_derivesLinkedReferencesFromAnnotations_andDefaultsType() throws Exception {
        CompanyKnowledgeEntry entry = CompanyKnowledgeEntry.builder()
            .connectionId("conn-1")
            .title("Hotel onboarding context")
            .content("""
                Use @CUSTOMERS as the source of truth for property onboarding.
                Track onboarding from @@CUSTOMERS.subscription_start_date and active state from @@ACCOUNTS.account_status.
                """)
            .build();

        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schemaWithHotelAndAccounts());
        when(companyKnowledgeEntryRepository.save(any(CompanyKnowledgeEntry.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        CompanyKnowledgeEntry saved = companyKnowledgeService.createEntry(entry);

        ArgumentCaptor<CompanyKnowledgeEntry> captor = ArgumentCaptor.forClass(CompanyKnowledgeEntry.class);
        verify(companyKnowledgeEntryRepository).save(captor.capture());

        assertThat(captor.getValue().getEntryType()).isEqualTo(CompanyKnowledgeEntry.EntryType.COMPANY_CONTEXT);
        assertThat(captor.getValue().getLinkedTables()).containsExactly("CUSTOMERS");
        assertThat(captor.getValue().getLinkedColumns())
            .containsExactlyInAnyOrder("CUSTOMERS.subscription_start_date", "ACCOUNTS.account_status");
        assertThat(saved.getLinkedTables()).containsExactly("CUSTOMERS");
        assertThat(saved.getLinkedColumns())
            .containsExactlyInAnyOrder("CUSTOMERS.subscription_start_date", "ACCOUNTS.account_status");
    }

    @Test
    void listEntries_repairsMissingStoredLinksFromTaggedReferences() throws Exception {
        CompanyKnowledgeEntry entry = CompanyKnowledgeEntry.builder()
            .id("entry-3")
            .connectionId("conn-2")
            .title("MRR conversion")
            .content("""
                For MRR, use @@analytics_db.PRODUCT_PRICING.amount.
                Convert to monthly value with @@analytics_db.ACCOUNTS.account_billing_interval_months.
                """)
            .linkedTables(List.of())
            .linkedColumns(List.of("analytics_db.PRODUCT_PRICING.amount"))
            .build();

        when(companyKnowledgeEntryRepository.findByConnectionIdOrderByRecency("conn-2"))
            .thenReturn(List.of(entry));
        when(schemaScannerService.scanSchema("conn-2")).thenReturn(schemaWithQualifiedHotelTablesAndAccounts());
        when(companyKnowledgeEntryRepository.save(any(CompanyKnowledgeEntry.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        List<CompanyKnowledgeEntry> entries = companyKnowledgeService.listEntries("conn-2");

        assertThat(entries).singleElement().satisfies(annotated -> {
            assertThat(annotated.getLinkedColumns())
                .containsExactlyInAnyOrder(
                    "analytics_db.PRODUCT_PRICING.amount",
                    "analytics_db.ACCOUNTS.account_billing_interval_months"
                );
            assertThat(annotated.getInvalidMentions()).isEmpty();
            assertThat(annotated.getCoverageStatus()).isEqualTo("COMPLETE");
        });

        verify(companyKnowledgeEntryRepository).save(argThat(saved ->
            saved.getId().equals("entry-3")
                && saved.getLinkedColumns() != null
                && saved.getLinkedColumns().contains("analytics_db.ACCOUNTS.account_billing_interval_months")
        ));
    }

    @Test
    void listEntries_acceptsSchemaQualifiedAnnotations_withoutFlaggingFreeFormTableMentions() throws Exception {
        CompanyKnowledgeEntry entry = CompanyKnowledgeEntry.builder()
            .id("entry-2")
            .connectionId("conn-2")
            .title("Onboarding instructions")
            .content("""
                We should not use analytics_db.PRODUCT_PRICING for onboarding checks.
                Use @analytics_db.CUSTOMERS as the source of truth and @@analytics_db.CUSTOMERS.onboarding_status for current status.
                """)
            .linkedTables(List.of("analytics_db.CUSTOMERS"))
            .linkedColumns(List.of("analytics_db.CUSTOMERS.onboarding_status"))
            .build();

        when(companyKnowledgeEntryRepository.findByConnectionIdOrderByRecency("conn-2"))
            .thenReturn(List.of(entry));
        when(schemaScannerService.scanSchema("conn-2")).thenReturn(schemaWithQualifiedHotelTables());

        List<CompanyKnowledgeEntry> entries = companyKnowledgeService.listEntries("conn-2");

        assertThat(entries).singleElement().satisfies(annotated -> {
            assertThat(annotated.getCoverageStatus()).isEqualTo("COMPLETE");
            assertThat(annotated.getMentionedTables()).containsExactly("analytics_db.CUSTOMERS");
            assertThat(annotated.getMentionedColumns()).containsExactly("analytics_db.CUSTOMERS.onboarding_status");
            assertThat(annotated.getInvalidMentions()).isEmpty();
            assertThat(annotated.getUnlinkedMentions()).isEmpty();
        });
        verify(companyKnowledgeEntryRepository, never()).save(any(CompanyKnowledgeEntry.class));
    }

    private SchemaMetadata schema() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setTables(List.of(
            table("ACCOUNTS", column("account_billing_interval_months")),
            table("PRODUCT_PRICING", column("amount"))
        ));
        schema.setRelationships(List.of());
        return schema;
    }

    private SchemaMetadata schemaWithHotelAndAccounts() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setTables(List.of(
            table("CUSTOMERS", column("subscription_start_date")),
            table("ACCOUNTS", column("account_status"))
        ));
        schema.setRelationships(List.of());
        return schema;
    }

    private SchemaMetadata schemaWithQualifiedHotelTables() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setTables(List.of(
            table("analytics_db", "CUSTOMERS", column("onboarding_status")),
            table("analytics_db", "PRODUCT_PRICING", column("amount"))
        ));
        schema.setRelationships(List.of());
        return schema;
    }

    private SchemaMetadata schemaWithQualifiedHotelTablesAndAccounts() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setTables(List.of(
            table("analytics_db", "PRODUCT_PRICING", column("amount")),
            table("analytics_db", "ACCOUNTS", column("account_billing_interval_months"))
        ));
        schema.setRelationships(List.of());
        return schema;
    }

    private TableMetadata table(String name, ColumnMetadata... columns) {
        TableMetadata table = new TableMetadata();
        table.setName(name);
        table.setColumns(List.of(columns));
        return table;
    }

    private TableMetadata table(String schema, String name, ColumnMetadata... columns) {
        TableMetadata table = new TableMetadata();
        table.setSchema(schema);
        table.setName(name);
        table.setColumns(List.of(columns));
        return table;
    }

    private ColumnMetadata column(String name) {
        ColumnMetadata column = new ColumnMetadata();
        column.setName(name);
        column.setDataType("varchar");
        return column;
    }
}
