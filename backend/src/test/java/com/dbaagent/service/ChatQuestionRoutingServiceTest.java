package com.dbaagent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatQuestionRoutingServiceTest {

    private final ChatQuestionRoutingService service = new ChatQuestionRoutingService();

    @Test
    void classify_keyColumns_routesToBrainMetadata() {
        var route = service.classify("how many key columns we have");

        assertEquals(ChatQuestionRoutingService.RouteType.BRAIN_METADATA, route.type());
        assertEquals(ChatQuestionRoutingService.BrainTopic.KEY_COLUMNS, route.brainTopic());
    }

    @Test
    void classify_inferredKeysForNamedTable_routesToBrainMetadata() {
        var route = service.classify("what are all the inferred keys in guest mapping table?");

        assertEquals(ChatQuestionRoutingService.RouteType.BRAIN_METADATA, route.type());
        assertEquals(ChatQuestionRoutingService.BrainTopic.KEY_COLUMNS, route.brainTopic());
    }

    @Test
    void classify_relationships_routesToBrainMetadata() {
        var route = service.classify("How are USER_BOOKINGS and GUEST_MAPPING related?");

        assertEquals(ChatQuestionRoutingService.RouteType.BRAIN_METADATA, route.type());
        assertEquals(ChatQuestionRoutingService.BrainTopic.RELATIONSHIPS, route.brainTopic());
    }

    @Test
    void classify_joinGuidance_routesToBrainMetadataRelationships() {
        var route = service.classify("How should ORDERS and ORDER_DETAIL be joined?");

        assertEquals(ChatQuestionRoutingService.RouteType.BRAIN_METADATA, route.type());
        assertEquals(ChatQuestionRoutingService.BrainTopic.RELATIONSHIPS, route.brainTopic());
    }

    @Test
    void classify_growth_routesToBrainMetadata() {
        var route = service.classify("Which tables are growing the fastest?");

        assertEquals(ChatQuestionRoutingService.RouteType.BRAIN_METADATA, route.type());
        assertEquals(ChatQuestionRoutingService.BrainTopic.GROWTH, route.brainTopic());
    }

    @Test
    void classify_biPrompt_routesToBiQuery() {
        var route = service.classify("Show top 10 customers by revenue in the last 30 days");

        assertEquals(ChatQuestionRoutingService.RouteType.BI_QUERY, route.type());
        assertTrue(route.isBiQuery());
    }

    @Test
    void classify_businessPerformancePrompt_routesToBiQuery() {
        var route = service.classify("Compare payment gateway performance");

        assertEquals(ChatQuestionRoutingService.RouteType.BI_QUERY, route.type());
        assertTrue(route.isBiQuery());
    }

    @Test
    void classify_refundPerformancePrompt_routesToBiQuery() {
        var route = service.classify("Show refund performance");

        assertEquals(ChatQuestionRoutingService.RouteType.BI_QUERY, route.type());
        assertTrue(route.isBiQuery());
    }

    @Test
    void classify_exactTableRowCount_routesToSchemaMetadata() {
        var route = service.classify("How many rows in ACCOUNTS table?");

        assertEquals(ChatQuestionRoutingService.RouteType.BRAIN_METADATA, route.type());
        assertEquals(ChatQuestionRoutingService.BrainTopic.SCHEMA, route.brainTopic());
    }

    @Test
    void classify_exactTableIndexes_routesToSchemaMetadata() {
        var route = service.classify("What indexes are there on ACCOUNTS table?");

        assertEquals(ChatQuestionRoutingService.RouteType.BRAIN_METADATA, route.type());
        assertEquals(ChatQuestionRoutingService.BrainTopic.SCHEMA, route.brainTopic());
    }

    @Test
    void classify_schemaSnapshotQuestion_routesToSchemaMetadata() {
        var route = service.classify("how many schema snapshots are there for aws_sf_rds connection?");

        assertEquals(ChatQuestionRoutingService.RouteType.BRAIN_METADATA, route.type());
        assertEquals(ChatQuestionRoutingService.BrainTopic.SCHEMA, route.brainTopic());
    }

    @Test
    void classify_broadSchemaChangesQuestion_routesToSchemaMetadata() {
        var route = service.classify("what are the schema changes in the last 3 days?");

        assertEquals(ChatQuestionRoutingService.RouteType.BRAIN_METADATA, route.type());
        assertEquals(ChatQuestionRoutingService.BrainTopic.SCHEMA, route.brainTopic());
    }

    @Test
    void classify_costBenefitPerformanceFixPrompt_routesToPerformanceMetadata() {
        var route = service.classify("What is the expected ROI or cost benefit of the top performance fixes?");

        assertEquals(ChatQuestionRoutingService.RouteType.BRAIN_METADATA, route.type());
        assertEquals(ChatQuestionRoutingService.BrainTopic.PERFORMANCE, route.brainTopic());
    }

    @Test
    void metadataOnlySql_acceptsInformationSchemaQuery() {
        String sql = """
            SELECT COUNT(*)
            FROM information_schema.key_column_usage
            WHERE table_schema = DATABASE();
            """;

        assertTrue(service.isMetadataOnlySql(sql, "mysql"));
    }

    @Test
    void metadataOnlySql_rejectsApplicationTableQuery() {
        String sql = "SELECT COUNT(*) FROM user_bookings WHERE booking_status = 'CONFIRMED'";

        assertFalse(service.isMetadataOnlySql(sql, "mysql"));
    }
}
