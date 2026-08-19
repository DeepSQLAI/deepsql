package com.dbaagent.service;

import com.dbaagent.dto.ConnectionChatAccessPolicyResponse;
import com.dbaagent.dto.PolicyPreviewResponse;
import com.dbaagent.model.ConnectionChatAccessPolicy;
import com.dbaagent.model.ColumnMetadata;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ConnectionChatAccessPolicyService {

    private static final Pattern ONLY_SCHEMA_PATTERN = Pattern.compile(
        "(?:only|just)\\s+(?:have\\s+)?(?:access\\s+to\\s+)?(?:the\\s+)?schema\\s+([a-z_][a-z0-9_]*)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ACCESS_ONLY_SCHEMA_PATTERN = Pattern.compile(
        "access\\s+only\\s+to\\s+(?:schema\\s+)?([a-z_][a-z0-9_]*)",
        Pattern.CASE_INSENSITIVE
    );
    // Prefix-only: remainder is sliced linearly in extractConstraints so we never
    // run `.+?` + `\s+` lookaheads on untrusted policy text (ReDoS / CodeQL).
    private static final Pattern DENY_PREFIX_PATTERN = Pattern.compile(
        "(?:cannot|can't|must not|do not|don't|never) (?:query|access|see|select|read|use|return|expose) "
            + "|(?:redact|block|deny|hide) ",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ALLOW_PREFIX_PATTERN = Pattern.compile(
        "(?:but |except(?: that)? )?\\bcan (?:query|access|see|select|read|use|return) ",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DENY_STOP_PATTERN = Pattern.compile(
        " (?:but|except|however|strictly)\\b|[.;]"
    );
    private static final Pattern ALLOW_STOP_PATTERN = Pattern.compile(
        " (?:strictly)\\b|[.;]"
    );
    private static final List<TypeFamily> TYPE_FAMILIES = List.of(
        new TypeFamily("integer", Set.of("int", "integer", "bigint", "smallint", "tinyint", "serial", "bigserial", "int2", "int4", "int8")),
        new TypeFamily("float", Set.of("float", "double", "real", "numeric", "decimal", "number", "money", "float4", "float8")),
        new TypeFamily("string", Set.of("varchar", "character varying", "character", "char", "text", "clob", "uuid", "json", "jsonb", "string", "citext")),
        new TypeFamily("boolean", Set.of("bool", "boolean")),
        new TypeFamily("temporal", Set.of("date", "time", "timestamp", "timestamptz", "datetime", "interval"))
    );

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
                "deniedColumns", parsedPolicy.deniedColumns(),
                "allowedSchemas", parsedPolicy.allowedSchemas()
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
            new LinkedHashSet<>(parsedPolicy.allowedSchemas()),
            policy.isBlockMode(),
            policy.isRedactMode(),
            policy.getPlainEnglishPolicy(),
            parsedPolicy.impactedTables(),
            parsedPolicy.impactedColumns()
        );
    }

    private ParsedPolicy parsedFromPolicy(ConnectionChatAccessPolicy policy) {
        return parsePolicy(policy.getConnectionId(), policy.getPlainEnglishPolicy());
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
        Set<String> allowedSchemas = extractAllowedSchemas(normalized, schemaMetadata);
        LinkedHashSet<String> deniedTables = new LinkedHashSet<>();
        LinkedHashSet<String> deniedColumns = new LinkedHashSet<>();

        if (schemaMetadata != null) {
            for (TableMetadata table : schemaMetadata.getTables()) {
                if (!schemaInScope(table.getSchema(), allowedSchemas)) {
                    continue;
                }
                String qualifiedTable = qualifyTable(table.getSchema(), table.getName());
                if (containsWord(normalized, qualifiedTable) || containsWord(normalized, table.getName())) {
                    deniedTables.add(qualifiedTable);
                }
                if (table.getColumns() != null) {
                    table.getColumns().forEach(column -> {
                        String qualifiedColumn = qualifiedTable + "." + column.getName();
                        if (containsWord(normalized, qualifiedColumn)) {
                            deniedColumns.add(qualifiedColumn);
                        }
                    });
                }
            }
            applyColumnConstraints(normalized, allowedSchemas, schemaMetadata, deniedColumns);
        }

        Map<String, ProtectionDescriptor> descriptors = buildProtectionDescriptors(
            connectionId,
            new ArrayList<>(blockedCategories),
            new ArrayList<>(deniedTables),
            new ArrayList<>(deniedColumns),
            allowedSchemas,
            schemaMetadata
        );

        List<String> impactedTables = descriptors.values().stream()
            .filter(ProtectionDescriptor::protectWholeTable)
            .map(ProtectionDescriptor::qualifiedTableName)
            .distinct()
            .sorted()
            .toList();
        List<String> impactedColumns = descriptors.values().stream()
            .flatMap(descriptor -> descriptor.restrictedColumns().stream()
                .map(column -> descriptor.qualifiedTableName() + "." + column))
            .distinct()
            .sorted()
            .toList();

        return new ParsedPolicy(
            new ArrayList<>(blockedCategories),
            new ArrayList<>(deniedTables),
            new ArrayList<>(deniedColumns),
            new ArrayList<>(allowedSchemas),
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
        return buildProtectionDescriptors(
            connectionId,
            blockedSensitivityCategories,
            deniedTables,
            deniedColumns,
            Set.of(),
            tryScanSchema(connectionId)
        );
    }

    public Map<String, ProtectionDescriptor> buildProtectionDescriptors(
        String connectionId,
        List<String> blockedSensitivityCategories,
        List<String> deniedTables,
        List<String> deniedColumns,
        Set<String> allowedSchemas,
        SchemaMetadata schemaMetadata
    ) {
        SchemaMetadata effectiveSchema = schemaMetadata != null ? schemaMetadata : tryScanSchema(connectionId);
        Set<String> categorySet = normalizeSet(blockedSensitivityCategories);
        Set<String> deniedTableSet = normalizeSet(deniedTables);
        Set<String> deniedColumnSet = normalizeSet(deniedColumns);
        Set<String> allowedSchemaSet = normalizeSet(new ArrayList<>(allowedSchemas));

        Map<String, List<String>> schemasByBareTable = indexSchemasByBareTable(effectiveSchema);

        Map<String, ProtectionDescriptor> descriptors = new LinkedHashMap<>();
        for (TableClassification classification : tableClassificationRepository.findLatestByConnectionIdOrderByTableNameAsc(connectionId)) {
            String bareTable = classification.getTableName();
            List<String> schemas = schemasByBareTable.getOrDefault(normalizeName(bareTable), List.of(""));
            for (String schema : schemas) {
                if (!schemaInScope(schema, allowedSchemaSet)) {
                    continue;
                }
                String qualifiedTable = qualifyTable(schema, bareTable);
                ProtectionDescriptor descriptor = descriptors.computeIfAbsent(
                    normalizeName(qualifiedTable),
                    ignored -> new ProtectionDescriptor(schema, bareTable, false, new LinkedHashSet<>())
                );

                boolean tableExplicitlyDenied = deniedTableSet.contains(normalizeName(qualifiedTable))
                    || deniedTableSet.contains(normalizeName(bareTable));
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
                        String qualifiedColumn = qualifiedTable + "." + column;
                        if (categorySet.contains(normalizeName(type))
                            || deniedColumnSet.contains(normalizeName(qualifiedColumn))
                            || tableExplicitlyDenied) {
                            descriptor.restrictedColumns.add(column);
                        }
                    }
                }
            }
        }

        deniedTableSet.forEach(tableRef -> {
            String normalized = normalizeName(tableRef);
            descriptors.computeIfAbsent(normalized, key -> descriptorFromTableRef(tableRef, true)).protectWholeTable = true;
        });
        deniedColumnSet.forEach(columnRef -> {
            String[] parts = columnRef.split("\\.", 3);
            if (parts.length == 3) {
                String qualifiedTable = parts[0] + "." + parts[1];
                ProtectionDescriptor descriptor = descriptors.computeIfAbsent(
                    normalizeName(qualifiedTable),
                    key -> descriptorFromTableRef(qualifiedTable, false)
                );
                descriptor.restrictedColumns.add(parts[2]);
            } else if (parts.length == 2) {
                ProtectionDescriptor descriptor = descriptors.computeIfAbsent(
                    normalizeName(columnRef.substring(0, columnRef.lastIndexOf('.'))),
                    key -> descriptorFromTableRef(parts[0], false)
                );
                descriptor.restrictedColumns.add(parts[1]);
            }
        });
        return descriptors;
    }

    public Map<String, ProtectionDescriptor> buildProtectionDescriptors(
        ConnectionChatAccessPolicyService.EffectivePolicy policy
    ) {
        if (policy == null || !policy.protectsAnything()) {
            return Map.of();
        }
        return buildProtectionDescriptors(
            policy.connectionId(),
            new ArrayList<>(policy.blockedSensitivityCategories()),
            new ArrayList<>(policy.deniedTables()),
            new ArrayList<>(policy.deniedColumns()),
            policy.allowedSchemas(),
            null
        );
    }

    static String qualifyTable(String schema, String table) {
        if (table == null || table.isBlank()) {
            return "";
        }
        if (schema == null || schema.isBlank() || "public".equalsIgnoreCase(schema)) {
            return table;
        }
        return schema + "." + table;
    }

    private ProtectionDescriptor descriptorFromTableRef(String tableRef, boolean protectWholeTable) {
        String[] parts = tableRef.split("\\.", 2);
        if (parts.length == 2) {
            return new ProtectionDescriptor(parts[0], parts[1], protectWholeTable, new LinkedHashSet<>());
        }
        return new ProtectionDescriptor(null, tableRef, protectWholeTable, new LinkedHashSet<>());
    }

    private Map<String, List<String>> indexSchemasByBareTable(SchemaMetadata schemaMetadata) {
        Map<String, List<String>> schemasByBareTable = new LinkedHashMap<>();
        if (schemaMetadata == null || schemaMetadata.getTables() == null) {
            return schemasByBareTable;
        }
        for (TableMetadata table : schemaMetadata.getTables()) {
            schemasByBareTable
                .computeIfAbsent(normalizeName(table.getName()), ignored -> new ArrayList<>())
                .add(table.getSchema() == null ? "" : table.getSchema());
        }
        return schemasByBareTable;
    }

    private void applyColumnConstraints(
        String normalized,
        Set<String> allowedSchemas,
        SchemaMetadata schemaMetadata,
        Set<String> deniedColumns
    ) {
        Set<String> knownColumnNames = collectColumnNames(schemaMetadata, allowedSchemas);
        List<ColumnConstraint> denials = extractConstraints(normalized, DENY_PREFIX_PATTERN, DENY_STOP_PATTERN, knownColumnNames);
        List<ColumnConstraint> allowances = extractConstraints(normalized, ALLOW_PREFIX_PATTERN, ALLOW_STOP_PATTERN, knownColumnNames);
        if (denials.isEmpty()) {
            return;
        }

        for (TableMetadata table : schemaMetadata.getTables()) {
            if (!schemaInScope(table.getSchema(), allowedSchemas) || table.getColumns() == null) {
                continue;
            }
            String qualifiedTable = qualifyTable(table.getSchema(), table.getName());
            for (ColumnMetadata column : table.getColumns()) {
                boolean denied = denials.stream().anyMatch(constraint -> constraint.matches(column));
                boolean allowed = allowances.stream().anyMatch(constraint -> constraint.matches(column));
                if (denied && !allowed) {
                    deniedColumns.add(qualifiedTable + "." + column.getName());
                }
            }
        }
    }

    private List<ColumnConstraint> extractConstraints(
        String normalized,
        Pattern prefixPattern,
        Pattern stopPattern,
        Set<String> knownColumnNames
    ) {
        List<ColumnConstraint> constraints = new ArrayList<>();
        String haystack = normalized == null ? "" : normalized.replaceAll("\\s+", " ").trim();
        Matcher matcher = prefixPattern.matcher(haystack);
        while (matcher.find()) {
            String snippet = sliceUntilStop(haystack, matcher.end(), stopPattern);
            if (snippet == null || snippet.isBlank()) {
                continue;
            }
            String lowered = snippet.toLowerCase(Locale.ROOT);
            LinkedHashSet<String> typeKeys = new LinkedHashSet<>();
            for (TypeFamily family : TYPE_FAMILIES) {
                if (family.mentionedIn(lowered)) {
                    typeKeys.add(family.key());
                }
            }
            LinkedHashSet<String> nameTokens = new LinkedHashSet<>();
            knownColumnNames.stream()
                .sorted((left, right) -> Integer.compare(right.length(), left.length()))
                .filter(name -> containsWholeWord(lowered, name))
                .forEach(nameTokens::add);
            if (!typeKeys.isEmpty() || !nameTokens.isEmpty()) {
                constraints.add(new ColumnConstraint(typeKeys, nameTokens));
            }
        }
        return constraints;
    }

    private Set<String> collectColumnNames(SchemaMetadata schemaMetadata, Set<String> allowedSchemas) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (schemaMetadata == null || schemaMetadata.getTables() == null) {
            return names;
        }
        for (TableMetadata table : schemaMetadata.getTables()) {
            if (!schemaInScope(table.getSchema(), allowedSchemas) || table.getColumns() == null) {
                continue;
            }
            for (ColumnMetadata column : table.getColumns()) {
                String name = normalizeName(column.getName());
                if (!name.isBlank() && !isTypeToken(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private boolean isTypeToken(String name) {
        return TYPE_FAMILIES.stream().anyMatch(family -> family.aliases().contains(name) || family.key().equals(name));
    }

    private String sliceUntilStop(String haystack, int start, Pattern stopPattern) {
        if (start >= haystack.length()) {
            return "";
        }
        Matcher stop = stopPattern.matcher(haystack);
        if (stop.find(start)) {
            return haystack.substring(start, stop.start()).trim();
        }
        return haystack.substring(start).trim();
    }

    private boolean containsWholeWord(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isBlank()) {
            return false;
        }
        return Pattern.compile("\\b" + Pattern.quote(needle) + "\\b", Pattern.CASE_INSENSITIVE)
            .matcher(haystack)
            .find();
    }

    private record TypeFamily(String key, Set<String> aliases) {
        boolean mentionedIn(String snippet) {
            if (containsWholeWordStatic(snippet, key)) {
                return true;
            }
            return aliases.stream().anyMatch(alias -> containsWholeWordStatic(snippet, alias));
        }

        boolean matchesDataType(String dataType) {
            String normalized = dataType == null
                ? ""
                : dataType.toLowerCase(Locale.ROOT).replaceAll("\\([^)]*\\)", " ").trim();
            if (normalized.isBlank()) {
                return false;
            }
            if (containsWholeWordStatic(normalized, key) || normalized.equals(key)) {
                return true;
            }
            return aliases.stream().anyMatch(alias ->
                containsWholeWordStatic(normalized, alias) || normalized.equals(alias)
            );
        }

        private static boolean containsWholeWordStatic(String haystack, String needle) {
            if (haystack == null || needle == null || needle.isBlank()) {
                return false;
            }
            return Pattern.compile("\\b" + Pattern.quote(needle) + "\\b", Pattern.CASE_INSENSITIVE)
                .matcher(haystack)
                .find();
        }
    }

    private record ColumnConstraint(Set<String> typeKeys, Set<String> nameTokens) {
        boolean matches(ColumnMetadata column) {
            if ((typeKeys == null || typeKeys.isEmpty()) && (nameTokens == null || nameTokens.isEmpty())) {
                return false;
            }
            boolean typeOk = typeKeys == null || typeKeys.isEmpty()
                || typeKeys.stream().anyMatch(key -> TYPE_FAMILIES.stream()
                    .filter(family -> family.key().equals(key))
                    .anyMatch(family -> family.matchesDataType(column.getDataType())));
            boolean nameOk = nameTokens == null || nameTokens.isEmpty()
                || nameTokens.stream().anyMatch(token -> columnNameMatches(column.getName(), token));
            return typeOk && nameOk;
        }

        private static boolean columnNameMatches(String columnName, String token) {
            String column = columnName == null ? "" : columnName.trim().replace("\"", "").replace("`", "").toLowerCase(Locale.ROOT);
            String needle = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
            if (column.isBlank() || needle.isBlank()) {
                return false;
            }
            if (column.equals(needle)) {
                return true;
            }
            return column.startsWith(needle + "_")
                || column.endsWith("_" + needle)
                || column.contains("_" + needle + "_");
        }
    }

    private Set<String> extractAllowedSchemas(String normalized, SchemaMetadata schemaMetadata) {
        LinkedHashSet<String> schemas = new LinkedHashSet<>();
        for (Pattern pattern : List.of(ONLY_SCHEMA_PATTERN, ACCESS_ONLY_SCHEMA_PATTERN)) {
            Matcher matcher = pattern.matcher(normalized);
            while (matcher.find()) {
                schemas.add(normalizeName(matcher.group(1)));
            }
        }
        if (schemas.isEmpty() && containsAny(normalized, "cannot access any other schema", "no other schema")) {
            for (Pattern pattern : List.of(
                Pattern.compile("schema\\s+([a-z_][a-z0-9_]*)", Pattern.CASE_INSENSITIVE)
            )) {
                Matcher matcher = pattern.matcher(normalized);
                while (matcher.find()) {
                    schemas.add(normalizeName(matcher.group(1)));
                }
            }
        }
        if (schemaMetadata != null && !schemas.isEmpty()) {
            Set<String> known = new LinkedHashSet<>();
            for (TableMetadata table : schemaMetadata.getTables()) {
                if (table.getSchema() != null) {
                    known.add(normalizeName(table.getSchema()));
                }
            }
            schemas.retainAll(known);
        }
        return schemas;
    }

    private boolean schemaInScope(String schema, Set<String> allowedSchemas) {
        return isSchemaInScope(schema, allowedSchemas);
    }

    public static boolean isSchemaInScope(String schema, Set<String> allowedSchemas) {
        if (allowedSchemas == null || allowedSchemas.isEmpty()) {
            return true;
        }
        String normalized = schema == null ? "" : schema.trim().replace("\"", "").replace("`", "").toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            normalized = "public";
        }
        return allowedSchemas.contains(normalized);
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
        return value == null ? "" : value.trim().replace("\"", "").replace("`", "").toLowerCase(Locale.ROOT);
    }

    public record EffectivePolicy(
        boolean present,
        String connectionId,
        String username,
        Set<String> blockedSensitivityCategories,
        Set<String> deniedTables,
        Set<String> deniedColumns,
        Set<String> allowedSchemas,
        boolean blockMode,
        boolean redactMode,
        String plainEnglishPolicy,
        List<String> impactedTables,
        List<String> impactedColumns
    ) {
        public static EffectivePolicy none() {
            return new EffectivePolicy(false, null, null, Set.of(), Set.of(), Set.of(), Set.of(), false, false, null, List.of(), List.of());
        }

        public boolean protectsAnything() {
            return present && (
                !blockedSensitivityCategories.isEmpty()
                    || !deniedTables.isEmpty()
                    || !deniedColumns.isEmpty()
                    || !allowedSchemas.isEmpty()
            );
        }
    }

    private record ParsedPolicy(
        List<String> blockedSensitivityCategories,
        List<String> deniedTables,
        List<String> deniedColumns,
        List<String> allowedSchemas,
        List<String> impactedTables,
        List<String> impactedColumns,
        boolean blockMode,
        boolean redactMode
    ) {
    }

    public static final class ProtectionDescriptor {
        private final String schemaName;
        private final String tableName;
        private boolean protectWholeTable;
        private final Set<String> restrictedColumns;

        private ProtectionDescriptor(String schemaName, String tableName, boolean protectWholeTable, Set<String> restrictedColumns) {
            this.schemaName = schemaName;
            this.tableName = tableName;
            this.protectWholeTable = protectWholeTable;
            this.restrictedColumns = restrictedColumns;
        }

        public String schemaName() {
            return schemaName;
        }

        public String tableName() {
            return tableName;
        }

        public String qualifiedTableName() {
            return qualifyTable(schemaName, tableName);
        }

        public boolean protectWholeTable() {
            return protectWholeTable;
        }

        public Set<String> restrictedColumns() {
            return restrictedColumns;
        }
    }
}
