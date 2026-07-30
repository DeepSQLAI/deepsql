package com.dbaagent.service.brain.classification;

import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.CredentialService;

import com.dbaagent.model.ConnectionRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Service for classifying tables into business domains.
 *
 * Business Domains:
 * - CUSTOMER: Users, customers, accounts, profiles
 * - PRODUCT: Products, items, inventory, catalog
 * - TRANSACTION: Orders, payments, invoices, bookings
 * - LOCATION: Addresses, regions, countries, stores
 * - TEMPORAL: Dates, times, periods, calendars
 * - CONFIGURATION: Settings, config, parameters, flags
 * - SECURITY: Roles, permissions, access_control
 * - COMMUNICATION: Emails, notifications, messages
 * - ANALYTICS: Reports, metrics, aggregations, statistics
 * - CONTENT: Content, media, files, documents
 * - UNKNOWN: Cannot determine domain
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BusinessDomainClassificationService {

    private final ConnectionService connectionService;
    private final CredentialService credentialService;

    // Domain patterns for table names
    private static final Map<String, List<Pattern>> TABLE_NAME_PATTERNS = Map.ofEntries(
        Map.entry("CUSTOMER", List.of(
            Pattern.compile("(?i).*(user|customer|client|account|profile|member|person|contact|subscriber).*")
        )),
        Map.entry("PRODUCT", List.of(
            Pattern.compile("(?i).*(product|item|inventory|catalog|sku|goods|merchandise|stock|article).*")
        )),
        Map.entry("TRANSACTION", List.of(
            Pattern.compile("(?i).*(order|payment|invoice|booking|reservation|transaction|purchase|sale|cart|checkout|receipt|refund).*")
        )),
        Map.entry("LOCATION", List.of(
            Pattern.compile("(?i).*(address|location|region|country|city|state|store|warehouse|branch|zone|territory).*")
        )),
        Map.entry("TEMPORAL", List.of(
            Pattern.compile("(?i).*(date|calendar|time|period|schedule|holiday|season|fiscal).*")
        )),
        Map.entry("CONFIGURATION", List.of(
            Pattern.compile("(?i).*(config|setting|parameter|option|preference|flag|feature|property).*")
        )),
        Map.entry("SECURITY", List.of(
            Pattern.compile("(?i).*(role|permission|access|auth|credential|token|session|policy|privilege|acl).*")
        )),
        Map.entry("COMMUNICATION", List.of(
            Pattern.compile("(?i).*(email|notification|message|alert|sms|push|newsletter|template|campaign).*")
        )),
        Map.entry("ANALYTICS", List.of(
            Pattern.compile("(?i).*(report|metric|statistic|aggregate|summary|dashboard|kpi|fact_|dim_).*")
        )),
        Map.entry("CONTENT", List.of(
            Pattern.compile("(?i).*(content|media|file|document|image|video|attachment|asset|blog|article|post).*")
        ))
    );

    // Column patterns that indicate specific domains
    private static final Map<String, List<Pattern>> COLUMN_PATTERNS = Map.ofEntries(
        Map.entry("CUSTOMER", List.of(
            Pattern.compile("(?i)(email|phone|first_name|last_name|full_name|date_of_birth|dob|age|gender|avatar|username|password_hash)"),
            Pattern.compile("(?i)(customer_id|user_id|member_id|account_id|profile_id)")
        )),
        Map.entry("PRODUCT", List.of(
            Pattern.compile("(?i)(sku|upc|barcode|price|cost|weight|dimensions|manufacturer|brand|category|stock_quantity)"),
            Pattern.compile("(?i)(product_id|item_id|catalog_id)")
        )),
        Map.entry("TRANSACTION", List.of(
            Pattern.compile("(?i)(total_amount|subtotal|tax|discount|quantity|unit_price|payment_method|transaction_id|order_number)"),
            Pattern.compile("(?i)(order_id|invoice_id|payment_id|booking_id|reservation_id)")
        )),
        Map.entry("LOCATION", List.of(
            Pattern.compile("(?i)(street|city|state|province|postal_code|zip_code|country|latitude|longitude|coordinates)"),
            Pattern.compile("(?i)(address_id|location_id|region_id|store_id)")
        )),
        Map.entry("TEMPORAL", List.of(
            Pattern.compile("(?i)(year|month|day|quarter|week|fiscal_year|fiscal_quarter|is_weekend|is_holiday)")
        )),
        Map.entry("CONFIGURATION", List.of(
            Pattern.compile("(?i)(setting_key|setting_value|config_key|config_value|is_enabled|is_active|feature_flag)")
        )),
        Map.entry("SECURITY", List.of(
            Pattern.compile("(?i)(role_name|permission_name|access_level|scope|resource|action|is_admin|is_superuser)"),
            Pattern.compile("(?i)(role_id|permission_id|policy_id)")
        )),
        Map.entry("COMMUNICATION", List.of(
            Pattern.compile("(?i)(subject|body|recipient|sender|sent_at|read_at|template_name|channel)")
        )),
        Map.entry("ANALYTICS", List.of(
            Pattern.compile("(?i)(metric_name|metric_value|dimension|measure|aggregation_type|period_start|period_end)")
        )),
        Map.entry("CONTENT", List.of(
            Pattern.compile("(?i)(title|slug|content|body|excerpt|author|published_at|mime_type|file_size|file_path)")
        ))
    );

    /**
     * Classify business domains for all tables in a connection.
     */
    public Map<String, DomainClassification> classifyBusinessDomains(String connectionId) {
        Map<String, DomainClassification> results = new HashMap<>();

        ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
        if (connRequest == null) {
            log.warn("Connection not found: {}", connectionId);
            return results;
        }

        String dbType = connRequest.getDbType();

        try (Connection conn = connectionService.getConnection(connectionId, connRequest)) {
            // Get all tables with their columns
            Map<String, List<String>> tableColumns = getTableColumns(conn, dbType);

            // Classify each table
            for (Map.Entry<String, List<String>> entry : tableColumns.entrySet()) {
                String tableName = entry.getKey();
                List<String> columns = entry.getValue();

                DomainClassification classification = classifyTable(tableName, columns);
                results.put(tableName.toLowerCase(), classification);
            }

        } catch (Exception e) {
            log.error("Error classifying business domains for connection {}: {}", connectionId, e.getMessage(), e);
        }

        return results;
    }

    /**
     * Classify a single table's business domain.
     */
    private DomainClassification classifyTable(String tableName, List<String> columns) {
        Map<String, Double> domainScores = new HashMap<>();
        List<Map<String, Object>> indicators = new ArrayList<>();

        // Score based on table name
        for (Map.Entry<String, List<Pattern>> entry : TABLE_NAME_PATTERNS.entrySet()) {
            String domain = entry.getKey();
            for (Pattern pattern : entry.getValue()) {
                if (pattern.matcher(tableName).matches()) {
                    domainScores.merge(domain, 40.0, Double::sum);
                    indicators.add(Map.of(
                        "type", "TABLE_NAME",
                        "pattern", pattern.pattern(),
                        "matches", domain,
                        "weight", 40.0
                    ));
                    break; // Only count once per domain
                }
            }
        }

        // Score based on column names
        for (String column : columns) {
            for (Map.Entry<String, List<Pattern>> entry : COLUMN_PATTERNS.entrySet()) {
                String domain = entry.getKey();
                for (Pattern pattern : entry.getValue()) {
                    if (pattern.matcher(column).matches()) {
                        domainScores.merge(domain, 10.0, Double::sum);
                        indicators.add(Map.of(
                            "type", "COLUMN_PATTERN",
                            "column", column,
                            "matches", domain,
                            "weight", 10.0
                        ));
                        break; // Only count once per column per domain
                    }
                }
            }
        }

        // Find the domain with highest score
        String bestDomainTemp = "UNKNOWN";
        double bestScore = 0;
        for (Map.Entry<String, Double> entry : domainScores.entrySet()) {
            if (entry.getValue() > bestScore) {
                bestScore = entry.getValue();
                bestDomainTemp = entry.getKey();
            }
        }
        final String bestDomain = bestDomainTemp;

        // Calculate confidence (0-100)
        // Higher confidence if score is significantly higher than second best
        double confidence;
        if (bestScore == 0) {
            confidence = 50.0; // Low confidence for UNKNOWN
        } else {
            // Find second best score
            double secondBest = domainScores.entrySet().stream()
                .filter(e -> !e.getKey().equals(bestDomain))
                .mapToDouble(Map.Entry::getValue)
                .max()
                .orElse(0);

            // Confidence based on margin and absolute score
            double margin = bestScore - secondBest;
            confidence = Math.min(100, 50 + (bestScore / 2) + (margin / 2));
        }

        // Generate reasoning
        String reasoning;
        if (bestScore == 0) {
            reasoning = "No matching patterns found for table name or columns";
        } else {
            long tableNameMatches = indicators.stream()
                .filter(i -> "TABLE_NAME".equals(i.get("type")))
                .count();
            long columnMatches = indicators.stream()
                .filter(i -> "COLUMN_PATTERN".equals(i.get("type")))
                .count();
            reasoning = String.format("Domain '%s' detected: %d table name match(es), %d column pattern match(es), score=%.0f",
                bestDomain, tableNameMatches, columnMatches, bestScore);
        }

        return DomainClassification.builder()
            .tableName(tableName)
            .businessDomain(bestDomain)
            .confidence(BigDecimal.valueOf(confidence).setScale(2, RoundingMode.HALF_UP))
            .domainScores(domainScores)
            .indicators(indicators)
            .reasoning(reasoning)
            .build();
    }

    /**
     * Get all tables with their column names.
     */
    private Map<String, List<String>> getTableColumns(Connection conn, String dbType) {
        Map<String, List<String>> tableColumns = new HashMap<>();

        String sql;
        if ("postgresql".equalsIgnoreCase(dbType)) {
            sql = """
                SELECT table_name, column_name
                FROM information_schema.columns
                WHERE table_schema NOT IN ('pg_catalog', 'information_schema')
                ORDER BY table_name, ordinal_position
                """;
        } else {
            sql = """
                SELECT TABLE_NAME as table_name, COLUMN_NAME as column_name
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                ORDER BY TABLE_NAME, ORDINAL_POSITION
                """;
        }

        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String tableName = rs.getString("table_name");
                String columnName = rs.getString("column_name");
                tableColumns.computeIfAbsent(tableName, k -> new ArrayList<>()).add(columnName);
            }
        } catch (Exception e) {
            log.error("Error getting table columns: {}", e.getMessage(), e);
        }

        return tableColumns;
    }

    // ==================== Data Classes ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DomainClassification {
        private String tableName;
        private String businessDomain;  // CUSTOMER, PRODUCT, TRANSACTION, etc.
        private BigDecimal confidence;
        private Map<String, Double> domainScores;
        private List<Map<String, Object>> indicators;
        private String reasoning;
    }
}
