package com.dbaagent.service;

import com.dbaagent.dto.ConnectionChatAccessPolicyResponse;
import com.dbaagent.dto.PolicyPreviewResponse;
import com.dbaagent.model.ConnectionChatAccessPolicy;
import com.dbaagent.model.SecurityEventOutcome;
import com.dbaagent.model.SecurityEventType;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TableClassification;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.repository.ConnectionChatAccessPolicyRepository;
import com.dbaagent.repository.TableClassificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ConnectionChatAccessPolicyService {

    private final ConnectionChatAccessPolicyRepository policyRepository;
    private final TableClassificationRepository tableClassificationRepository;
    private final ObjectProvider<SchemaScannerService> schemaScannerServiceProvider;
    private final SecurityEventService securityEventService;

    @Transactional(readOnly = true)
    public Optional<ConnectionChatAccessPolicyResponse> getPolicyResponse(String connectionId, String username) {
        return policyRepository.findByConnectionIdAndUsernameIgnoreCase(connectionId, username)
            .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public EffectivePolicy resolveEffectivePolicy(String connectionId, String username, boolean actorIsAdmin) {
        if (actorIsAdmin || username == null || username.isBlank()) {
            return EffectivePolicy.none();
        }
        return policyRepository.findByConnectionIdAndUsernameIgnoreCase(connectionId, username)
            .filter(ConnectionChatAccessPolicy::isActive)
            .map(this::toEffectivePolicy)
            .orElseGet(EffectivePolicy::none);
    }

    @Transactional
    public ConnectionChatAccessPolicyResponse savePolicy(
        String connectionId,
        String username,
        String plainEnglishPolicy,
        Boolean active,
        String updatedBy
    ) {
        if (plainEnglishPolicy == null || plainEnglishPolicy.isBlank()) {
            throw new IllegalArgumentException("Policy text is required");
        }

        ParsedPolicy parsedPolicy = parsePolicy(connectionId, plainEnglishPolicy);
        ConnectionChatAccessPolicy policy = policyRepository
            .findByConnectionIdAndUsernameIgnoreCase(connectionId, username)
            .orElseGet(ConnectionChatAccessPolicy::new);
        policy.setConnectionId(connectionId);
        policy.setUsername(username);
        policy.setPlainEnglishPolicy(plainEnglishPolicy.trim());
        policy.setBlockedSensitivityCategories(parsedPolicy.blockedSensitivityCategories());
        policy.setDeniedTables(parsedPolicy.deniedTables());
        policy.setDeniedColumns(parsedPolicy.deniedColumns());
        policy.setBlockMode(parsedPolicy.blockMode());
        policy.setRedactMode(parsedPolicy.redactMode());
        policy.setActive(active == null || active);
        policy.setUpdatedBy(updatedBy);
        ConnectionChatAccessPolicy saved = policyRepository.save(policy);

        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(SecurityEventType.CHAT_ACCESS_POLICY_UPDATED)
            .outcome(SecurityEventOutcome.SUCCESS)
            .email(username)
            .targetResource("connection:" + connectionId)
            .metadata(Map.of(
                "username", username,
                "updatedBy", updatedBy,
                "blockedSensitivityCategories", parsedPolicy.blockedSensitivityCategories(),
                "deniedTables", parsedPolicy.deniedTables(),
                "deniedColumns", parsedPolicy.deniedColumns()
            ))
            .build());

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public PolicyPreviewResponse previewPolicy(String connectionId, String plainEnglishPolicy) {
        ParsedPolicy parsedPolicy = parsePolicy(connectionId, plainEnglishPolicy);
        return PolicyPreviewResponse.builder()
            .blockedSensitivityCategories(parsedPolicy.blockedSensitivityCategories())
            .deniedTables(parsedPolicy.deniedTables())
            .deniedColumns(parsedPolicy.deniedColumns())
            .impactedTables(parsedPolicy.impactedTables())
            .impactedColumns(parsedPolicy.impactedColumns())
            .blockMode(parsedPolicy.blockMode())
            .redactMode(parsedPolicy.redactMode())
            .build();
    }

    private ConnectionChatAccessPolicyResponse toResponse(ConnectionChatAccessPolicy policy) {
        ParsedPolicy parsedPolicy = parsedFromPolicy(policy);
        return ConnectionChatAccessPolicyResponse.builder()
            .id(policy.getId())
            .connectionId(policy.getConnectionId())
            .username(policy.getUsername())
            .plainEnglishPolicy(policy.getPlainEnglishPolicy())
            .blockedSensitivityCategories(policy.getBlockedSensitivityCategories())
            .deniedTables(policy.getDeniedTables())
            .deniedColumns(policy.getDeniedColumns())
            .blockMode(policy.isBlockMode())
            .redactMode(policy.isRedactMode())
            .active(policy.isActive())
            .updatedBy(policy.getUpdatedBy())
            .createdAt(policy.getCreatedAt())
            .updatedAt(policy.getUpdatedAt())
            .impactedTables(parsedPolicy.impactedTables())
            .impactedColumns(parsedPolicy.impactedColumns())
            .build();
    }

    private EffectivePolicy toEffectivePolicy(ConnectionChatAccessPolicy policy) {
        ParsedPolicy parsedPolicy = parsedFromPolicy(policy);
        return new EffectivePolicy(
            true,
            policy.getConnectionId(),
            policy.getUsername(),
            new LinkedHashSet<>(policy.getBlockedSensitivityCategories() == null ? List.of() : policy.getBlockedSensitivityCategories()),
            new LinkedHashSet<>(policy.getDeniedTables() == null ? List.of() : policy.getDeniedTables()),
            new LinkedHashSet<>(policy.getDeniedColumns() == null ? List.of() : policy.getDeniedColumns()),
            policy.isBlockMode(),
            policy.isRedactMode(),
            policy.getPlainEnglishPolicy(),
            parsedPolicy.impactedTables(),
            parsedPolicy.impactedColumns()
        );
    }

    private ParsedPolicy parsedFromPolicy(ConnectionChatAccessPolicy policy) {
        List<String> impactedTables = new ArrayList<>();
        List<String> impactedColumns = new ArrayList<>();
        Map<String, ProtectionDescriptor> descriptors = buildProtectionDescriptors(
            policy.getConnectionId(),
            policy.getBlockedSensitivityCategories(),
            policy.getDeniedTables(),
            policy.getDeniedColumns()
        );
        descriptors.values().forEach(descriptor -> {
            if (descriptor.protectWholeTable()) {
                impactedTables.add(descriptor.tableName());
            }
            descriptor.restrictedColumns().forEach(column -> impactedColumns.add(descriptor.tableName() + "." + column));
        });
        return new ParsedPolicy(
            normalizeList(policy.getBlockedSensitivityCategories()),
            normalizeList(policy.getDeniedTables()),
            normalizeList(policy.getDeniedColumns()),
            impactedTables,
            impactedColumns,
            policy.isBlockMode(),
            policy.isRedactMode()
        );
    }

    private ParsedPolicy parsePolicy(String connectionId, String plainEnglishPolicy) {
        String normalized = plainEnglishPolicy == null ? "" : plainEnglishPolicy.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> blockedCategories = new LinkedHashSet<>();

        if (containsAny(normalized, "pii", "personally identifiable", "personal data")) {
            blockedCategories.add("PII_HIGH");
            blockedCategories.add("PII_MEDIUM");
        }
        if (containsAny(normalized, "email", "emails", "phone", "mobile", "contact info", "address", "name", "dob", "date of birth")) {
            blockedCategories.add("PII_MEDIUM");
        }
        if (containsAny(normalized, "ssn", "passport", "government id", "tax id", "biometric")) {
            blockedCategories.add("PII_HIGH");
        }
        if (containsAny(normalized, "financial", "bank", "credit card", "card details", "card number", "payment card", "salary", "income", "routing number")) {
            blockedCategories.add("FINANCIAL");
        }
        if (containsAny(normalized, "health", "medical", "diagnosis", "prescription", "patient")) {
            blockedCategories.add("HEALTH");
        }
        if (containsAny(normalized, "regulated", "gdpr", "ccpa", "pci", "hipaa", "sox")) {
            blockedCategories.add("REGULATED");
        }
        if (containsAny(normalized, "password", "secret", "token", "api key", "private key", "credential")) {
            blockedCategories.add("AUTH");
        }

        SchemaMetadata schemaMetadata = tryScanSchema(connectionId);
        LinkedHashSet<String> deniedTables = new LinkedHashSet<>();
        LinkedHashSet<String> deniedColumns = new LinkedHashSet<>();
        if (schemaMetadata != null) {
            for (TableMetadata table : schemaMetadata.getTables()) {
                if (containsWord(normalized, table.getName())) {
                    deniedTables.add(table.getName());
                }
                if (table.getColumns() != null) {
                    table.getColumns().forEach(column -> {
                        String qualified = table.getName() + "." + column.getName();
                        if (containsWord(normalized, qualified) || containsWord(normalized, column.getName())) {
                            deniedColumns.add(qualified);
                        }
                    });
                }
            }
        }

        Map<String, ProtectionDescriptor> descriptors = buildProtectionDescriptors(
            connectionId,
            new ArrayList<>(blockedCategories),
            new ArrayList<>(deniedTables),
            new ArrayList<>(deniedColumns)
        );

        List<String> impactedTables = descriptors.values().stream()
            .filter(ProtectionDescriptor::protectWholeTable)
            .map(ProtectionDescriptor::tableName)
            .distinct()
            .sorted()
            .toList();
        List<String> impactedColumns = descriptors.values().stream()
            .flatMap(descriptor -> descriptor.restrictedColumns().stream().map(column -> descriptor.tableName() + "." + column))
            .distinct()
            .sorted()
            .toList();

        return new ParsedPolicy(
            new ArrayList<>(blockedCategories),
            new ArrayList<>(deniedTables),
            new ArrayList<>(deniedColumns),
            impactedTables,
            impactedColumns,
            true,
            true
        );
    }

    public Map<String, ProtectionDescriptor> buildProtectionDescriptors(
        String connectionId,
        List<String> blockedSensitivityCategories,
        List<String> deniedTables,
        List<String> deniedColumns
    ) {
        Set<String> categorySet = normalizeSet(blockedSensitivityCategories);
        Set<String> deniedTableSet = normalizeSet(deniedTables);
        Set<String> deniedColumnSet = normalizeSet(deniedColumns);

        Map<String, ProtectionDescriptor> descriptors = new LinkedHashMap<>();
        for (TableClassification classification : tableClassificationRepository.findLatestByConnectionIdOrderByTableNameAsc(connectionId)) {
            String tableName = classification.getTableName();
            ProtectionDescriptor descriptor = descriptors.computeIfAbsent(
                normalizeName(tableName),
                ignored -> new ProtectionDescriptor(tableName, false, new LinkedHashSet<>())
            );

            boolean tableExplicitlyDenied = deniedTableSet.contains(normalizeName(tableName));
            boolean tableCategoryBlocked = categorySet.contains(normalizeName(classification.getSensitivityLevel()));
            if (tableExplicitlyDenied) {
                descriptor.protectWholeTable = true;
            }
            if (tableCategoryBlocked && (classification.getSensitiveColumns() == null || classification.getSensitiveColumns().isEmpty())) {
                descriptor.protectWholeTable = true;
            }

            if (classification.getSensitiveColumns() != null) {
                for (Map<String, Object> sensitiveColumn : classification.getSensitiveColumns()) {
                    String column = String.valueOf(sensitiveColumn.get("column"));
                    String type = sensitiveColumn.get("type") == null ? "" : String.valueOf(sensitiveColumn.get("type"));
                    if (categorySet.contains(normalizeName(type))
                        || deniedColumnSet.contains(normalizeName(tableName + "." + column))
                        || deniedColumnSet.contains(normalizeName(column))
                        || tableExplicitlyDenied) {
                        descriptor.restrictedColumns.add(column);
                    }
                }
            }
        }

        deniedTableSet.forEach(table -> descriptors.computeIfAbsent(table, key -> new ProtectionDescriptor(table, true, new LinkedHashSet<>())).protectWholeTable = true);
        deniedColumnSet.forEach(columnRef -> {
            String[] parts = columnRef.split("\\.", 2);
            if (parts.length == 2) {
                ProtectionDescriptor descriptor = descriptors.computeIfAbsent(parts[0], key -> new ProtectionDescriptor(parts[0], false, new LinkedHashSet<>()));
                descriptor.restrictedColumns.add(parts[1]);
            }
        });
        return descriptors;
    }

    private SchemaMetadata tryScanSchema(String connectionId) {
        try {
            SchemaScannerService schemaScannerService = schemaScannerServiceProvider.getIfAvailable();
            if (schemaScannerService == null) {
                return null;
            }
            return schemaScannerService.scanSchema(connectionId);
        } catch (SQLException e) {
            return null;
        }
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsWord(String haystack, String rawNeedle) {
        if (haystack == null || haystack.isBlank() || rawNeedle == null || rawNeedle.isBlank()) {
            return false;
        }
        String needle = rawNeedle.toLowerCase(Locale.ROOT).replace("\"", "").replace("`", "");
        return haystack.contains(needle.toLowerCase(Locale.ROOT));
    }

    private Set<String> normalizeSet(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values == null) {
            return normalized;
        }
        values.stream()
            .map(this::normalizeName)
            .filter(value -> !value.isBlank())
            .forEach(normalized::add);
        return normalized;
    }

    private List<String> normalizeList(List<String> values) {
        return new ArrayList<>(normalizeSet(values));
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record EffectivePolicy(
        boolean present,
        String connectionId,
        String username,
        Set<String> blockedSensitivityCategories,
        Set<String> deniedTables,
        Set<String> deniedColumns,
        boolean blockMode,
        boolean redactMode,
        String plainEnglishPolicy,
        List<String> impactedTables,
        List<String> impactedColumns
    ) {
        public static EffectivePolicy none() {
            return new EffectivePolicy(false, null, null, Set.of(), Set.of(), Set.of(), false, false, null, List.of(), List.of());
        }

        public boolean protectsAnything() {
            return present && (!blockedSensitivityCategories.isEmpty() || !deniedTables.isEmpty() || !deniedColumns.isEmpty());
        }
    }

    private record ParsedPolicy(
        List<String> blockedSensitivityCategories,
        List<String> deniedTables,
        List<String> deniedColumns,
        List<String> impactedTables,
        List<String> impactedColumns,
        boolean blockMode,
        boolean redactMode
    ) {
    }

    public static final class ProtectionDescriptor {
        private final String tableName;
        private boolean protectWholeTable;
        private final Set<String> restrictedColumns;

        private ProtectionDescriptor(String tableName, boolean protectWholeTable, Set<String> restrictedColumns) {
            this.tableName = tableName;
            this.protectWholeTable = protectWholeTable;
            this.restrictedColumns = restrictedColumns;
        }

        public String tableName() {
            return tableName;
        }

        public boolean protectWholeTable() {
            return protectWholeTable;
        }

        public Set<String> restrictedColumns() {
            return restrictedColumns;
        }
    }
}
