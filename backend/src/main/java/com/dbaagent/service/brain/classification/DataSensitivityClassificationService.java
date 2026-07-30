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
 * Service for classifying data sensitivity in tables.
 *
 * Sensitivity Levels:
 * - PII_HIGH: SSN, passport, government ID, biometric data
 * - PII_MEDIUM: Email, phone, name, address, date of birth
 * - FINANCIAL: Credit card, bank account, salary, tax information
 * - HEALTH: Medical records, diagnoses, prescriptions (HIPAA)
 * - REGULATED: Subject to GDPR, CCPA, SOX, PCI-DSS
 * - PUBLIC: No sensitive data detected
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DataSensitivityClassificationService {

    private final ConnectionService connectionService;
    private final CredentialService credentialService;

    // High-sensitivity PII patterns (government IDs, biometrics)
    private static final List<SensitiveColumnPattern> PII_HIGH_PATTERNS = List.of(
        new SensitiveColumnPattern(Pattern.compile("(?i).*(ssn|social_security|national_id|national_insurance|passport|driver_license|drivers_license).*"), "Government ID", 100),
        new SensitiveColumnPattern(Pattern.compile("(?i).*(biometric|fingerprint|retina|face_encoding|voice_print).*"), "Biometric Data", 100),
        new SensitiveColumnPattern(Pattern.compile("(?i).*(tax_id|tin|ein|itin|vat_number|pan_number).*"), "Tax ID", 95)
    );

    // Medium-sensitivity PII patterns (contact info, demographics)
    private static final List<SensitiveColumnPattern> PII_MEDIUM_PATTERNS = List.of(
        new SensitiveColumnPattern(Pattern.compile("(?i)^email$|.*_email$|^email_.*"), "Email Address", 90),
        new SensitiveColumnPattern(Pattern.compile("(?i).*(phone|mobile|cell|fax|telephone).*"), "Phone Number", 85),
        new SensitiveColumnPattern(Pattern.compile("(?i)^(first_name|last_name|full_name|surname|given_name|family_name|middle_name)$"), "Personal Name", 80),
        new SensitiveColumnPattern(Pattern.compile("(?i).*(date_of_birth|dob|birth_date|birthday).*"), "Date of Birth", 85),
        new SensitiveColumnPattern(Pattern.compile("(?i).*(street|address|city|postal|zip_code|apt|suite|unit)(?!_id).*"), "Address", 75),
        new SensitiveColumnPattern(Pattern.compile("(?i)^(ip_address|ip|user_agent|device_id|mac_address)$"), "Device/Network ID", 70),
        new SensitiveColumnPattern(Pattern.compile("(?i).*(gender|sex|marital_status|ethnicity|race|religion|nationality).*"), "Demographic", 70)
    );

    // Financial data patterns
    private static final List<SensitiveColumnPattern> FINANCIAL_PATTERNS = List.of(
        new SensitiveColumnPattern(Pattern.compile("(?i).*(credit_card|card_number|card_num|cc_number|pan)(?!_id).*"), "Credit Card", 100),
        new SensitiveColumnPattern(Pattern.compile("(?i).*(cvv|cvc|security_code|card_security).*"), "Card Security Code", 100),
        new SensitiveColumnPattern(Pattern.compile("(?i).*(bank_account|account_number|routing_number|iban|swift|bic).*"), "Bank Account", 95),
        new SensitiveColumnPattern(Pattern.compile("(?i)^(salary|wage|income|compensation|pay_rate|hourly_rate)$"), "Salary/Income", 90),
        new SensitiveColumnPattern(Pattern.compile("(?i).*(tax_amount|tax_rate|withholding).*"), "Tax Information", 80)
    );

    // Health data patterns (HIPAA)
    private static final List<SensitiveColumnPattern> HEALTH_PATTERNS = List.of(
        new SensitiveColumnPattern(Pattern.compile("(?i).*(diagnosis|icd_code|medical_condition|health_condition).*"), "Medical Diagnosis", 100),
        new SensitiveColumnPattern(Pattern.compile("(?i).*(prescription|medication|drug|dosage|rx_).*"), "Prescription", 95),
        new SensitiveColumnPattern(Pattern.compile("(?i).*(medical_record|health_record|patient_id|mrn).*"), "Medical Record", 100),
        new SensitiveColumnPattern(Pattern.compile("(?i).*(blood_type|allergy|immunization|vaccine|lab_result).*"), "Health Data", 90),
        new SensitiveColumnPattern(Pattern.compile("(?i).*(insurance_id|policy_number|member_id|group_number).*"), "Insurance Info", 85)
    );

    // Authentication/Security patterns (special handling)
    private static final List<SensitiveColumnPattern> AUTH_PATTERNS = List.of(
        new SensitiveColumnPattern(Pattern.compile("(?i).*(password|passwd|pwd|secret|api_key|access_token|refresh_token|private_key).*"), "Authentication Secret", 100),
        new SensitiveColumnPattern(Pattern.compile("(?i).*(password_hash|pwd_hash|passwd_hash|secret_hash|encrypted_|cipher_).*"), "Encrypted Data", 70)
    );

    /**
     * Classify data sensitivity for all tables in a connection.
     */
    public Map<String, SensitivityClassification> classifyDataSensitivity(String connectionId) {
        Map<String, SensitivityClassification> results = new HashMap<>();

        ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
        if (connRequest == null) {
            log.warn("Connection not found: {}", connectionId);
            return results;
        }

        String dbType = connRequest.getDbType();

        try (Connection conn = connectionService.getConnection(connectionId, connRequest)) {
            // Get all tables with their columns
            Map<String, List<ColumnMetadata>> tableColumns = getTableColumnsWithMetadata(conn, dbType);

            // Classify each table
            for (Map.Entry<String, List<ColumnMetadata>> entry : tableColumns.entrySet()) {
                String tableName = entry.getKey();
                List<ColumnMetadata> columns = entry.getValue();

                SensitivityClassification classification = classifyTable(tableName, columns);
                results.put(tableName.toLowerCase(), classification);
            }

        } catch (Exception e) {
            log.error("Error classifying data sensitivity for connection {}: {}", connectionId, e.getMessage(), e);
        }

        return results;
    }

    /**
     * Classify a single table's data sensitivity.
     */
    private SensitivityClassification classifyTable(String tableName, List<ColumnMetadata> columns) {
        List<SensitiveColumnInfo> sensitiveColumns = new ArrayList<>();
        Set<String> detectedCategories = new HashSet<>();

        for (ColumnMetadata column : columns) {
            String colName = column.getColumnName();

            // Check all pattern categories
            checkPatterns(colName, PII_HIGH_PATTERNS, "PII_HIGH", sensitiveColumns, detectedCategories);
            checkPatterns(colName, PII_MEDIUM_PATTERNS, "PII_MEDIUM", sensitiveColumns, detectedCategories);
            checkPatterns(colName, FINANCIAL_PATTERNS, "FINANCIAL", sensitiveColumns, detectedCategories);
            checkPatterns(colName, HEALTH_PATTERNS, "HEALTH", sensitiveColumns, detectedCategories);
            checkPatterns(colName, AUTH_PATTERNS, "AUTH", sensitiveColumns, detectedCategories);
        }

        // Determine overall sensitivity level
        String sensitivityLevel;
        double confidence;
        if (detectedCategories.contains("PII_HIGH") || detectedCategories.contains("HEALTH") ||
            detectedCategories.contains("AUTH")) {
            sensitivityLevel = detectedCategories.contains("HEALTH") ? "HEALTH" :
                              detectedCategories.contains("PII_HIGH") ? "PII_HIGH" : "REGULATED";
            confidence = 95.0;
        } else if (detectedCategories.contains("FINANCIAL")) {
            sensitivityLevel = "FINANCIAL";
            confidence = 90.0;
        } else if (detectedCategories.contains("PII_MEDIUM")) {
            sensitivityLevel = "PII_MEDIUM";
            confidence = 85.0;
        } else {
            sensitivityLevel = "PUBLIC";
            confidence = sensitiveColumns.isEmpty() ? 90.0 : 70.0;
        }

        // Calculate average confidence from detected columns
        if (!sensitiveColumns.isEmpty()) {
            double avgColumnConfidence = sensitiveColumns.stream()
                .mapToDouble(SensitiveColumnInfo::getConfidence)
                .average()
                .orElse(confidence);
            confidence = (confidence + avgColumnConfidence) / 2;
        }

        // Generate compliance flags
        List<String> complianceFlags = generateComplianceFlags(detectedCategories, sensitiveColumns);

        // Generate recommendations
        List<String> recommendations = generateRecommendations(sensitivityLevel, sensitiveColumns, columns.size());

        return SensitivityClassification.builder()
            .tableName(tableName)
            .sensitivityLevel(sensitivityLevel)
            .sensitiveColumns(sensitiveColumns)
            .sensitiveColumnCount(sensitiveColumns.size())
            .totalColumnCount(columns.size())
            .confidence(BigDecimal.valueOf(confidence).setScale(2, RoundingMode.HALF_UP))
            .detectedCategories(new ArrayList<>(detectedCategories))
            .complianceFlags(complianceFlags)
            .recommendations(recommendations)
            .build();
    }

    private void checkPatterns(String columnName, List<SensitiveColumnPattern> patterns,
                               String category, List<SensitiveColumnInfo> sensitiveColumns,
                               Set<String> detectedCategories) {
        for (SensitiveColumnPattern pattern : patterns) {
            if (pattern.getPattern().matcher(columnName).matches()) {
                sensitiveColumns.add(SensitiveColumnInfo.builder()
                    .column(columnName)
                    .sensitivityType(category)
                    .dataType(pattern.getDescription())
                    .confidence(pattern.getConfidence())
                    .build());
                detectedCategories.add(category);
                break; // Only match first pattern per column
            }
        }
    }

    private List<String> generateComplianceFlags(Set<String> categories, List<SensitiveColumnInfo> columns) {
        List<String> flags = new ArrayList<>();

        if (categories.contains("PII_HIGH") || categories.contains("PII_MEDIUM")) {
            flags.add("GDPR");
            flags.add("CCPA");
        }
        if (categories.contains("FINANCIAL")) {
            flags.add("PCI-DSS");
            flags.add("SOX");
        }
        if (categories.contains("HEALTH")) {
            flags.add("HIPAA");
            flags.add("HITECH");
        }
        if (categories.contains("AUTH")) {
            flags.add("SOC2");
        }

        // Check for specific high-risk columns
        boolean hasCreditCard = columns.stream()
            .anyMatch(c -> c.getDataType().contains("Credit Card"));
        if (hasCreditCard) {
            if (!flags.contains("PCI-DSS")) flags.add("PCI-DSS");
        }

        return flags;
    }

    private List<String> generateRecommendations(String sensitivityLevel, List<SensitiveColumnInfo> sensitiveColumns, int totalColumns) {
        List<String> recommendations = new ArrayList<>();

        if ("PUBLIC".equals(sensitivityLevel)) {
            return recommendations;
        }

        recommendations.add("Ensure data access is logged and auditable");

        if ("PII_HIGH".equals(sensitivityLevel) || "HEALTH".equals(sensitivityLevel)) {
            recommendations.add("Implement column-level encryption for highly sensitive fields");
            recommendations.add("Consider data masking for non-production environments");
            recommendations.add("Implement strict access controls with role-based permissions");
        }

        if ("FINANCIAL".equals(sensitivityLevel)) {
            recommendations.add("Ensure PCI-DSS compliance for credit card data storage");
            recommendations.add("Implement tokenization for payment card numbers");
        }

        if (sensitiveColumns.stream().anyMatch(c -> "AUTH".equals(c.getSensitivityType()))) {
            recommendations.add("Verify that passwords/secrets are properly hashed, not stored in plaintext");
            recommendations.add("Consider using a dedicated secrets management service");
        }

        double sensitiveRatio = (double) sensitiveColumns.size() / totalColumns;
        if (sensitiveRatio > 0.5) {
            recommendations.add("High proportion of sensitive columns (" + String.format("%.0f%%", sensitiveRatio * 100) +
                ") - consider table-level encryption");
        }

        return recommendations;
    }

    /**
     * Get all tables with their column metadata.
     */
    private Map<String, List<ColumnMetadata>> getTableColumnsWithMetadata(Connection conn, String dbType) {
        Map<String, List<ColumnMetadata>> tableColumns = new HashMap<>();

        String sql;
        if ("postgresql".equalsIgnoreCase(dbType)) {
            sql = """
                SELECT table_name, column_name, data_type, is_nullable
                FROM information_schema.columns
                WHERE table_schema NOT IN ('pg_catalog', 'information_schema')
                ORDER BY table_name, ordinal_position
                """;
        } else {
            sql = """
                SELECT TABLE_NAME as table_name, COLUMN_NAME as column_name,
                       DATA_TYPE as data_type, IS_NULLABLE as is_nullable
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                ORDER BY TABLE_NAME, ORDINAL_POSITION
                """;
        }

        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String tableName = rs.getString("table_name");
                ColumnMetadata col = ColumnMetadata.builder()
                    .columnName(rs.getString("column_name"))
                    .dataType(rs.getString("data_type"))
                    .isNullable("YES".equalsIgnoreCase(rs.getString("is_nullable")))
                    .build();
                tableColumns.computeIfAbsent(tableName, k -> new ArrayList<>()).add(col);
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
    public static class SensitivityClassification {
        private String tableName;
        private String sensitivityLevel;  // PII_HIGH, PII_MEDIUM, FINANCIAL, HEALTH, REGULATED, PUBLIC
        private List<SensitiveColumnInfo> sensitiveColumns;
        private int sensitiveColumnCount;
        private int totalColumnCount;
        private BigDecimal confidence;
        private List<String> detectedCategories;
        private List<String> complianceFlags;
        private List<String> recommendations;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SensitiveColumnInfo {
        private String column;
        private String sensitivityType;  // PII_HIGH, PII_MEDIUM, FINANCIAL, HEALTH, AUTH
        private String dataType;         // Description of what kind of sensitive data
        private double confidence;
    }

    @Data
    @AllArgsConstructor
    private static class SensitiveColumnPattern {
        private Pattern pattern;
        private String description;
        private int confidence;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class ColumnMetadata {
        private String columnName;
        private String dataType;
        private boolean isNullable;
    }
}
