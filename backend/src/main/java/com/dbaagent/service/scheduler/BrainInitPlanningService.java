package com.dbaagent.service.scheduler;

import com.dbaagent.model.ConnectionInitStatus;
import com.dbaagent.model.DatabaseConnection;
import com.dbaagent.model.DocumentationSource;
import com.dbaagent.model.InitStage;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.SchemaSnapshot;
import com.dbaagent.repository.ColumnProfileRepository;
import com.dbaagent.repository.CompanyKnowledgeEntryRepository;
import com.dbaagent.repository.ConnectionInitStatusRepository;
import com.dbaagent.repository.CredentialRepository;
import com.dbaagent.repository.QueryLineageRepository;
import com.dbaagent.repository.SchemaDocumentationRepository;
import com.dbaagent.repository.SchemaSnapshotRepository;
import com.dbaagent.repository.SlowQueryHistoryRepository;
import com.dbaagent.service.RagDocumentStateService;
import com.dbaagent.service.SchemaScannerService;
import com.dbaagent.service.VectorSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class BrainInitPlanningService {

    private static final Set<String> DETERMINISTIC_RAG_TYPES = Set.of(
        "SCHEMA_DDL",
        "RELATIONSHIP",
        "COLUMN_VALUES",
        "DOCUMENTATION",
        "BUSINESS_TERM"
    );

    private final ConnectionInitStatusRepository initStatusRepository;
    private final CredentialRepository credentialRepository;
    private final SchemaSnapshotRepository schemaSnapshotRepository;
    private final ColumnProfileRepository columnProfileRepository;
    private final QueryLineageRepository queryLineageRepository;
    private final SlowQueryHistoryRepository slowQueryHistoryRepository;
    private final SchemaDocumentationRepository schemaDocumentationRepository;
    private final CompanyKnowledgeEntryRepository companyKnowledgeEntryRepository;
    private final RagDocumentStateService ragDocumentStateService;
    private final SchemaScannerService schemaScannerService;
    private final VectorSearchService vectorSearchService;
    private final ObjectMapper objectMapper;

    @Value("${brain.init.refresh.interval-hours:24}")
    private long refreshIntervalHours;

    @Value("${brain.init.profile.ttl-hours:24}")
    private long profileTtlHours;

    public BrainInitPlan planRefresh(String connectionId, boolean forceFullRebuild) {
        Optional<ConnectionInitStatus> statusOpt = initStatusRepository.findById(connectionId);
        if (statusOpt.isEmpty()) {
            return BrainInitPlan.refresh(
                InitStage.SCHEMA_SCAN,
                "No existing Brain init state was found",
                List.of("missingInit")
            );
        }

        ConnectionInitStatus status = statusOpt.get();
        if (status.getCurrentStage() != null && !status.getCurrentStage().isTerminal()) {
            return BrainInitPlan.quickVerifySkip("Brain init is already in progress", List.of());
        }

        if (forceFullRebuild) {
            return BrainInitPlan.refresh(
                InitStage.SCHEMA_SCAN,
                "Force rebuild requested",
                List.of("force")
            );
        }

        if (status.getCurrentStage() == InitStage.FAILED) {
            return BrainInitPlan.resumeFailed(inferResumeStage(status));
        }

        LocalDateTime lastRefresh = status.getCompletedAt() != null
            ? status.getCompletedAt()
            : status.getStartedAt();

        DirtyEvaluation evaluation = evaluateDirtySources(connectionId, status, lastRefresh);
        if (evaluation.startStage() == null) {
            return BrainInitPlan.quickVerifySkip(
                evaluation.reason(),
                evaluation.dirtySources()
            );
        }

        return BrainInitPlan.refresh(
            evaluation.startStage(),
            evaluation.reason(),
            evaluation.dirtySources()
        );
    }

    private DirtyEvaluation evaluateDirtySources(
        String connectionId,
        ConnectionInitStatus status,
        LocalDateTime lastRefresh
    ) {
        List<String> dirtySources = new ArrayList<>();

        boolean schemaDirty = isSchemaDirty(connectionId, status, lastRefresh);
        if (schemaDirty) {
            dirtySources.add("schemaDirty");
        }

        boolean profileDirty = !schemaDirty && isProfileDirty(connectionId, status);
        if (profileDirty) {
            dirtySources.add("profileDirty");
        }

        boolean queryEvidenceDirty = !schemaDirty && isQueryEvidenceDirty(connectionId, status);
        if (queryEvidenceDirty) {
            dirtySources.add("queryEvidenceDirty");
        }

        boolean aiDescriptionsMissing = !schemaDirty && isAiDescriptionsMissing(connectionId);
        if (aiDescriptionsMissing) {
            dirtySources.add("aiDescriptionsMissing");
        }

        boolean docsDirty = !schemaDirty && isSchemaDocsDirty(connectionId, status);
        if (docsDirty) {
            dirtySources.add("docsDirty");
        }

        boolean companyKnowledgeDirty = !schemaDirty && isCompanyKnowledgeDirty(connectionId, status);
        if (companyKnowledgeDirty) {
            dirtySources.add("companyKnowledgeDirty");
        }

        boolean vectorGap = !schemaDirty && isVectorGap(connectionId);
        if (vectorGap) {
            dirtySources.add("vectorGap");
        }

        InitStage startStage = null;
        if (schemaDirty) {
            startStage = InitStage.SCHEMA_SCAN;
        } else if (profileDirty) {
            startStage = InitStage.DATA_SAMPLING;
        } else if (queryEvidenceDirty) {
            startStage = InitStage.KEY_COLUMN_ANALYSIS;
        } else if (aiDescriptionsMissing) {
            startStage = InitStage.AI_DESCRIPTION;
        } else if (docsDirty || companyKnowledgeDirty || vectorGap) {
            startStage = InitStage.RAG_EMBEDDING;
        }

        if (startStage == null) {
            return new DirtyEvaluation(
                null,
                dirtySources,
                quickVerifyReason(lastRefresh)
            );
        }

        return new DirtyEvaluation(
            startStage,
            dirtySources,
            "Detected dirty sources: " + String.join(", ", dirtySources)
        );
    }

    private boolean isSchemaDirty(String connectionId, ConnectionInitStatus status, LocalDateTime lastRefresh) {
        Optional<SchemaSnapshot> latestSnapshot = schemaSnapshotRepository.findTopByConnectionIdOrderByCapturedAtDesc(connectionId);
        String storedFingerprint = stringDetail(status, InitStage.SCHEMA_SCAN, "schemaFingerprint");
        if (latestSnapshot.isEmpty() || storedFingerprint == null || storedFingerprint.isBlank()) {
            return true;
        }

        String snapshotFingerprint = latestSnapshot.get().getSchemaHash();
        if (snapshotFingerprint == null || snapshotFingerprint.isBlank()) {
            return true;
        }

        if (!snapshotFingerprint.equals(storedFingerprint)) {
            return true;
        }

        if (!isOlderThan(lastRefresh, refreshIntervalHours)) {
            return false;
        }

        try {
            schemaScannerService.evictSchemaCache(connectionId);
            SchemaMetadata schema = schemaScannerService.scanSchema(connectionId);
            String currentFingerprint = sha256(objectMapper.writeValueAsString(schema));
            return !storedFingerprint.equals(currentFingerprint);
        } catch (Exception e) {
            log.warn("Could not verify live schema fingerprint for {}: {}", connectionId, e.getMessage());
            return true;
        }
    }

    private boolean isProfileDirty(String connectionId, ConnectionInitStatus status) {
        DatabaseConnection connection = credentialRepository.findById(connectionId).orElse(null);
        boolean samplingEnabled = connection == null || !Boolean.FALSE.equals(connection.getEnableDataSampling());
        Boolean previousSamplingEnabled = booleanDetail(status, InitStage.DATA_SAMPLING, "samplingEnabled");
        if (previousSamplingEnabled != null && previousSamplingEnabled != samplingEnabled) {
            return true;
        }
        if (!samplingEnabled) {
            return false;
        }

        long profileCount = columnProfileRepository.countByConnectionId(connectionId);
        LocalDateTime latestProfiledAt = columnProfileRepository.findLatestProfiledAt(connectionId);
        LocalDateTime storedProfiledAt = parseDateTime(detail(status, InitStage.DATA_SAMPLING, "profiledAtWatermark"));

        if (profileCount == 0 || latestProfiledAt == null) {
            return true;
        }
        if (storedProfiledAt == null) {
            return true;
        }
        if (latestProfiledAt.isAfter(storedProfiledAt)) {
            return true;
        }

        return isOlderThan(latestProfiledAt, profileTtlHours);
    }

    private boolean isQueryEvidenceDirty(String connectionId, ConnectionInitStatus status) {
        LocalDateTime latestEvidence = latest(
            queryLineageRepository.findLatestCreatedAt(connectionId),
            slowQueryHistoryRepository.findLatestCreatedAt(connectionId)
        );
        if (latestEvidence == null) {
            return false;
        }

        LocalDateTime storedEvidence = parseDateTime(detail(status, InitStage.KEY_COLUMN_ANALYSIS, "queryEvidenceWatermark"));
        return storedEvidence == null || latestEvidence.isAfter(storedEvidence);
    }

    private boolean isSchemaDocsDirty(String connectionId, ConnectionInitStatus status) {
        LocalDateTime latestDocs = schemaDocumentationRepository.findLatestTouchedAt(connectionId);
        if (latestDocs == null) {
            return false;
        }

        LocalDateTime stored = parseDateTime(detail(status, InitStage.RAG_EMBEDDING, "schemaDocsWatermark"));
        return stored == null || latestDocs.isAfter(stored);
    }

    private boolean isAiDescriptionsMissing(String connectionId) {
        long aiDocCount = schemaDocumentationRepository.countByConnectionIdAndSource(
            connectionId, DocumentationSource.AI_GENERATED);
        if (aiDocCount > 0) {
            return false;
        }
        // Only trigger if there are tables to describe (schema has been scanned)
        Optional<SchemaSnapshot> latestSnapshot = schemaSnapshotRepository
            .findTopByConnectionIdOrderByCapturedAtDesc(connectionId);
        return latestSnapshot.isPresent()
            && latestSnapshot.get().getTableCount() != null
            && latestSnapshot.get().getTableCount() > 0;
    }

    private boolean isCompanyKnowledgeDirty(String connectionId, ConnectionInitStatus status) {
        LocalDateTime latestKnowledge = companyKnowledgeEntryRepository.findLatestTouchedAt(connectionId);
        if (latestKnowledge == null) {
            return false;
        }

        LocalDateTime stored = parseDateTime(detail(status, InitStage.RAG_EMBEDDING, "companyKnowledgeWatermark"));
        return stored == null || latestKnowledge.isAfter(stored);
    }

    private boolean isVectorGap(String connectionId) {
        if (!(vectorSearchService instanceof com.dbaagent.service.PgVectorSearchService)
            || !vectorSearchService.isEnabled()) {
            return false;
        }

        Map<String, Long> totalCounts = ragDocumentStateService.getTypeCounts(connectionId);
        Map<String, Long> embeddedCounts = ragDocumentStateService.getEmbeddedTypeCounts(connectionId);

        for (String type : DETERMINISTIC_RAG_TYPES) {
            long total = totalCounts.getOrDefault(type, 0L);
            long embedded = embeddedCounts.getOrDefault(type, 0L);
            if (total > 0 && embedded < total) {
                return true;
            }
        }

        Optional<SchemaSnapshot> latestSnapshot = schemaSnapshotRepository.findTopByConnectionIdOrderByCapturedAtDesc(connectionId);
        if (latestSnapshot.isPresent() && latestSnapshot.get().getTableCount() != null
            && latestSnapshot.get().getTableCount() > 0
            && totalCounts.getOrDefault("SCHEMA_DDL", 0L) == 0L) {
            return true;
        }

        long schemaDocCount = schemaDocumentationRepository.countByConnectionId(connectionId);
        if (schemaDocCount > 0 && totalCounts.getOrDefault("DOCUMENTATION", 0L) < schemaDocCount) {
            return true;
        }

        long businessTermCount = schemaDocumentationRepository.countWithBusinessTerms(connectionId);
        return businessTermCount > 0
            && totalCounts.getOrDefault("BUSINESS_TERM", 0L) < businessTermCount;
    }

    private InitStage inferResumeStage(ConnectionInitStatus status) {
        if (status.getStageTimings() == null || status.getStageTimings().isEmpty()) {
            return InitStage.SCHEMA_SCAN;
        }

        return status.getStageTimings().entrySet().stream()
            .map(entry -> {
                try {
                    InitStage stage = InitStage.valueOf(entry.getKey());
                    if (stage.isTerminal()) {
                        return null;
                    }
                    String startedAt = entry.getValue() != null ? entry.getValue().startedAt() : null;
                    if (startedAt == null || startedAt.isBlank()) {
                        return null;
                    }
                    return Map.entry(stage, Instant.parse(startedAt));
                } catch (Exception e) {
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .max(Comparator.comparing(Map.Entry::getValue))
            .map(Map.Entry::getKey)
            .orElse(InitStage.SCHEMA_SCAN);
    }

    private String quickVerifyReason(LocalDateTime lastRefresh) {
        if (lastRefresh != null && !isOlderThan(lastRefresh, refreshIntervalHours)) {
            return "Quick verify found no dirty sources and the last Brain init is still fresh";
        }
        return "Quick verify found no dirty sources";
    }

    private boolean isOlderThan(LocalDateTime value, long hours) {
        if (value == null) {
            return true;
        }
        long safeHours = Math.max(1, hours);
        return value.isBefore(LocalDateTime.now().minusHours(safeHours));
    }

    private LocalDateTime latest(LocalDateTime left, LocalDateTime right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isAfter(right) ? left : right;
    }

    private Object detail(ConnectionInitStatus status, InitStage stage, String key) {
        Map<String, Object> details = status.getStageDetails() != null
            ? status.getStageDetails().get(stage.name())
            : null;
        return details != null ? details.get(key) : null;
    }

    private String stringDetail(ConnectionInitStatus status, InitStage stage, String key) {
        Object value = detail(status, stage, key);
        return value != null ? String.valueOf(value) : null;
    }

    private Boolean booleanDetail(ConnectionInitStatus status, InitStage stage, String key) {
        Object value = detail(status, stage, key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private LocalDateTime parseDateTime(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text);
        } catch (Exception ignored) {
        }
        try {
            return OffsetDateTime.parse(text).toLocalDateTime();
        } catch (Exception ignored) {
        }
        try {
            return Instant.parse(text).atOffset(ZoneOffset.UTC).toLocalDateTime();
        } catch (Exception ignored) {
        }
        return null;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash Brain init fingerprint", e);
        }
    }

    private record DirtyEvaluation(
        InitStage startStage,
        List<String> dirtySources,
        String reason
    ) {
    }
}
