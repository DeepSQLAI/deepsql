package com.dbaagent.service;

import com.dbaagent.dto.SlowQueryHistorySummary;
import com.dbaagent.dto.SlowQueryInsightsResponse;
import com.dbaagent.model.CapacityForecast;
import com.dbaagent.model.ColumnInfo;
import com.dbaagent.model.ConnectionAccessGrant;
import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.DatabaseEvent;
import com.dbaagent.model.DatabaseObject;
import com.dbaagent.model.GrowthAnomaly;
import com.dbaagent.model.IndexRecommendationEntity;
import com.dbaagent.model.IndexRecommendationEvidence;
import com.dbaagent.model.LockContention;
import com.dbaagent.model.PerformanceAction;
import com.dbaagent.model.PerformanceSnapshot;
import com.dbaagent.model.QueryFingerprint;
import com.dbaagent.util.QueryLabeler;
import com.dbaagent.model.QueryRequest;
import com.dbaagent.model.QueryResult;
import com.dbaagent.model.SchemaChange;
import com.dbaagent.model.SchemaSnapshot;
import com.dbaagent.model.SlackChannelBinding;
import com.dbaagent.model.SlackDigestLog;
import com.dbaagent.model.SlowQuery;
import com.dbaagent.model.SlowQueryAnalysis;
import com.dbaagent.model.TableStatsHistory;
import com.dbaagent.repository.AuthLoginChallengeRepository;
import com.dbaagent.repository.CapacityForecastRepository;
import com.dbaagent.repository.ConnectionAccessGrantRepository;
import com.dbaagent.repository.DatabaseEventRepository;
import com.dbaagent.repository.GrowthAnomalyRepository;
import com.dbaagent.repository.LockContentionRepository;
import com.dbaagent.repository.QueryFingerprintRepository;
import com.dbaagent.repository.SchemaChangeRepository;
import com.dbaagent.repository.SlackChannelBindingRepository;
import com.dbaagent.repository.SlackDigestLogRepository;
import com.dbaagent.repository.TableStatsHistoryRepository;
import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;

@Service
@RequiredArgsConstructor
@Slf4j
public class SlackDailyDigestService {

    /**
     * When true (default), the daily digest is delivered only to Slack channel
     * bindings owned by a DeepSQL admin. A binding is created per channel/DM when
     * someone picks a connection, so without this filter every linked user gets
     * the digest DM'd. Set false to restore broadcast-to-all delivery.
     */
    @Value("${slack.digest.admins-only:true}")
    private boolean digestAdminsOnly;

    private final SlackRuntimeSettingsService slackRuntimeSettingsService;
    private final SlackChannelBindingRepository channelBindingRepository;
    private final CredentialService credentialService;
    private final ConnectionService connectionService;
    private final PerformanceInsightsService performanceInsightsService;
    private final SlowQueryService slowQueryService;
    private final SlowQueryHistoryService slowQueryHistoryService;
    private final SlowQueryInsightsService slowQueryInsightsService;
    private final SlowQueryAnalyticsService slowQueryAnalyticsService;
    private final PerformanceActionAggregatorService actionAggregatorService;
    private final EnhancedSqlParserService sqlParserService;
    private final QueryExecutorService queryExecutorService;
    private final TableGrowthMonitoringService tableGrowthMonitoringService;
    private final SchemaChangeTrackingService schemaChangeTrackingService;
    private final TableStatsHistoryRepository tableStatsHistoryRepository;
    private final GrowthAnomalyRepository growthAnomalyRepository;
    private final CapacityForecastRepository capacityForecastRepository;
    private final SchemaChangeRepository schemaChangeRepository;
    private final SlackDigestLogRepository digestLogRepository;
    private final SlackUserLinkService slackUserLinkService;
    private final LockContentionRepository lockContentionRepository;
    private final QueryFingerprintRepository queryFingerprintRepository;
    private final DatabaseEventRepository databaseEventRepository;
    private final ConnectionAccessGrantRepository connectionAccessGrantRepository;
    private final AuthLoginChallengeRepository authLoginChallengeRepository;
    private final IndexAdvisorService indexAdvisorService;
    private final IndexRecommendationService indexRecommendationService;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy");
    private static final int TOP_TABLES = 5;
    private static final int TOP_SLOW_QUERIES = 3;
    private static final List<String> DIGEST_TITLES = List.of(
        "🗄️ DB Health Briefing",
        "🗄️ Database Situation Report",
        "🗄️ Engineering DB Readout"
    );
    private static final List<String> SUMMARY_TITLES = List.of(
        "🧭 EXECUTIVE READ",
        "🧭 WHAT CHANGED",
        "🧭 FIRST TAKE"
    );
    private static final List<String> SPOTLIGHT_TITLES = List.of(
        "🔦 TODAY'S SPOTLIGHT",
        "🔦 LEAD SIGNAL",
        "🔦 WHAT DESERVES ATTENTION"
    );
    private static final List<String> SLOW_TITLES = List.of(
        "🐢 SLOW QUERY SNAPSHOT",
        "🐢 QUERY PRESSURE BOARD",
        "🐢 WORKLOAD RISK CHECK"
    );
    private static final List<String> CUSTOMERS_TITLES = List.of(
        "👥 TOP AFFECTED CUSTOMERS",
        "👥 CUSTOMERS BEARING THE LOAD",
        "👥 SLOWNESS BY CUSTOMER"
    );
    private static final List<String> FREE_WIN_TITLES = List.of(
        "🎯 FREE WIN",
        "🎯 LOW-EFFORT LEVER",
        "🎯 HIGHEST-ROI MOVE"
    );
    private static final List<String> GROWTH_TITLES = List.of(
        "📈 GROWTH WATCH",
        "📈 CAPACITY WATCH",
        "📈 FOOTPRINT WATCH"
    );
    private static final List<String> SCHEMA_TITLES = List.of(
        "🔧 SCHEMA EVENTS (last 24h)",
        "🔧 SCHEMA DELTAS (last 24h)",
        "🔧 STRUCTURE CHANGES (last 24h)"
    );
    private static final List<String> SECURITY_TITLES = List.of(
        "🛡️ SECURITY & PRIVILEGED ACTIONS (last 24h)",
        "🛡️ ACCESS WATCH (last 24h)",
        "🛡️ TRUST BOUNDARY (last 24h)"
    );
    private static final List<String> CONCURRENCY_TITLES = List.of(
        "🔒 CONCURRENCY HOTSPOTS (last 24h)",
        "🔒 LOCK & WAIT REPORT (last 24h)",
        "🔒 BLOCKING ACTIVITY (last 24h)"
    );
    private static final List<String> WASTE_TITLES = List.of(
        "💸 SILENT WASTE",
        "💸 RECLAIMABLE STORAGE",
        "💸 HIDDEN COSTS"
    );
    private static final List<String> NEWCOMER_TITLES = List.of(
        "🆕 NEWCOMER QUERIES (last 24h)",
        "🆕 NEW HEAVY HITTERS",
        "🆕 RISING WORKLOAD"
    );
    private static final List<String> SAVINGS_TITLES = List.of(
        "💰 POTENTIAL SAVINGS",
        "💰 MONEY ON THE TABLE",
        "💰 COST RECOVERY OPPORTUNITY"
    );
    private static final List<String> INDEX_WINS_TITLES = List.of(
        "📈 INDEX WINS",
        "📈 WORKLOAD-WEIGHTED INDEX ADVISOR",
        "📈 INDEXES TO ADD (RANKED BY ROI)"
    );
    private static final List<String> DEEP_DIVE_TITLES = List.of(
        "🔎 TODAY'S DEEP DIVE",
        "🔎 ZOOM IN",
        "🔎 ONE THING WORTH KNOWING"
    );
    private static final List<String> FOOTERS = List.of(
        "Powered by DeepSQL · reply with any question about your DB",
        "Powered by DeepSQL · ask follow-up questions right here",
        "Powered by DeepSQL · use this thread to dig deeper"
    );
    // "savings" leads every rotation — money is the universal hook. "deep_dive" is a rotating
    // angle picked daily so the digest doesn't read the same two days in a row. The rest of the
    // rotation preserves prior section ordering so the macro briefing keeps its primacy.
    // "customers" sits right after "slow" in every rotation — they're the
    // same data sliced along a different axis (queries × customers instead
    // of queries × time), so they read naturally adjacent.
    private static final List<String> SECTION_ORDER_A = List.of(
        "savings", "index_wins", "summary", "spotlight", "slow", "customers", "concurrency", "newcomers", "free", "growth", "waste", "schema", "security", "deep_dive");
    private static final List<String> SECTION_ORDER_B = List.of(
        "savings", "index_wins", "summary", "spotlight", "concurrency", "growth", "slow", "customers", "deep_dive", "newcomers", "schema", "security", "free", "waste");
    private static final List<String> SECTION_ORDER_C = List.of(
        "savings", "index_wins", "summary", "slow", "customers", "deep_dive", "spotlight", "newcomers", "concurrency", "schema", "security", "growth", "waste", "free");
    private static final String CONNECTION_UNAVAILABLE_HEADLINE = "live database access unavailable";
    private static final List<String> LABEL_COLUMN_CANDIDATES = List.of(
        "name", "title", "display_name", "displayname", "label", "full_name", "hotel_name",
        "shop_name", "property_name", "account_name", "customer_name", "slug", "code"
    );

    private record DigestStyle(
        String digestTitle,
        String summaryTitle,
        String spotlightTitle,
        String slowTitle,
        String customersTitle,
        String freeWinTitle,
        String growthTitle,
        String schemaTitle,
        String securityTitle,
        String concurrencyTitle,
        String wasteTitle,
        String newcomerTitle,
        String savingsTitle,
        String indexWinsTitle,
        String deepDiveTitle,
        String footer,
        List<String> order,
        long digestCount
    ) {}

    private record GrowthDigestData(
        List<TableStatsHistory> latestSnapshots,
        List<TableStatsHistory> topGrowing,
        Map<String, CapacityForecast> forecastByTable,
        Map<String, GrowthAnomaly> anomalyByTable,
        boolean bootstrapped
    ) {}

    private record SchemaDigestData(
        List<SchemaChange> changes,
        boolean comparedFromSnapshots,
        @Nullable LocalDateTime currentSnapshotAt,
        @Nullable LocalDateTime previousSnapshotAt
    ) {}

    public record TriggerResult(boolean triggered, String message) {}

    /** Runs the digest in a virtual thread so the HTTP trigger returns immediately. */
    public void sendDailyDigestAsync() {
        Thread.ofVirtual().name("digest-send").start(this::sendDailyDigest);
    }

    public TriggerResult triggerDigest() {
        List<String> connectionIds = digestConnectionIds();
        if (connectionIds.isEmpty()) {
            return new TriggerResult(false, "No database connections available for digest generation.");
        }

        boolean slackEnabled = isSlackDeliveryEnabled();
        long bindingCount = channelBindingRepository.count();
        if (!slackEnabled) {
            sendDailyDigestAsync();
            return new TriggerResult(true, "Digest generation started. Slack delivery is disabled in this environment.");
        }
        if (bindingCount == 0) {
            sendDailyDigestAsync();
            return new TriggerResult(true, "Digest generation started. No Slack channel bindings found, so it will only appear in the app.");
        }

        sendDailyDigestAsync();
        return new TriggerResult(true, "Digest generation and Slack delivery are running in the background.");
    }

    public void sendDailyDigest() {
        List<String> connectionIds = digestConnectionIds();
        if (connectionIds.isEmpty()) {
            log.info("No connections available — skipping daily digest generation");
            return;
        }

        List<SlackChannelBinding> bindings = channelBindingRepository.findAll();
        Map<String, List<SlackChannelBinding>> bindingsByConnection = bindings.stream()
            .filter(binding -> binding.getDefaultConnectionId() != null && !binding.getDefaultConnectionId().isBlank())
            .collect(Collectors.groupingBy(SlackChannelBinding::getDefaultConnectionId));
        boolean slackEnabled = isSlackDeliveryEnabled();

        log.info(
            "Generating daily digest for {} connection(s); Slack delivery enabled={}, bound channels={}",
            connectionIds.size(),
            slackEnabled,
            bindings.size()
        );

        for (String connectionId : connectionIds) {
            SlackDigestLog logEntry = new SlackDigestLog();
            logEntry.setConnectionId(connectionId);
            logEntry.setConnectionName(connectionName(connectionId));
            logEntry.setChannelId(null);
            logEntry.setSentAt(LocalDateTime.now());
            try {
                String message = buildRichDigest(connectionId);
                logEntry.setContent(message);
                logEntry.setHeadline(extractHeadline(message));
                List<SlackChannelBinding> allBindings = bindingsByConnection.getOrDefault(connectionId, List.of());
                List<SlackChannelBinding> connectionBindings = filterAdminRecipients(allBindings);
                if (digestAdminsOnly && connectionBindings.size() < allBindings.size()) {
                    log.info("Digest for connection {}: delivering to {}/{} admin-owned binding(s) (admins-only filter)",
                        connectionId, connectionBindings.size(), allBindings.size());
                }
                if (!slackEnabled || connectionBindings.isEmpty()) {
                    logEntry.setStatus("GENERATED");
                    if (!slackEnabled) {
                        logEntry.setErrorMessage("Slack delivery disabled");
                    } else {
                        logEntry.setErrorMessage("No Slack channel bindings");
                    }
                    log.info(
                        "Digest generated for connection {} without Slack delivery (enabled={}, bindings={})",
                        connectionId,
                        slackEnabled,
                        connectionBindings.size()
                    );
                } else {
                    List<String> failures = sendToBoundChannels(connectionBindings, message);
                    if (failures.isEmpty()) {
                        logEntry.setStatus("SENT");
                        log.info(
                            "Daily digest generated and sent to {} channel(s) for connection {}",
                            connectionBindings.size(),
                            connectionId
                        );
                    } else if (failures.size() < connectionBindings.size()) {
                        logEntry.setStatus("PARTIAL");
                        logEntry.setErrorMessage(String.join(" | ", failures));
                    } else {
                        logEntry.setStatus("FAILED");
                        logEntry.setErrorMessage(String.join(" | ", failures));
                    }
                }
            } catch (Exception e) {
                logEntry.setStatus("FAILED");
                logEntry.setErrorMessage(e.getMessage());
                log.error("Failed to generate daily digest for connection {}: {}",
                    connectionId, e.getMessage(), e);
            } finally {
                try { digestLogRepository.save(logEntry); } catch (Exception ex) {
                    log.warn("Could not persist digest log: {}", ex.getMessage());
                }
            }
        }
    }

    private boolean isSlackDeliveryEnabled() {
        SlackRuntimeSettingsService.SlackRuntimeConfig config = slackRuntimeSettingsService.current();
        return config.enabled() && config.botToken() != null && !config.botToken().isBlank();
    }

    private List<String> digestConnectionIds() {
        Set<String> ids = new HashSet<>();
        credentialService.getAllConnections().stream()
            .map(c -> c.getId())
            .filter(id -> id != null && !id.isBlank())
            .forEach(ids::add);
        channelBindingRepository.findAll().stream()
            .map(SlackChannelBinding::getDefaultConnectionId)
            .filter(id -> id != null && !id.isBlank())
            .forEach(ids::add);
        return ids.stream().sorted().toList();
    }

    /**
     * Keep only bindings owned by a DeepSQL admin when {@code slack.digest.admins-only}
     * is on. The binding's {@code updatedBy} is the DeepSQL username that bound the
     * channel/DM; a non-admin or unknown owner is excluded so the digest isn't
     * broadcast to every linked user.
     */
    private List<SlackChannelBinding> filterAdminRecipients(List<SlackChannelBinding> bindings) {
        if (!digestAdminsOnly) {
            return bindings;
        }
        return bindings.stream().filter(this::isAdminOwned).toList();
    }

    private boolean isAdminOwned(SlackChannelBinding binding) {
        // updated_by is the Slack user id that bound the channel/DM — resolve it to
        // the linked DeepSQL user and check their role.
        String slackUserId = binding.getUpdatedBy();
        if (slackUserId == null || slackUserId.isBlank()) {
            return false;
        }
        return slackUserLinkService.resolveLinkedUser(binding.getTeamId(), slackUserId)
            .map(SlackUserLinkService.LinkedUser::admin)
            .orElse(false);
    }

    private List<String> sendToBoundChannels(List<SlackChannelBinding> bindings, String message) {
        return bindings.stream()
            .map(binding -> {
                try {
                    postMessage(binding.getChannelId(), message);
                    return null;
                } catch (Exception e) {
                    log.error("Failed to send daily digest to channel {}: {}",
                        binding.getChannelId(), e.getMessage(), e);
                    return binding.getChannelId() + ": " + e.getMessage();
                }
            })
            .filter(error -> error != null && !error.isBlank())
            .toList();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main builder
    // ─────────────────────────────────────────────────────────────────────────

    private String buildRichDigest(String connectionId) {
        String connName = connectionName(connectionId);
        DigestStyle style = chooseStyle(connectionId);
        if (!canAccessConnection(connectionId)) {
            return buildConnectionUnavailableDigest(connName, style);
        }
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        StringBuilder sb = new StringBuilder();

        // Gather insights once (reused across sections)
        SlowQueryInsightsResponse insights = fetchInsights(connectionId);
        SlowQueryAnalysis analysis = fetchSlowQueryAnalysis(connectionId);
        List<SlowQueryHistorySummary> recentHistory = fetchHistory(connectionId);
        SlackDigestLog previousDigest = digestLogRepository
            .findTopByConnectionIdAndChannelIdIsNullOrderBySentAtDesc(connectionId)
            .orElse(null);
        GrowthDigestData growthData = loadGrowthDigestData(connectionId, since);
        SchemaDigestData schemaData = loadSchemaDigestData(connectionId, since);

        // Header
        String headline = pickHeadline(insights, analysis, growthData, schemaData, previousDigest);
        sb.append("*").append(style.digestTitle()).append(" — ").append(connName).append("*\n");
        sb.append("_").append(LocalDateTime.now().format(DATE_FMT)).append(" · ").append(headline).append("_\n");
        sb.append("────────────────────────\n\n");
        for (String section : style.order()) {
            switch (section) {
                case "summary" -> appendExecutiveSummary(sb, style, analysis, recentHistory, growthData, schemaData, previousDigest);
                case "spotlight" -> appendSpotlight(sb, style, insights, analysis, recentHistory, connectionId);
                case "slow" -> appendSlowQuerySection(sb, style, connectionId, analysis, insights, recentHistory);
                case "customers" -> appendTopCustomersSection(sb, style, connectionId);
                case "free" -> appendFreeWin(sb, style, connectionId);
                case "growth" -> appendGrowthSection(sb, style, growthData);
                case "schema" -> appendSchemaEvents(sb, style, schemaData);
                case "security" -> appendSecuritySection(sb, style, connectionId, since);
                case "concurrency" -> appendConcurrencySection(sb, style, connectionId, since);
                case "waste" -> appendSilentWasteSection(sb, style, connectionId, growthData);
                case "newcomers" -> appendNewcomersSection(sb, style, connectionId, since);
                case "savings" -> appendPotentialSavingsSection(sb, style, connectionId, growthData, analysis, since);
                case "index_wins" -> appendIndexWinsSection(sb, style, connectionId);
                case "deep_dive" -> appendDeepDiveSection(sb, style, analysis, growthData);
                default -> { }
            }
        }

        sb.append("\n_").append(style.footer()).append("_");
        return sb.toString();
    }

    private boolean canAccessConnection(String connectionId) {
        try {
            ConnectionRequest request = credentialService.getDecryptedConnection(connectionId);
            return request != null && connectionService.testConnection(request);
        } catch (Exception e) {
            log.warn("Digest connection preflight failed for {}: {}", connectionId, e.getMessage());
            return false;
        }
    }

    private String buildConnectionUnavailableDigest(String connectionName, DigestStyle style) {
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(style.digestTitle()).append(" — ").append(connectionName).append("*\n");
        sb.append("_").append(LocalDateTime.now().format(DATE_FMT)).append(" · ").append(CONNECTION_UNAVAILABLE_HEADLINE).append("_\n");
        sb.append("────────────────────────\n\n");
        sb.append("*🚫 LIVE ACCESS*\n");
        sb.append("  DeepSQL could not reach this database during the digest run.\n");
        sb.append("  No fresh slow-query, schema-delta, or growth updates were published to avoid sending stale information.\n");
        sb.append("  Please reach out to your admin to verify the connection credentials and connectivity.\n\n");
        sb.append("_").append(style.footer()).append("_");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Headline — one-sentence story for today
    // ─────────────────────────────────────────────────────────────────────────

    private String pickHeadline(SlowQueryInsightsResponse insights,
                                SlowQueryAnalysis analysis,
                                GrowthDigestData growthData,
                                SchemaDigestData schemaData,
                                @Nullable SlackDigestLog previousDigest) {
        List<String> candidates = new ArrayList<>();
        try {
            // Plan drift → most urgent story
            if (insights != null) {
                SlowQueryInsightsResponse.PlanDriftInsights pd = insights.getPlanDrift();
                if (pd != null && pd.getCriticalRegressions() > 0) {
                    candidates.add(pd.getCriticalRegressions() + " query plan regression(s) detected — performance degraded");
                }
                // Tail risk
                SlowQueryInsightsResponse.TailRiskInsights tr = insights.getTailRisk();
                if (tr != null && tr.getTailRisks() != null && !tr.getTailRisks().isEmpty()) {
                    SlowQueryInsightsResponse.TailRiskItem worst = tr.getTailRisks().get(0);
                    if (worst.getTailRatio() >= 20) {
                        candidates.add(String.format("variance bomb detected — %.0f× gap between P50 and P99", worst.getTailRatio()));
                    }
                }
                // Skew / blast radius
                SlowQueryInsightsResponse.SkewInsights skew = insights.getSkew();
                if (skew != null && skew.getItems() != null && !skew.getItems().isEmpty()) {
                    SlowQueryInsightsResponse.SkewItem top = skew.getItems().get(0);
                    if (top.getDominancePct() >= 20) {
                        candidates.add(String.format("column `%s.%s` driving %.0f%% of slow query load",
                            top.getTableName(), top.getColumnName(), top.getDominancePct()));
                    }
                }
            }
            if (!schemaData.changes().isEmpty()) {
                long breaking = schemaData.changes().stream().filter(change -> Boolean.TRUE.equals(change.getIsBreakingChange())).count();
                if (breaking > 0) {
                    candidates.add(breaking + " breaking schema change(s) detected in the latest snapshot delta");
                } else {
                    candidates.add(schemaData.changes().size() + " schema change(s) landed since the last snapshot");
                }
            }
            if (!growthData.topGrowing().isEmpty()) {
                TableStatsHistory leader = growthData.topGrowing().getFirst();
                candidates.add(String.format(
                    "table `%s` expanded by %s in the latest growth sample",
                    leader.getTableName(),
                    formatBytes(leader.getSizeGrowthBytes() != null ? leader.getSizeGrowthBytes() : 0L)
                ));
            } else if (growthData.bootstrapped() && !growthData.latestSnapshots().isEmpty()) {
                candidates.add("growth tracking baseline established for " + growthData.latestSnapshots().size() + " table(s)");
            }
            // Critical slow queries
            if (analysis != null && analysis.getTotalSlowQueries() != null && analysis.getTotalSlowQueries() > 0) {
                long critical = analysis.getCountBySeverity(SlowQuery.Severity.CRITICAL);
                if (critical > 0)
                    candidates.add(critical + " critical slow quer" + (critical == 1 ? "y" : "ies") + " in the last 24h");
                candidates.add(analysis.getTotalSlowQueries() + " slow queries · health: " +
                    (analysis.getOverallHealth() != null ? analysis.getOverallHealth() : "UNKNOWN"));
            }
        } catch (Exception e) {
            log.debug("Headline generation failed: {}", e.getMessage());
        }
        String previousHeadline = previousDigest != null ? previousDigest.getHeadline() : null;
        return candidates.stream()
            .filter(candidate -> candidate != null && !candidate.isBlank())
            .filter(candidate -> previousHeadline == null || !candidate.equalsIgnoreCase(previousHeadline))
            .findFirst()
            .orElseGet(() -> candidates.stream()
                .filter(candidate -> candidate != null && !candidate.isBlank())
                .findFirst()
                .orElse("all systems nominal"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Spotlight — the single most interesting signal today
    // ─────────────────────────────────────────────────────────────────────────

    private void appendSpotlight(StringBuilder sb,
                                 DigestStyle style,
                                 SlowQueryInsightsResponse insights,
                                 SlowQueryAnalysis analysis,
                                 List<SlowQueryHistorySummary> history,
                                 String connectionId) {
        sb.append("*").append(style.spotlightTitle()).append("*\n");
        try {
            // Priority 1: plan drift regression
            if (insights != null) {
                SlowQueryInsightsResponse.PlanDriftInsights pd = insights.getPlanDrift();
                if (pd != null && pd.getItems() != null && !pd.getItems().isEmpty()) {
                    SlowQueryInsightsResponse.PlanDriftItem item = pd.getItems().get(0);
                    // Was: truncate(item.getQueryPreview(), 60) — produced
                    // partial SQL like "SELECT b.id, b.user_email, b.checkin," that
                    // cut off mid-clause and was unreadable in Slack. Switch to a
                    // human-readable label ("Booking lookup by hotel_id") + the
                    // 8-char query ID so the user can look up the full text in the
                    // CLI / UI.
                    sb.append(String.format(
                        "  ⚠️ Query plan changed: *%s* `(id: %s)`\n",
                        labelForDigest(item.getQueryPreview()),
                        shortId(item.getQueryId())));
                    if (item.getRuntimeRegressionPct() > 0)
                        sb.append(String.format("  Runtime regressed *%.0f%%* · %d plan change(s) detected\n",
                            item.getRuntimeRegressionPct(), pd.getTotalPlanDriftQueries()));
                    sb.append("\n");
                    return;
                }

                // Priority 2: tail risk variance bomb
                SlowQueryInsightsResponse.TailRiskInsights tr = insights.getTailRisk();
                if (tr != null && tr.getTailRisks() != null && !tr.getTailRisks().isEmpty()) {
                    SlowQueryInsightsResponse.TailRiskItem risk = tr.getTailRisks().get(0);
                    if (risk.getTailRatio() >= 10) {
                        // Same readability fix as above — human-readable
                        // label + queryId instead of a truncated SQL prefix.
                        sb.append(String.format(
                            "  💣 Variance bomb: *%s* `(id: %s)`\n",
                            labelForDigest(risk.getQueryPreview()),
                            shortId(risk.getQueryId())));
                        sb.append(String.format(
                            "  P50: *%.0f ms* → P99: *%.0f ms* (%.0f× gap) — unpredictable customer impact\n",
                            risk.getP50Ms(), risk.getP99Ms(), risk.getTailRatio()));
                        sb.append("\n");
                        return;
                    }
                }

                // Priority 3: skew / blast radius
                SlowQueryInsightsResponse.SkewInsights skew = insights.getSkew();
                if (skew != null && skew.getItems() != null && !skew.getItems().isEmpty()) {
                    SlowQueryInsightsResponse.SkewItem top = skew.getItems().get(0);
                    if (top.getDominancePct() >= 15) {
                        sb.append(String.format(
                            "  🎯 `%s.%s = %s` accounts for *%.0f%%* of slow query load\n",
                            top.getTableName(), top.getColumnName(),
                            truncate(top.getDisplayValue(), 20), top.getDominancePct()));
                        sb.append(String.format(
                            "  %d affected queries · risk: *%s*\n",
                            top.getSlowQueryCount(), top.getRiskLevel()));
                        sb.append("\n");
                        return;
                    }
                }
            }

            // Priority 4: new heavy hitter since yesterday
            if (analysis != null && history != null && history.size() >= 2) {
                long todayTotal = analysis.getTotalSlowQueries() != null ? analysis.getTotalSlowQueries() : 0;
                long yesterdayTotal = history.get(1).getTotalSlowQueries() != null ? history.get(1).getTotalSlowQueries() : 0;
                if (yesterdayTotal > 0 && todayTotal > yesterdayTotal * 1.5) {
                    long delta = todayTotal - yesterdayTotal;
                    sb.append(String.format(
                        "  📈 Slow query count jumped *+%d* vs yesterday (%d → %d)\n",
                        delta, yesterdayTotal, todayTotal));
                    sb.append("\n");
                    return;
                }
            }

            // Priority 5: capacity countdown
            try {
                List<CapacityForecast> forecasts = capacityForecastRepository
                    .findByConnectionIdOrderByForecastDateDesc(connectionId);
                if (!forecasts.isEmpty()) {
                    CapacityForecast fc = forecasts.get(0);
                    if (fc.getStorageExhaustionDate() != null) {
                        long daysLeft = java.time.temporal.ChronoUnit.DAYS.between(
                            LocalDateTime.now(), fc.getStorageExhaustionDate());
                        if (daysLeft > 0 && daysLeft <= 30) {
                            sb.append(String.format(
                                "  🚨 Storage exhaustion forecast: *%d days* at current growth rate\n", daysLeft));
                            if (fc.getDailyStorageGrowthRate() != null)
                                sb.append(String.format("  Growing *%.1f GB/day*\n",
                                    fc.getDailyStorageGrowthRate() / 1_073_741_824.0));
                            sb.append("\n");
                            return;
                        }
                    }
                }
            } catch (Exception ignored) {}

            // Fallback: no dramatic signal
            sb.append("  ✅ No critical signals today — DB is behaving as expected\n\n");

        } catch (Exception e) {
            log.warn("Spotlight section failed for {}: {}", connectionId, e.getMessage());
            sb.append("  _(signal data unavailable)_\n\n");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Slow query snapshot — enriched with delta, customer blast radius, flags
    // ─────────────────────────────────────────────────────────────────────────

    private void appendSlowQuerySection(StringBuilder sb,
                                        DigestStyle style,
                                        String connectionId,
                                        SlowQueryAnalysis analysis,
                                        SlowQueryInsightsResponse insights,
                                        List<SlowQueryHistorySummary> history) {
        sb.append("*").append(style.slowTitle()).append("*\n");
        try {
            if (analysis == null || analysis.getTotalSlowQueries() == null) {
                sb.append("  _(configure a slow log source to enable)_\n\n");
                return;
            }

            long total = analysis.getTotalSlowQueries();
            long critical = analysis.getCountBySeverity(SlowQuery.Severity.CRITICAL);
            long high = analysis.getCountBySeverity(SlowQuery.Severity.HIGH);
            String health = analysis.getOverallHealth() != null ? analysis.getOverallHealth() : "UNKNOWN";

            sb.append(String.format("  Total: *%d* · Critical: *%d* · High: *%d* · %s %s\n",
                total, critical, high, healthEmoji(health), health));

            // Delta vs yesterday
            if (history != null && history.size() >= 2) {
                long yesterday = history.get(1).getTotalSlowQueries() != null ? history.get(1).getTotalSlowQueries() : 0;
                long delta = total - yesterday;
                if (delta > 0)
                    sb.append(String.format("  ↑ *+%d* vs yesterday\n", delta));
                else if (delta < 0)
                    sb.append(String.format("  ↓ *%d* vs yesterday\n", delta));
                else
                    sb.append("  → Same as yesterday\n");
            }

            if (analysis.getTopSlowQueries() == null || analysis.getTopSlowQueries().isEmpty()) {
                sb.append("\n");
                return;
            }

            // Build sets for cross-referencing with insights
            java.util.Set<String> planDriftIds = java.util.Collections.emptySet();
            java.util.Set<String> tailRiskIds = java.util.Collections.emptySet();
            if (insights != null) {
                if (insights.getPlanDrift() != null && insights.getPlanDrift().getItems() != null) {
                    planDriftIds = insights.getPlanDrift().getItems().stream()
                        .map(SlowQueryInsightsResponse.PlanDriftItem::getQueryId)
                        .collect(Collectors.toSet());
                }
                if (insights.getTailRisk() != null && insights.getTailRisk().getTailRisks() != null) {
                    tailRiskIds = insights.getTailRisk().getTailRisks().stream()
                        .map(SlowQueryInsightsResponse.TailRiskItem::getQueryId)
                        .collect(Collectors.toSet());
                }
            }

            int shown = 0;
            Map<String, Map<String, String>> labelCache = new LinkedHashMap<>();
            for (SlowQuery q : analysis.getTopSlowQueries()) {
                if (shown++ >= TOP_SLOW_QUERIES) break;
                String preview = normalizedPreview(q.getNormalizedQuery() != null
                    ? q.getNormalizedQuery() : q.getQueryText());
                String idSuffix = q.getQueryId() != null && !q.getQueryId().isBlank()
                    ? String.format(" `(id: %s)`", shortId(q.getQueryId()))
                    : "";
                sb.append(String.format("\n  _%d. %s_%s\n", shown, preview, idSuffix));

                // Timing + frequency
                if (q.getAvgExecutionTimeMs() != null)
                    sb.append(String.format("     avg *%.0f ms*", q.getAvgExecutionTimeMs()));
                if (q.getCallCount() != null)
                    sb.append(String.format(" · called *%s×*", formatCount(q.getCallCount())));
                if (q.getAvgExecutionTimeMs() != null && q.getCallCount() != null) {
                    double totalMs = q.getAvgExecutionTimeMs() * q.getCallCount();
                    if (totalMs >= 60_000) {
                        sb.append(" · *").append(formatDbTime(totalMs)).append("* total DB time today");
                        double monthlyUsd = computeCostUsdMonthly(totalMs);
                        if (monthlyUsd >= 1.0) {
                            sb.append(String.format(" · ≈ *%s/mo* in compute", formatUsd(monthlyUsd)));
                        }
                    }
                }
                sb.append("\n");

                // Affected tables
                if (q.getAffectedTables() != null && !q.getAffectedTables().isEmpty())
                    sb.append("     tables: `" + String.join("`, `", q.getAffectedTables()) + "`\n");

                // Customer blast radius from literal extraction
                appendBlastRadius(sb, connectionId, q, labelCache);

                // Plan drift flag
                if (q.getQueryId() != null && planDriftIds.contains(q.getQueryId()))
                    sb.append("     ⚠️ *Plan drift* — execution plan changed recently\n");

                // Tail risk flag
                if (q.getQueryId() != null && tailRiskIds.contains(q.getQueryId())) {
                    insights.getTailRisk().getTailRisks().stream()
                        .filter(t -> q.getQueryId().equals(t.getQueryId()))
                        .findFirst()
                        .ifPresent(tr -> sb.append(String.format(
                            "     💣 *Tail risk* — P50: %.0f ms · P99: %.0f ms (%.0f×)\n",
                            tr.getP50Ms(), tr.getP99Ms(), tr.getTailRatio())));
                }
            }
            sb.append("\n");
        } catch (Exception e) {
            log.warn("Slow query section failed: {}", e.getMessage());
            sb.append("  _(data unavailable)_\n\n");
        }
    }

    private void appendBlastRadius(StringBuilder sb,
                                   String connectionId,
                                   SlowQuery q,
                                   Map<String, Map<String, String>> labelCache) {
        try {
            String sql = q.getSampleQuery();
            if (sql == null || sql.isBlank()) return;

            List<EnhancedSqlParserService.ColumnLiteralPair> literals =
                sqlParserService.extractWhereClauseLiterals(sql);
            if (literals.isEmpty()) return;

            // Group by column, count occurrences — surface the most interesting identifier columns
            Map<String, Long> colFreq = literals.stream()
                .filter(l -> isInterestingIdentifier(l.getColumnName()))
                .collect(Collectors.groupingBy(
                    l -> (l.getTableName() != null ? l.getTableName() + "." : "") + l.getColumnName(),
                    LinkedHashMap::new,
                    Collectors.counting()
                ));

            if (colFreq.isEmpty()) return;

            // For the top column, collect unique values
            String topCol = colFreq.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
            if (topCol == null) return;

            List<String> values = literals.stream()
                .filter(l -> {
                    String fqn = (l.getTableName() != null ? l.getTableName() + "." : "") + l.getColumnName();
                    return topCol.equals(fqn);
                })
                .map(EnhancedSqlParserService.ColumnLiteralPair::getValue)
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .limit(5)
                .collect(Collectors.toList());

            if (values.isEmpty()) return;
            String colDisplay = topCol.contains(".") ? topCol.substring(topCol.lastIndexOf('.') + 1) : topCol;
            Map<String, String> resolvedLabels = resolveIdentifierLabels(connectionId, colDisplay, values, labelCache);
            List<String> renderedValues = values.stream()
                .map(value -> formatResolvedValue(value, resolvedLabels.get(value)))
                .toList();
            sb.append(String.format("     affected %s: %s",
                blastRadiusLabel(colDisplay),
                String.join(", ", renderedValues)));
            if (values.size() == 5) sb.append(" _(+more)_");
            sb.append("\n");
        } catch (Exception ignored) {}
    }

    private static boolean isInterestingIdentifier(String col) {
        if (col == null) return false;
        String lower = col.toLowerCase();
        return lower.endsWith("_id") || lower.endsWith("id")
            || lower.equals("hotel") || lower.equals("group_id")
            || lower.equals("account_id") || lower.equals("tenant_id")
            || lower.equals("customer_id") || lower.equals("user_id")
            || lower.equals("booking_id") || lower.equals("property_id");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Top affected customers — the per-tenant axis of the slow-query data
    // ─────────────────────────────────────────────────────────────────────────

    /** How many customers to surface — small enough that the section stays scannable. */
    private static final int TOP_CUSTOMERS_LIMIT = 5;

    /**
     * Per-customer slowness rollup — the same data the "By Customer" tab in
     * the UI shows, condensed to the digest's top-N format. Pulls from the
     * {@code slow_query_customer_day} rollup which is populated by
     * {@code SlowQueryCustomerAttributionService} every time analyze-now or
     * the nightly job runs. Skips silently when the connection has no tenant
     * column configured (the underlying data simply isn't there).
     *
     * <p>Ranked by {@code total_slow_ms} — the customer whose queries burned
     * the most DB time leads, since that's what an operator would prioritize
     * outreach / optimization for. Each row reads:
     * <pre>
     *   1. Isha Foundation — 5 slow queries · 31.1s total · worst 4.8s
     *      → top: Booking lookup by hotel_id (id: e7e53f00)
     * </pre>
     * The "top" line is the customer's single worst query (by mean exec time),
     * so the operator can jump straight from "this customer is suffering" to
     * "and here's the query causing it."
     */
    private void appendTopCustomersSection(StringBuilder sb, DigestStyle style, String connectionId) {
        try {
            List<SlowQueryAnalyticsService.CustomerSummary> customers =
                slowQueryAnalyticsService.listCustomers(connectionId);
            if (customers == null || customers.isEmpty()) {
                // No tenant column configured, OR no attributed samples yet —
                // skip rendering rather than show an empty header.
                return;
            }

            sb.append("*").append(style.customersTitle()).append("*\n");
            sb.append("  Customers carrying the most slow-query DB time:\n");

            int shown = 0;
            for (SlowQueryAnalyticsService.CustomerSummary c : customers) {
                if (shown++ >= TOP_CUSTOMERS_LIMIT) break;
                String name = (c.customerName() != null && !c.customerName().isBlank())
                    ? c.customerName()
                    : c.customerId();
                String customerLabel = formatCustomerLabel(name, c.customerId());
                double totalMs = c.totalSlowMs() != null ? c.totalSlowMs() : 0.0;
                double worstMs = c.slowestMeanMs() != null ? c.slowestMeanMs() : 0.0;
                sb.append(String.format(
                    "\n  %d. %s — *%d* slow %s · *%s* total · worst *%s*\n",
                    shown,
                    customerLabel,
                    c.queryCount(),
                    c.queryCount() == 1 ? "query" : "queries",
                    formatDbTime(totalMs),
                    formatMillis(worstMs)
                ));

                // Top query for this customer — the "here's what's hurting them" payload.
                try {
                    List<SlowQueryAnalyticsService.CustomerQueryRow> rows =
                        slowQueryAnalyticsService.queriesForCustomer(connectionId, c.customerId());
                    if (rows != null && !rows.isEmpty()) {
                        var top = rows.get(0);
                        String label = (top.label() != null && !top.label().isBlank())
                            ? top.label()
                            : "Query";
                        sb.append(String.format(
                            "     → top: %s `(id: %s)`",
                            label,
                            shortId(top.fingerprint())
                        ));
                        if (top.meanExecMs() != null) {
                            sb.append(String.format(" · mean *%s*", formatMillis(top.meanExecMs())));
                        }
                        sb.append("\n");
                    }
                } catch (Exception innerEx) {
                    // Per-customer drill-in failure shouldn't kill the row — the
                    // ranking line is already valuable on its own.
                    log.debug("Per-customer top query lookup failed for {} on {}: {}",
                        c.customerId(), connectionId, innerEx.getMessage());
                }
            }
            sb.append("\n");
        } catch (Exception e) {
            log.debug("Top customers section skipped for {}: {}", connectionId, e.getMessage());
        }
    }

    /**
     * Render the customer's display name with a fallback to the raw tenant
     * id in parens when both are available — so a digest reader can both
     * recognize the customer ("Isha Foundation") and look them up in their
     * own CRM by id ("24603"). When the name and id are the same (no name
     * resolved yet), just show the id.
     */
    private static String formatCustomerLabel(String name, String id) {
        if (name == null || name.isBlank()) return id != null ? id : "(unknown)";
        if (id == null || id.isBlank() || id.equals(name)) return name;
        return name + " `(" + id + ")`";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Free win — highest ROI action
    // ─────────────────────────────────────────────────────────────────────────

    private void appendFreeWin(StringBuilder sb, DigestStyle style, String connectionId) {
        try {
            List<PerformanceAction> actions = actionAggregatorService.getTopActions(connectionId, 1);
            if (actions.isEmpty()) return;

            PerformanceAction action = actions.get(0);
            int impact = action.getImpactScore() != null ? action.getImpactScore() : 0;
            int effort = action.getEffortScore() != null ? action.getEffortScore() : 50;
            if (impact < 30) return; // Not worth surfacing low-impact actions

            sb.append("*").append(style.freeWinTitle()).append("*\n");
            sb.append("  ").append(action.getTitle()).append("\n");
            if (action.getDescription() != null)
                sb.append("  ").append(truncate(action.getDescription(), 120)).append("\n");
            if (action.getTargetObject() != null)
                sb.append("  Target: `").append(action.getTargetObject()).append("`");
            if (action.getTargetSecondary() != null)
                sb.append(" · `").append(action.getTargetSecondary()).append("`");
            if (action.getTargetObject() != null) sb.append("\n");
            sb.append(String.format("  Impact: *%d/100* · Effort: *%d/100* · ROI: *%.0f*\n",
                impact, effort, action.getRoi() != null ? action.getRoi() : 0.0));
            sb.append("\n");
        } catch (Exception e) {
            log.debug("Free win section skipped for {}: {}", connectionId, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Growth watch — top tables with forecast and event attribution
    // ─────────────────────────────────────────────────────────────────────────

    private void appendGrowthSection(StringBuilder sb, DigestStyle style, GrowthDigestData growthData) {
        try {
            sb.append("*").append(style.growthTitle()).append("*\n");
            if (growthData.latestSnapshots().isEmpty()) {
                sb.append("  Growth telemetry unavailable — no table snapshots captured yet\n\n");
                return;
            }

            if (!growthData.topGrowing().isEmpty()) {
                for (TableStatsHistory h : growthData.topGrowing()) {
                    appendGrowthLine(sb, h, growthData.forecastByTable(), growthData.anomalyByTable());
                }
                sb.append("\n");
                return;
            }

            if (growthData.bootstrapped()) {
                sb.append(String.format(
                    "  Baseline captured just now for *%d* table(s) — the next hourly sample will unlock true growth deltas\n",
                    growthData.latestSnapshots().size()
                ));
                growthData.latestSnapshots().stream()
                    .sorted(Comparator.comparingLong((TableStatsHistory snapshot) ->
                        snapshot.getSizeBytes() != null ? snapshot.getSizeBytes() : 0L).reversed())
                    .limit(3)
                    .forEach(snapshot -> sb.append(String.format(
                        "  `%s` currently at %s · %s rows\n",
                        snapshot.getTableName(),
                        formatBytes(snapshot.getSizeBytes() != null ? snapshot.getSizeBytes() : 0L),
                        formatCount(snapshot.getRowCount() != null ? snapshot.getRowCount() : 0L)
                    )));
                sb.append("\n");
                return;
            }

            sb.append("  No material growth in the latest window — largest tracked tables right now:\n");
            growthData.latestSnapshots().stream()
                .sorted(Comparator.comparingLong((TableStatsHistory snapshot) ->
                    snapshot.getSizeBytes() != null ? snapshot.getSizeBytes() : 0L).reversed())
                .limit(3)
                .forEach(snapshot -> sb.append(String.format(
                    "  `%s` at %s · %s rows\n",
                    snapshot.getTableName(),
                    formatBytes(snapshot.getSizeBytes() != null ? snapshot.getSizeBytes() : 0L),
                    formatCount(snapshot.getRowCount() != null ? snapshot.getRowCount() : 0L)
                )));
            sb.append("\n");
        } catch (Exception e) {
            log.warn("Growth section failed: {}", e.getMessage());
            sb.append("*").append(style.growthTitle()).append("*\n  _(data unavailable)_\n\n");
        }
    }

    private void appendGrowthLine(StringBuilder sb,
                                  TableStatsHistory h,
                                  Map<String, CapacityForecast> forecastMap,
                                  Map<String, GrowthAnomaly> anomalyMap) {
                String tableName = h.getTableName();
                sb.append(String.format("  `%s`  +%s", tableName, formatBytes(h.getSizeGrowthBytes())));

                if (h.getSizeGrowthPercent() != null)
                    sb.append(String.format(" (%.1f%%)", h.getSizeGrowthPercent()));
                if (h.getRowCount() != null)
                    sb.append(String.format(" · %s rows", formatCount(h.getRowCount())));

                // Forecast countdown
                CapacityForecast fc = forecastMap.get(tableName);
                if (fc != null && fc.getStorageExhaustionDate() != null) {
                    long days = java.time.temporal.ChronoUnit.DAYS.between(
                        LocalDateTime.now(), fc.getStorageExhaustionDate());
                    if (days > 0 && days <= 60)
                        sb.append(String.format(" · ⚠️ ~%d days until storage limit", days));
                }

                // Growth pattern
                GrowthAnomaly anomaly = anomalyMap.get(tableName);
                if (anomaly != null) {
                    if (anomaly.getGrowthPattern() != null)
                        sb.append(String.format(" · *%s*", anomaly.getGrowthPattern().name()));
                    if (anomaly.getAttributedEventDescription() != null)
                        sb.append(String.format("\n    └ caused by: _%s_",
                            truncate(anomaly.getAttributedEventDescription(), 60)));
                }
                sb.append("\n");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Schema events — breaking changes in the last 24h
    // ─────────────────────────────────────────────────────────────────────────

    private void appendSchemaEvents(StringBuilder sb, DigestStyle style, SchemaDigestData schemaData) {
        try {
            List<SchemaChange> notable = prioritizeSchemaChanges(schemaData.changes());

            sb.append("*").append(style.schemaTitle()).append("*\n");
            if (notable.isEmpty()) {
                if (schemaData.comparedFromSnapshots() && schemaData.currentSnapshotAt() != null && schemaData.previousSnapshotAt() != null) {
                    sb.append(String.format(
                        "  No structural delta between snapshots captured %s and %s ✅\n\n",
                        schemaData.previousSnapshotAt().format(DateTimeFormatter.ofPattern("MMM d HH:mm")),
                        schemaData.currentSnapshotAt().format(DateTimeFormatter.ofPattern("MMM d HH:mm"))
                    ));
                } else {
                    sb.append("  No recent schema delta detected ✅\n\n");
                }
                return;
            }

            for (SchemaChange c : notable) {
                String icon = schemaChangeIcon(c);
                sb.append(String.format("  %s `%s` — %s\n",
                    icon,
                    schemaObjectReference(c),
                    c.getDescription() != null ? truncate(c.getDescription(), 80) : c.getChangeType()));
            }
            sb.append("\n");
        } catch (Exception e) {
            log.debug("Schema events section skipped: {}", e.getMessage());
        }
    }

    static List<SchemaChange> prioritizeSchemaChanges(List<SchemaChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return List.of();
        }
        return changes.stream()
            .filter(Objects::nonNull)
            .sorted(Comparator
                .comparingInt((SchemaChange change) -> schemaChangePriority(change)).reversed()
                .thenComparing(change -> change.getDetectedAt() != null ? change.getDetectedAt() : LocalDateTime.MIN, Comparator.reverseOrder())
                .thenComparing(change -> Objects.toString(change.getObjectName(), ""), String.CASE_INSENSITIVE_ORDER))
            .limit(5)
            .toList();
    }

    private static int schemaChangePriority(@Nullable SchemaChange change) {
        if (change == null) {
            return 0;
        }
        if (Boolean.TRUE.equals(change.getIsBreakingChange()) || change.getSeverity() == SchemaChange.Severity.CRITICAL) {
            return 3;
        }
        if (change.getSeverity() == SchemaChange.Severity.WARNING) {
            return 2;
        }
        return 1;
    }

    private static String schemaChangeIcon(@Nullable SchemaChange change) {
        int priority = schemaChangePriority(change);
        return switch (priority) {
            case 3 -> "🔴";
            case 2 -> "🟡";
            default -> "🟢";
        };
    }

    private static String schemaObjectReference(@Nullable SchemaChange change) {
        if (change == null) {
            return "unknown";
        }
        return switch (change.getObjectType()) {
            case TABLE -> Objects.toString(change.getObjectName(), "unknown");
            case COLUMN -> change.getTableName() != null && change.getObjectName() != null
                ? change.getTableName() + "." + change.getObjectName()
                : Objects.toString(change.getObjectName(), "unknown");
            case INDEX, CONSTRAINT, FOREIGN_KEY -> change.getTableName() != null && change.getObjectName() != null
                ? change.getTableName() + " :: " + change.getObjectName()
                : Objects.toString(change.getObjectName(), "unknown");
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Deep Dive — one rotating angle per day so the digest doesn't read the
    // same two days in a row. Each topic uses already-fetched data so we don't
    // pay extra round-trips. Topic chosen by digestCount % NUM_TOPICS.
    // ─────────────────────────────────────────────────────────────────────────

    private static final int DEEP_DIVE_TOPIC_COUNT = 4;

    private void appendDeepDiveSection(StringBuilder sb,
                                       DigestStyle style,
                                       @Nullable SlowQueryAnalysis analysis,
                                       GrowthDigestData growthData) {
        try {
            int seed = (int) (Math.floorMod(style.digestCount(), DEEP_DIVE_TOPIC_COUNT));
            // Try the seeded topic first, then fall through to alternates so the section
            // reliably renders even when one topic's source data is empty. The seed gives
            // day-to-day variety; the fallback gives day-to-day reliability.
            String body = "";
            for (int offset = 0; offset < DEEP_DIVE_TOPIC_COUNT && body.isBlank(); offset++) {
                int topic = (seed + offset) % DEEP_DIVE_TOPIC_COUNT;
                body = switch (topic) {
                    case 0 -> deepDiveHottestTable(analysis);
                    case 1 -> deepDiveHeaviestQuery(analysis);
                    case 2 -> deepDiveLargestTable(growthData);
                    case 3 -> deepDiveQueryMix(analysis);
                    default -> "";
                };
                if (body == null) body = "";
            }
            if (body.isBlank()) return; // truly quiet
            sb.append("*").append(style.deepDiveTitle()).append("*\n");
            sb.append(body);
            sb.append("\n");
        } catch (Exception e) {
            log.debug("Deep-dive section skipped: {}", e.getMessage());
        }
    }

    private String deepDiveHottestTable(@Nullable SlowQueryAnalysis analysis) {
        if (analysis == null || analysis.getTopSlowQueries() == null || analysis.getTopSlowQueries().isEmpty()) {
            return "";
        }
        Map<String, Long> tableHits = new LinkedHashMap<>();
        long totalQueries = 0;
        for (SlowQuery q : analysis.getTopSlowQueries()) {
            if (q.getAffectedTables() == null) continue;
            totalQueries++;
            for (String table : q.getAffectedTables()) {
                if (table == null || table.isBlank()) continue;
                tableHits.merge(table.toLowerCase(Locale.ROOT), 1L, Long::sum);
            }
        }
        if (tableHits.isEmpty()) return "";
        Map.Entry<String, Long> top = tableHits.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .orElse(null);
        if (top == null) return "";
        long pct = totalQueries > 0 ? Math.round(100.0 * top.getValue() / totalQueries) : 0;
        return String.format(
            "  🔥 *Hottest table this 24h*: `%s`\n" +
            "     Referenced by *%d of %d* top slow queries (%d%%) — likely the next hotspot worth indexing or partitioning.\n",
            top.getKey(), top.getValue(), totalQueries, pct);
    }

    private String deepDiveHeaviestQuery(@Nullable SlowQueryAnalysis analysis) {
        if (analysis == null || analysis.getTopSlowQueries() == null || analysis.getTopSlowQueries().isEmpty()) {
            return "";
        }
        SlowQuery worst = analysis.getTopSlowQueries().stream()
            .max(Comparator.comparingDouble(q -> {
                double avg = q.getAvgExecutionTimeMs() != null ? q.getAvgExecutionTimeMs() : 0.0;
                long calls = q.getCallCount() != null ? q.getCallCount() : 0L;
                return avg * calls;
            }))
            .orElse(null);
        if (worst == null) return "";
        double avg = worst.getAvgExecutionTimeMs() != null ? worst.getAvgExecutionTimeMs() : 0.0;
        long calls = worst.getCallCount() != null ? worst.getCallCount() : 0L;
        double totalMs = avg * calls;
        if (totalMs < 60_000) return ""; // not interesting
        String preview = normalizedPreview(worst.getNormalizedQuery() != null
            ? worst.getNormalizedQuery() : worst.getQueryText());
        StringBuilder out = new StringBuilder();
        out.append("  🐘 *One query alone burned ").append(formatDbTime(totalMs)).append(" of DB time today*\n");
        out.append("     `").append(preview).append("`\n");
        double monthlyUsd = computeCostUsdMonthly(totalMs);
        if (monthlyUsd >= 1.0) {
            out.append(String.format("     If left as-is, that's roughly *%s/mo* in compute. Optimising this single pattern is the highest-leverage win.\n",
                formatUsd(monthlyUsd)));
        } else {
            out.append("     Optimising this single pattern is one of the highest-leverage wins available.\n");
        }
        return out.toString();
    }

    private String deepDiveLargestTable(GrowthDigestData growthData) {
        TableStatsHistory biggest = growthData.latestSnapshots().stream()
            .filter(s -> s.getSizeBytes() != null && s.getSizeBytes() > 0)
            .max(Comparator.comparingLong(s -> s.getSizeBytes()))
            .orElse(null);
        if (biggest == null) return "";
        long size = biggest.getSizeBytes();
        long rows = biggest.getRowCount() != null ? biggest.getRowCount() : 0L;
        double monthlyUsd = storageCostUsdMonthly(size);
        StringBuilder out = new StringBuilder();
        out.append(String.format("  🐋 *Largest table on this DB*: `%s`\n", biggest.getTableName()));
        out.append(String.format("     %s · %s rows", formatBytes(size), formatCount(rows)));
        if (monthlyUsd >= 0.10) {
            out.append(String.format(" · costs ~*%s/mo* to keep on disk", formatUsd(monthlyUsd)));
        }
        out.append("\n     Worth a glance for partitioning, archival, or TTL eligibility.\n");
        return out.toString();
    }

    private String deepDiveQueryMix(@Nullable SlowQueryAnalysis analysis) {
        if (analysis == null || analysis.getTopSlowQueries() == null || analysis.getTopSlowQueries().isEmpty()) {
            return "";
        }
        Map<String, Long> mix = new LinkedHashMap<>();
        long total = 0;
        for (SlowQuery q : analysis.getTopSlowQueries()) {
            String type = inferQueryType(q.getNormalizedQuery() != null ? q.getNormalizedQuery() : q.getQueryText());
            if (type == null) continue;
            mix.merge(type, 1L, Long::sum);
            total++;
        }
        if (total == 0) return "";
        long finalTotal = total;
        String breakdown = mix.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .map(e -> String.format("%d%% %s", Math.round(100.0 * e.getValue() / finalTotal), e.getKey()))
            .collect(Collectors.joining(" · "));
        String dominant = mix.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("SELECT");
        String shape = switch (dominant) {
            case "SELECT" -> "read-heavy";
            case "UPDATE", "DELETE" -> "write-heavy";
            case "INSERT" -> "ingest-heavy";
            default -> "DDL/maintenance-heavy";
        };
        return String.format(
            "  📊 *Today's slow-query mix*: %s\n" +
            "     Workload skews *%s* — index strategy and isolation level should match.\n",
            breakdown, shape);
    }

    private static String inferQueryType(String sql) {
        if (sql == null) return null;
        String trimmed = sql.trim().toUpperCase(Locale.ROOT);
        if (trimmed.startsWith("SELECT") || trimmed.startsWith("WITH")) return "SELECT";
        if (trimmed.startsWith("INSERT")) return "INSERT";
        if (trimmed.startsWith("UPDATE")) return "UPDATE";
        if (trimmed.startsWith("DELETE")) return "DELETE";
        if (trimmed.startsWith("ALTER") || trimmed.startsWith("CREATE") || trimmed.startsWith("DROP")) return "DDL";
        return "OTHER";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Potential Savings — money on the table, surfaced at the top of the digest
    // ─────────────────────────────────────────────────────────────────────────

    /** How much of a query's daily DB time we assume is recoverable through optimization. */
    private static final double COMPUTE_SAVINGS_OPTIMIZATION_FACTOR = 0.50;

    @SuppressWarnings("unchecked")
    private void appendPotentialSavingsSection(StringBuilder sb,
                                               DigestStyle style,
                                               String connectionId,
                                               GrowthDigestData growthData,
                                               @Nullable SlowQueryAnalysis analysis,
                                               LocalDateTime since) {
        try {
            // ── Storage waste (concrete) ──────────────────────────────────────
            long unusedBytes = 0L;
            int unusedCount = 0;
            if (indexAdvisorService != null) {
                try {
                    Map<String, Object> health = indexAdvisorService.getIndexHealthReport(connectionId);
                    if (health != null) {
                        Object wasted = health.get("unusedIndexWastedBytes");
                        if (wasted instanceof Number n) unusedBytes = n.longValue();
                        Object indexes = health.get("unusedIndexes");
                        if (indexes instanceof List<?> list) unusedCount = list.size();
                    }
                } catch (Exception e) {
                    log.debug("Savings: index health unavailable for {}: {}", connectionId, e.getMessage());
                }
            }

            long bloatBytes = growthData.latestSnapshots().stream()
                .filter(s -> s.getBloatPercent() != null && s.getBloatPercent() >= BLOAT_THRESHOLD_PERCENT)
                .mapToLong(s -> s.getBloatBytes() != null ? s.getBloatBytes() : 0L)
                .sum();
            int bloatTableCount = (int) growthData.latestSnapshots().stream()
                .filter(s -> s.getBloatPercent() != null && s.getBloatPercent() >= BLOAT_THRESHOLD_PERCENT)
                .count();

            double storageUsdMonthly = storageCostUsdMonthly(unusedBytes) + storageCostUsdMonthly(bloatBytes);

            // ── Compute opportunity ───────────────────────────────────────────
            // Two complementary sources: slow-query analysis covers the heaviest
            // patterns currently in flight; query_fingerprints covers newcomers.
            double hotspotMsTotal = 0.0;
            int hotspotCount = 0;
            String hotspotSource = "";

            if (analysis != null && analysis.getTopSlowQueries() != null && !analysis.getTopSlowQueries().isEmpty()) {
                double slowMs = analysis.getTopSlowQueries().stream()
                    .mapToDouble(q -> {
                        double avg = q.getAvgExecutionTimeMs() != null ? q.getAvgExecutionTimeMs() : 0.0;
                        long calls = q.getCallCount() != null ? q.getCallCount() : 0L;
                        return avg * calls;
                    })
                    .sum();
                hotspotMsTotal += slowMs;
                hotspotCount += analysis.getTopSlowQueries().size();
                hotspotSource = "top slow queries";
            }
            if (queryFingerprintRepository != null) {
                try {
                    List<QueryFingerprint> recent = queryFingerprintRepository.findRecentlySeen(connectionId, since);
                    List<QueryFingerprint> hotspots = recent.stream()
                        .sorted(Comparator.comparingDouble((QueryFingerprint qf) -> dbTimeBurned(qf)).reversed())
                        .limit(10)
                        .toList();
                    if (!hotspots.isEmpty()) {
                        hotspotMsTotal += hotspots.stream().mapToDouble(SlackDailyDigestService::dbTimeBurned).sum();
                        hotspotCount += hotspots.size();
                        hotspotSource = hotspotSource.isBlank() ? "newcomer queries" : "slow + newcomer queries";
                    }
                } catch (Exception e) {
                    log.debug("Savings: fingerprint hot-spots unavailable for {}: {}", connectionId, e.getMessage());
                }
            }
            double computeUsdMonthly = computeCostUsdMonthly(hotspotMsTotal) * COMPUTE_SAVINGS_OPTIMIZATION_FACTOR;

            double totalUsd = storageUsdMonthly + computeUsdMonthly;
            // Quiet day: skip entirely if the headline number isn't motivating
            if (totalUsd < SAVINGS_USD_FLOOR_MONTHLY) {
                return;
            }

            sb.append("*").append(style.savingsTitle()).append("*\n");
            sb.append(String.format("  💰 Roughly *%s/mo* recoverable across storage + compute\n",
                formatUsd(totalUsd)));

            if (storageUsdMonthly >= 1.0 && (unusedBytes > 0 || bloatBytes > 0)) {
                StringBuilder breakdown = new StringBuilder();
                if (unusedBytes > 0) {
                    breakdown.append(String.format("%d unused index%s (%s)",
                        unusedCount, unusedCount == 1 ? "" : "es", formatBytes(unusedBytes)));
                }
                if (bloatBytes > 0) {
                    if (breakdown.length() > 0) breakdown.append(" + ");
                    breakdown.append(String.format("%d bloated table%s (%s)",
                        bloatTableCount, bloatTableCount == 1 ? "" : "s", formatBytes(bloatBytes)));
                }
                sb.append(String.format("     🗄️ Storage: *%s/mo* — %s\n",
                    formatUsd(storageUsdMonthly), breakdown));
            }

            if (computeUsdMonthly >= 1.0 && hotspotCount > 0) {
                sb.append(String.format("     ⚙️ Compute: *%s/mo* — %s burning *%s* of DB time today (assumes ~50%% recoverable via optimization)\n",
                    formatUsd(computeUsdMonthly),
                    hotspotSource,
                    formatDbTime(hotspotMsTotal)));
            }

            sb.append(String.format("  _Estimates use AWS RDS gp3 storage (~$%.3f/GB-mo) and db.r6g.large vCPU rates (~$%.2f/CPU-hr)_\n",
                USD_PER_GB_MONTH, USD_PER_VCPU_HOUR));
            sb.append("\n");
        } catch (Exception e) {
            log.debug("Savings section skipped for {}: {}", connectionId, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Security & Privileged Actions — off-hours DDL, failed logins, new grants
    // ─────────────────────────────────────────────────────────────────────────

    private static final java.util.Set<DatabaseEvent.EventType> PRIVILEGED_EVENT_TYPES = java.util.EnumSet.of(
        DatabaseEvent.EventType.SCHEMA_CHANGE,
        DatabaseEvent.EventType.INDEX_CREATE,
        DatabaseEvent.EventType.INDEX_DROP,
        DatabaseEvent.EventType.TABLE_CREATE,
        DatabaseEvent.EventType.TABLE_DROP,
        DatabaseEvent.EventType.TABLE_ALTER,
        DatabaseEvent.EventType.CONFIGURATION_CHANGE,
        DatabaseEvent.EventType.SECURITY_UPDATE,
        DatabaseEvent.EventType.MIGRATION,
        DatabaseEvent.EventType.PARTITION_ADD,
        DatabaseEvent.EventType.PARTITION_DROP
    );
    private static final int OFF_HOURS_START = 19; // 19:00 local
    private static final int OFF_HOURS_END = 9;    // before 09:00 local
    private static final DateTimeFormatter EVENT_TIME_FMT = DateTimeFormatter.ofPattern("MMM d HH:mm");

    // ─────────────────────────────────────────────────────────────────────────
    // Index wins — workload-weighted top-N from the consolidated advisor.
    //
    // Companion to (not a replacement for) the storage-cost angle in
    // `savings` / `waste`. Those pull from IndexAdvisorService and show
    // "you're paying $X/mo for unused indexes." This section pulls from
    // IndexRecommendationService and shows "you're losing N hours of query
    // time per day; here's the highest-ROI fix and how to validate it."
    //
    // Both kinds of waste matter — different audiences. We surface them
    // side-by-side rather than collapse one into the other.
    // ─────────────────────────────────────────────────────────────────────────

    private static final int INDEX_WINS_LIMIT = 3;

    private void appendIndexWinsSection(StringBuilder sb, DigestStyle style, String connectionId) {
        if (indexRecommendationService == null) return; // graceful: optional dependency
        try {
            List<IndexRecommendationService.TopRecommendationWithEvidence> top =
                indexRecommendationService.getTopRecommendationsWithEvidence(connectionId, INDEX_WINS_LIMIT);
            if (top == null || top.isEmpty()) return;

            // Only surface CREATE_INDEX candidates here — DROP candidates already
            // live in `savings` / `waste` (storage cost angle). Mixing them in
            // this section would duplicate the narrative.
            List<IndexRecommendationService.TopRecommendationWithEvidence> creates = top.stream()
                .filter(p -> p.recommendation() != null
                    && p.recommendation().getKind() == IndexRecommendationEntity.Kind.CREATE_INDEX)
                .toList();
            if (creates.isEmpty()) return;

            sb.append("*").append(style.indexWinsTitle()).append("*\n");
            sb.append("  Workload-weighted picks — ranked by net benefit (saved query time − write-amplification cost):\n");

            int rank = 1;
            for (IndexRecommendationService.TopRecommendationWithEvidence pair : creates) {
                IndexRecommendationEntity r = pair.recommendation();
                String prio = r.getPriority() != null ? r.getPriority().name() : "?";
                String netLabel = formatNetBenefit(r.netBenefitMs());
                String occ = r.getOccurrenceCount() != null && r.getOccurrenceCount() > 1
                    ? String.format(", seen %d×", r.getOccurrenceCount()) : "";
                String hypopg = "";
                if (r.getHypopgReductionPct() != null && r.getHypopgReductionPct() > 0) {
                    hypopg = String.format(" · HypoPG −%.0f%%", r.getHypopgReductionPct());
                }
                sb.append(String.format("  %d. `%s(%s)` — *%s*%s%s%s\n",
                    rank++,
                    r.getTableName(),
                    r.getColumnNames(),
                    prio,
                    occ,
                    netLabel.isEmpty() ? "" : " · " + netLabel,
                    hypopg
                ));

                // Top contributing query — the "why" payload
                List<IndexRecommendationEvidence> ev = pair.topEvidence();
                if (ev != null && !ev.isEmpty()) {
                    IndexRecommendationEvidence top1 = ev.get(0);
                    Long calls = top1.getCalls();
                    Double mean = top1.getMeanExecTimeMs();
                    Double total = top1.getTotalExecTimeMs();
                    if (calls != null && mean != null) {
                        sb.append(String.format("     → %s calls × %s mean = %s total (`%s`)\n",
                            formatCallCount(calls),
                            formatMillis(mean),
                            formatMillis(total != null ? total : calls * mean),
                            top1.getRole() != null ? top1.getRole() : "WHERE_EQ"
                        ));
                    }
                }

                // Actionable CTA: validate without writes, then apply.
                sb.append(String.format("     `deepsql indexes apply %s --mode dry-run` to validate; "
                    + "add `--mode apply --confirm` to ship.\n",
                    r.getId() != null ? r.getId().substring(0, Math.min(8, r.getId().length())) + "…" : "<id>"));
            }
            sb.append("\n");
        } catch (Exception e) {
            log.debug("Index wins section skipped for {}: {}", connectionId, e.getMessage());
        }
    }

    /**
     * Turn a SQL preview into a 1-line, human-readable label for the digest.
     * Backed by {@link QueryLabeler} — produces things like
     * "Booking lookup by hotel_id" or "Room reservations aggregation" instead
     * of the partial SQL prefix the old {@code truncate(preview, 60)} produced.
     * The full text isn't lost — the digest pairs this with the query ID so
     * users can pull it up in the CLI ({@code deepsql queries get <id>}) or
     * the UI's Slow Queries → Tracked Queries view.
     */
    private static String labelForDigest(String sql) {
        if (sql == null || sql.isBlank()) return "Query";
        String label = QueryLabeler.label(sql);
        return label != null && !label.isBlank() ? label : "Query";
    }

    /** First 8 chars of a query/fingerprint id — enough to look up in the CLI. */
    private static String shortId(String id) {
        if (id == null || id.isBlank()) return "?";
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    /** Humanize milliseconds for Slack: 4823000ms → "1.3h", 482ms → "482ms". */
    private static String formatMillis(double ms) {
        if (ms <= 0 || !Double.isFinite(ms)) return "0ms";
        if (ms >= 86_400_000) return String.format("%.1fd", ms / 86_400_000);
        if (ms >= 3_600_000) return String.format("%.1fh", ms / 3_600_000);
        if (ms >= 60_000) return String.format("%.1fm", ms / 60_000);
        if (ms >= 1_000) return String.format("%.1fs", ms / 1_000);
        return String.format("%.0fms", ms);
    }

    /** "net=1.3h saved" — empty string when no workload signal. */
    private static String formatNetBenefit(Long netMs) {
        if (netMs == null || netMs <= 0) return "";
        return "net=" + formatMillis(netMs) + " saved";
    }

    /** "4,500" — locale-stable thousands grouping for Slack readability. */
    private static String formatCallCount(long calls) {
        if (calls < 1000) return Long.toString(calls);
        return String.format("%,d", calls);
    }

    private void appendSecuritySection(StringBuilder sb, DigestStyle style, String connectionId, LocalDateTime since) {
        try {
            List<DatabaseEvent> recentEvents = safeRecentEvents(connectionId, since);
            List<DatabaseEvent> privileged = recentEvents.stream()
                .filter(event -> event != null && event.getEventType() != null
                    && PRIVILEGED_EVENT_TYPES.contains(event.getEventType()))
                .sorted(Comparator.comparing(
                    (DatabaseEvent event) -> event.getEventTimestamp() == null ? LocalDateTime.MIN : event.getEventTimestamp(),
                    Comparator.reverseOrder()
                ))
                .toList();
            List<DatabaseEvent> offHours = privileged.stream()
                .filter(event -> event.getEventTimestamp() != null && isOffHours(event.getEventTimestamp()))
                .limit(3)
                .toList();

            List<ConnectionAccessGrant> recentGrants = safeRecentGrants(connectionId, since);
            long failedLogins = safeFailedLoginCount(since);
            List<String> failedLoginActors = safeFailedLoginActors(since);

            // Quiet day: no privileged events, no new grants, no failed logins
            if (privileged.isEmpty() && recentGrants.isEmpty() && failedLogins == 0) {
                return;
            }

            sb.append("*").append(style.securityTitle()).append("*\n");

            if (!offHours.isEmpty()) {
                sb.append("  🌙 *Off-hours privileged activity*\n");
                for (DatabaseEvent event : offHours) {
                    String when = event.getEventTimestamp().format(EVENT_TIME_FMT);
                    String actor = event.getInitiatedBy() != null && !event.getInitiatedBy().isBlank()
                        ? event.getInitiatedBy() : "unknown";
                    String description = event.getEventName() != null && !event.getEventName().isBlank()
                        ? event.getEventName()
                        : (event.getDescription() != null ? event.getDescription() : event.getEventType().getDisplayName());
                    sb.append(String.format("     `%s` ran *%s* at %s — %s\n",
                        truncate(actor, 32),
                        event.getEventType().name(),
                        when,
                        truncate(description, 80)));
                }
            } else if (!privileged.isEmpty()) {
                sb.append(String.format("  ⚙️ *%d* privileged DDL/permission event(s) in the last 24h\n",
                    privileged.size()));
                privileged.stream().limit(2).forEach(event -> {
                    String when = event.getEventTimestamp() != null ? event.getEventTimestamp().format(EVENT_TIME_FMT) : "—";
                    String actor = event.getInitiatedBy() != null && !event.getInitiatedBy().isBlank()
                        ? event.getInitiatedBy() : "unknown";
                    sb.append(String.format("     `%s` · *%s* @ %s\n",
                        truncate(actor, 32), event.getEventType().name(), when));
                });
            }

            if (!recentGrants.isEmpty()) {
                long count = recentGrants.size();
                sb.append(String.format("  🔑 *%d* new connection access grant(s)\n", count));
                recentGrants.stream().limit(3).forEach(grant -> sb.append(String.format(
                    "     `%s` granted *%s* by `%s`\n",
                    truncate(grant.getUsername(), 32),
                    grant.getAccessLevel() != null ? grant.getAccessLevel().name() : "?",
                    truncate(Objects.toString(grant.getGrantedBy(), "system"), 32)
                )));
            }

            if (failedLogins > 0) {
                sb.append(String.format("  🚪 *%d* failed/expired login attempt(s) across the org\n", failedLogins));
                if (!failedLoginActors.isEmpty()) {
                    sb.append("     top actors: ");
                    sb.append(failedLoginActors.stream().limit(3)
                        .map(email -> "`" + truncate(email, 40) + "`")
                        .collect(Collectors.joining(", ")));
                    sb.append("\n");
                }
            }
            sb.append("\n");
        } catch (Exception e) {
            log.debug("Security section skipped for {}: {}", connectionId, e.getMessage());
        }
    }

    private List<DatabaseEvent> safeRecentEvents(String connectionId, LocalDateTime since) {
        if (databaseEventRepository == null) return List.of();
        try {
            return databaseEventRepository.findRecentEvents(connectionId, since);
        } catch (Exception e) {
            log.debug("Could not load recent database events for {}: {}", connectionId, e.getMessage());
            return List.of();
        }
    }

    private List<ConnectionAccessGrant> safeRecentGrants(String connectionId, LocalDateTime since) {
        if (connectionAccessGrantRepository == null) return List.of();
        try {
            return connectionAccessGrantRepository.findRecentForDigest(connectionId, since);
        } catch (Exception e) {
            log.debug("Could not load recent access grants for {}: {}", connectionId, e.getMessage());
            return List.of();
        }
    }

    private long safeFailedLoginCount(LocalDateTime since) {
        if (authLoginChallengeRepository == null) return 0L;
        try {
            return authLoginChallengeRepository.countFailedSince(since);
        } catch (Exception e) {
            log.debug("Could not count failed login attempts: {}", e.getMessage());
            return 0L;
        }
    }

    private List<String> safeFailedLoginActors(LocalDateTime since) {
        if (authLoginChallengeRepository == null) return List.of();
        try {
            return authLoginChallengeRepository.findRecentFailures(since).stream()
                .map(challenge -> challenge.getEmail())
                .filter(Objects::nonNull)
                .filter(email -> !email.isBlank())
                .map(email -> email.toLowerCase(Locale.ROOT))
                .distinct()
                .limit(3)
                .toList();
        } catch (Exception e) {
            log.debug("Could not load failed login actors: {}", e.getMessage());
            return List.of();
        }
    }

    private static boolean isOffHours(LocalDateTime timestamp) {
        int hour = timestamp.getHour();
        return hour < OFF_HOURS_END || hour >= OFF_HOURS_START;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Concurrency Hotspots — blocking sessions, lock waits
    // ─────────────────────────────────────────────────────────────────────────

    private void appendConcurrencySection(StringBuilder sb, DigestStyle style, String connectionId, LocalDateTime since) {
        if (lockContentionRepository == null) return;
        try {
            List<LockContention> recent = lockContentionRepository.findRecentForDigest(connectionId, since);
            if (recent == null || recent.isEmpty()) {
                return; // quiet day — suppress entirely
            }

            long critical = recent.stream().filter(lc -> lc.getSeverity() == LockContention.Severity.CRITICAL).count();
            long high = recent.stream().filter(lc -> lc.getSeverity() == LockContention.Severity.HIGH).count();
            long maxWait = recent.stream()
                .map(LockContention::getWaitDurationSeconds)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .max().orElse(0L);

            sb.append("*").append(style.concurrencyTitle()).append("*\n");
            sb.append(String.format("  Total: *%d* · Critical: *%d* · High: *%d* · Longest wait: *%s*\n",
                recent.size(), critical, high, formatDurationSeconds(maxWait)));

            int shown = 0;
            for (LockContention lc : recent) {
                if (shown++ >= 3) break;
                String severityIcon = severityEmojiFor(lc.getSeverity());
                String blocker = lc.getBlockingUser() != null ? lc.getBlockingUser() : "unknown";
                String victim = lc.getBlockedUser() != null ? lc.getBlockedUser() : "unknown";
                String table = lc.getTableName() != null ? lc.getTableName() : "—";
                long wait = lc.getWaitDurationSeconds() != null ? lc.getWaitDurationSeconds() : 0L;
                sb.append(String.format("\n  %s `%s` blocked `%s` on `%s` for *%s*\n",
                    severityIcon,
                    truncate(blocker, 24),
                    truncate(victim, 24),
                    truncate(table, 40),
                    formatDurationSeconds(wait)));
                if (lc.getBlockingQuery() != null && !lc.getBlockingQuery().isBlank()) {
                    sb.append("     blocker: _").append(normalizedPreview(lc.getBlockingQuery())).append("_\n");
                }
                if (lc.getLockType() != null) {
                    sb.append("     lock: `").append(lc.getLockType());
                    if (lc.getLockMode() != null) sb.append("` mode `").append(lc.getLockMode());
                    sb.append("`\n");
                }
            }
            sb.append("\n");
        } catch (Exception e) {
            log.debug("Concurrency section skipped for {}: {}", connectionId, e.getMessage());
        }
    }

    private static String severityEmojiFor(@Nullable LockContention.Severity severity) {
        if (severity == null) return "⚪";
        return switch (severity) {
            case CRITICAL -> "🔴";
            case HIGH -> "🟠";
            case MEDIUM -> "🟡";
            case LOW -> "🟢";
        };
    }

    private static String formatDurationSeconds(long seconds) {
        if (seconds <= 0) return "—";
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return String.format("%dm %ds", seconds / 60, seconds % 60);
        return String.format("%dh %dm", seconds / 3600, (seconds % 3600) / 60);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Silent Waste — unused indexes, table bloat
    // ─────────────────────────────────────────────────────────────────────────

    private static final long WASTE_MIN_BYTES = 1L << 30; // 1 GB
    private static final double BLOAT_THRESHOLD_PERCENT = 20.0;

    @SuppressWarnings("unchecked")
    private void appendSilentWasteSection(StringBuilder sb, DigestStyle style, String connectionId, GrowthDigestData growthData) {
        try {
            long unusedWastedBytes = 0L;
            int unusedCount = 0;
            List<Map<String, Object>> unusedIndexes = List.of();
            if (indexAdvisorService != null) {
                try {
                    Map<String, Object> health = indexAdvisorService.getIndexHealthReport(connectionId);
                    if (health != null) {
                        Object wasted = health.get("unusedIndexWastedBytes");
                        if (wasted instanceof Number n) unusedWastedBytes = n.longValue();
                        Object indexes = health.get("unusedIndexes");
                        if (indexes instanceof List<?> list) {
                            unusedIndexes = (List<Map<String, Object>>) list;
                            unusedCount = list.size();
                        }
                    }
                } catch (Exception e) {
                    log.debug("Could not load index health report for {}: {}", connectionId, e.getMessage());
                }
            }

            List<TableStatsHistory> bloated = growthData.latestSnapshots().stream()
                .filter(snapshot -> snapshot.getBloatPercent() != null
                    && snapshot.getBloatPercent() >= BLOAT_THRESHOLD_PERCENT)
                .sorted(Comparator
                    .comparingLong((TableStatsHistory s) -> s.getBloatBytes() != null ? s.getBloatBytes() : 0L)
                    .reversed())
                .limit(3)
                .toList();

            // Quiet day: under reclaim threshold and no bloated tables
            if (unusedWastedBytes < WASTE_MIN_BYTES && bloated.isEmpty()) {
                return;
            }

            sb.append("*").append(style.wasteTitle()).append("*\n");

            if (unusedWastedBytes >= WASTE_MIN_BYTES) {
                sb.append(String.format("  🗑️ *%s* reclaimable from *%d* unused index(es) · ≈ *%s/mo*\n",
                    formatBytes(unusedWastedBytes), unusedCount,
                    formatUsd(storageCostUsdMonthly(unusedWastedBytes))));
                int shown = 0;
                for (Map<String, Object> idx : unusedIndexes) {
                    if (shown++ >= 3) break;
                    String name = Objects.toString(idx.get("indexName"), Objects.toString(idx.get("index_name"), "index"));
                    String table = Objects.toString(idx.get("tableName"), Objects.toString(idx.get("table_name"), "?"));
                    Object size = idx.get("indexSizeBytes");
                    long sizeBytes = size instanceof Number n ? n.longValue() : 0L;
                    sb.append(String.format("     `%s` on `%s` · %s · ≈ %s/mo\n",
                        truncate(name, 40), truncate(table, 40),
                        formatBytes(sizeBytes), formatUsd(storageCostUsdMonthly(sizeBytes))));
                }
            }

            if (!bloated.isEmpty()) {
                sb.append("  💧 *Bloated tables* (≥ ")
                    .append(String.format("%.0f%%", BLOAT_THRESHOLD_PERCENT))
                    .append(" wasted space):\n");
                for (TableStatsHistory snapshot : bloated) {
                    long bloatBytes = snapshot.getBloatBytes() != null ? snapshot.getBloatBytes() : 0L;
                    sb.append(String.format("     `%s` · %s wasted · *%.0f%%* of allocated · ≈ %s/mo\n",
                        truncate(snapshot.getTableName(), 40),
                        formatBytes(bloatBytes),
                        snapshot.getBloatPercent(),
                        formatUsd(storageCostUsdMonthly(bloatBytes))));
                }
            }
            sb.append("\n");
        } catch (Exception e) {
            log.debug("Silent-waste section skipped for {}: {}", connectionId, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Newcomer Queries — fingerprints first seen in the last 24h
    // ─────────────────────────────────────────────────────────────────────────

    private void appendNewcomersSection(StringBuilder sb, DigestStyle style, String connectionId, LocalDateTime since) {
        if (queryFingerprintRepository == null) return;
        try {
            List<QueryFingerprint> recent = queryFingerprintRepository.findRecentlySeen(connectionId, since);
            if (recent == null || recent.isEmpty()) {
                return;
            }
            // Truly new: firstSeenAt within window. Otherwise rapid ramp: low observation count.
            List<QueryFingerprint> newcomers = recent.stream()
                .filter(qf -> qf.getFirstSeenAt() != null && !qf.getFirstSeenAt().isBefore(since))
                .sorted(Comparator
                    .comparingDouble((QueryFingerprint qf) -> dbTimeBurned(qf))
                    .reversed())
                .limit(3)
                .toList();
            if (newcomers.isEmpty()) {
                return;
            }

            sb.append("*").append(style.newcomerTitle()).append("*\n");
            sb.append("  Queries that did not exist before this window:\n");
            int shown = 0;
            for (QueryFingerprint qf : newcomers) {
                shown++;
                String preview = normalizedPreview(qf.getNormalizedQuery() != null
                    ? qf.getNormalizedQuery() : qf.getSampleQuery());
                String idSuffix = qf.getFingerprint() != null && !qf.getFingerprint().isBlank()
                    ? String.format(" `(id: %s)`", shortId(qf.getFingerprint()))
                    : "";
                sb.append(String.format("\n  _%d. %s_%s\n", shown, preview, idSuffix));
                if (qf.getCurrentAvgTimeMs() != null) {
                    sb.append(String.format("     avg *%.0f ms*", qf.getCurrentAvgTimeMs()));
                }
                if (qf.getCurrentCallCount() != null) {
                    sb.append(String.format(" · called *%s×*", formatCount(qf.getCurrentCallCount())));
                }
                double burnedMs = dbTimeBurned(qf);
                if (burnedMs >= 60_000) {
                    sb.append(" · *").append(formatDbTime(burnedMs)).append("* total DB time");
                    double monthlyUsd = computeCostUsdMonthly(burnedMs);
                    if (monthlyUsd >= 1.0) {
                        sb.append(String.format(" · ≈ *%s/mo* in compute", formatUsd(monthlyUsd)));
                    }
                }
                sb.append("\n");
                if (qf.getFirstSeenAt() != null) {
                    sb.append("     first seen ").append(qf.getFirstSeenAt().format(EVENT_TIME_FMT)).append("\n");
                }
                if (qf.getAffectedTables() != null && !qf.getAffectedTables().isEmpty()) {
                    sb.append("     tables: `").append(String.join("`, `", qf.getAffectedTables())).append("`\n");
                }
            }
            sb.append("\n");
        } catch (Exception e) {
            log.debug("Newcomers section skipped for {}: {}", connectionId, e.getMessage());
        }
    }

    private static double dbTimeBurned(QueryFingerprint qf) {
        double avg = qf.getCurrentAvgTimeMs() != null ? qf.getCurrentAvgTimeMs() : 0.0;
        long calls = qf.getCurrentCallCount() != null ? qf.getCurrentCallCount() : 0L;
        return avg * calls;
    }

    private DigestStyle chooseStyle(String connectionId) {
        long digestCount = digestLogRepository.countByConnectionIdAndChannelIdIsNull(connectionId);
        int index = (int) (digestCount % DIGEST_TITLES.size());
        List<String> order = switch (index) {
            case 1 -> SECTION_ORDER_B;
            case 2 -> SECTION_ORDER_C;
            default -> SECTION_ORDER_A;
        };
        return new DigestStyle(
            DIGEST_TITLES.get(index),
            SUMMARY_TITLES.get(index),
            SPOTLIGHT_TITLES.get(index),
            SLOW_TITLES.get(index),
            CUSTOMERS_TITLES.get(index),
            FREE_WIN_TITLES.get(index),
            GROWTH_TITLES.get(index),
            SCHEMA_TITLES.get(index),
            SECURITY_TITLES.get(index),
            CONCURRENCY_TITLES.get(index),
            WASTE_TITLES.get(index),
            NEWCOMER_TITLES.get(index),
            SAVINGS_TITLES.get(index),
            INDEX_WINS_TITLES.get(index),
            DEEP_DIVE_TITLES.get(index),
            FOOTERS.get(index),
            order,
            digestCount
        );
    }

    private void appendExecutiveSummary(StringBuilder sb,
                                        DigestStyle style,
                                        SlowQueryAnalysis analysis,
                                        List<SlowQueryHistorySummary> history,
                                        GrowthDigestData growthData,
                                        SchemaDigestData schemaData,
                                        @Nullable SlackDigestLog previousDigest) {
        sb.append("*").append(style.summaryTitle()).append("*\n");
        List<String> bullets = new ArrayList<>();

        if (analysis != null && analysis.getTotalSlowQueries() != null) {
            long total = analysis.getTotalSlowQueries();
            long critical = analysis.getCountBySeverity(SlowQuery.Severity.CRITICAL);
            if (history != null && history.size() >= 2) {
                long yesterday = history.get(1).getTotalSlowQueries() != null ? history.get(1).getTotalSlowQueries() : 0;
                long delta = total - yesterday;
                bullets.add(String.format(
                    "Slow-query volume is %s versus yesterday at %s total findings, with %s critical items.",
                    delta > 0 ? ("up by " + delta) : delta < 0 ? ("down by " + Math.abs(delta)) : "flat",
                    formatCount(total),
                    formatCount(critical)
                ));
            } else {
                bullets.add(String.format(
                    "The last 24h logged %s slow queries, including %s critical issues.",
                    formatCount(total),
                    formatCount(critical)
                ));
            }
        }

        if (!schemaData.changes().isEmpty()) {
            long breaking = schemaData.changes().stream().filter(change -> Boolean.TRUE.equals(change.getIsBreakingChange())).count();
            bullets.add(breaking > 0
                ? String.format("Fresh schema movement includes %d breaking change(s) that deserve review.", breaking)
                : String.format("Schema changed across %d object(s) since the prior captured snapshot.", schemaData.changes().size()));
        } else if (schemaData.comparedFromSnapshots() && schemaData.currentSnapshotAt() != null) {
            bullets.add("The latest schema snapshot diff is clean, so there is no structural drift to surface today.");
        }

        if (!growthData.topGrowing().isEmpty()) {
            TableStatsHistory leader = growthData.topGrowing().getFirst();
            bullets.add(String.format(
                "Biggest footprint change is `%s`, up %s in the latest sample.",
                leader.getTableName(),
                formatBytes(leader.getSizeGrowthBytes() != null ? leader.getSizeGrowthBytes() : 0L)
            ));
        } else if (growthData.bootstrapped()) {
            bullets.add(String.format(
                "Growth tracking was initialized for %d table(s) in this run, so the next hourly digest will have true deltas.",
                growthData.latestSnapshots().size()
            ));
        }

        if (previousDigest != null && previousDigest.getHeadline() != null && !previousDigest.getHeadline().isBlank()) {
            bullets.add("Previous briefing focus was: “" + previousDigest.getHeadline() + "”.");
        }

        bullets.stream().filter(Objects::nonNull).limit(3).forEach(bullet -> sb.append("  • ").append(bullet).append("\n"));
        sb.append("\n");
    }

    private GrowthDigestData loadGrowthDigestData(String connectionId, LocalDateTime since) {
        List<TableStatsHistory> latest = tableStatsHistoryRepository.findLatestSnapshotsForConnection(connectionId);
        boolean bootstrapped = false;
        if (latest.isEmpty()) {
            try {
                tableGrowthMonitoringService.manualTrigger(connectionId);
                latest = tableStatsHistoryRepository.findLatestSnapshotsForConnection(connectionId);
                bootstrapped = !latest.isEmpty();
            } catch (Exception e) {
                log.warn("Could not bootstrap growth snapshots for {}: {}", connectionId, e.getMessage());
            }
        }

        List<TableStatsHistory> topGrowing = latest.stream()
            .filter(h -> h.getSizeGrowthBytes() != null && h.getSizeGrowthBytes() > 0)
            .sorted(Comparator.comparingLong(TableStatsHistory::getSizeGrowthBytes).reversed())
            .limit(TOP_TABLES)
            .toList();

        Map<String, CapacityForecast> forecastMap = new LinkedHashMap<>();
        try {
            capacityForecastRepository.findByConnectionIdOrderByForecastDateDesc(connectionId)
                .forEach(forecast -> {
                    if (forecast.getTableName() != null) {
                        forecastMap.putIfAbsent(forecast.getTableName(), forecast);
                    }
                });
        } catch (Exception e) {
            log.debug("Could not load capacity forecasts for {}: {}", connectionId, e.getMessage());
        }

        Map<String, GrowthAnomaly> anomalyMap = new LinkedHashMap<>();
        try {
            growthAnomalyRepository.findRecentAnomalies(connectionId, since)
                .forEach(anomaly -> {
                    if (anomaly.getTableName() != null) {
                        anomalyMap.putIfAbsent(anomaly.getTableName(), anomaly);
                    }
                });
        } catch (Exception e) {
            log.debug("Could not load growth anomalies for {}: {}", connectionId, e.getMessage());
        }

        return new GrowthDigestData(latest, topGrowing, forecastMap, anomalyMap, bootstrapped);
    }

    private SchemaDigestData loadSchemaDigestData(String connectionId, LocalDateTime since) {
        try {
            List<SchemaChange> changes = dedupeSchemaChanges(
                schemaChangeRepository.findByConnectionIdAndDetectedAtBetweenOrderByDetectedAtDesc(
                    connectionId, since, LocalDateTime.now())
            );
            if (!changes.isEmpty()) {
                return new SchemaDigestData(changes, false, null, null);
            }

            List<SchemaSnapshot> snapshots = schemaChangeTrackingService.getRecentSnapshots(connectionId);
            if (snapshots.size() >= 2) {
                SchemaSnapshot current = snapshots.get(0);
                SchemaSnapshot previous = snapshots.get(1);
                List<SchemaChange> compared = dedupeSchemaChanges(
                    schemaChangeTrackingService.compareSnapshots(previous.getId(), current.getId())
                );
                return new SchemaDigestData(compared, true, current.getCapturedAt(), previous.getCapturedAt());
            }
        } catch (Exception e) {
            log.warn("Could not load schema digest data for {}: {}", connectionId, e.getMessage());
        }
        return new SchemaDigestData(List.of(), false, null, null);
    }

    private List<SchemaChange> dedupeSchemaChanges(List<SchemaChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return List.of();
        }
        Map<String, SchemaChange> unique = new LinkedHashMap<>();
        for (SchemaChange change : changes) {
            String key = String.join("|",
                Objects.toString(change.getChangeType(), ""),
                Objects.toString(change.getObjectType(), ""),
                Objects.toString(change.getObjectName(), ""),
                Objects.toString(change.getTableName(), "")
            );
            unique.putIfAbsent(key, change);
        }
        return unique.values().stream().limit(5).toList();
    }

    private Map<String, String> resolveIdentifierLabels(String connectionId,
                                                        String columnName,
                                                        List<String> values,
                                                        Map<String, Map<String, String>> labelCache) {
        String cacheKey = connectionId + "|" + columnName.toLowerCase(Locale.ROOT);
        Map<String, String> resolved = labelCache.computeIfAbsent(cacheKey, ignored -> new LinkedHashMap<>());
        List<String> unresolvedValues = values.stream()
            .filter(value -> value != null && !value.isBlank())
            .filter(value -> !resolved.containsKey(value))
            .distinct()
            .toList();
        if (unresolvedValues.isEmpty()) {
            return resolved;
        }

        try {
            String entityRoot = deriveEntityRoot(columnName);
            if (entityRoot == null || entityRoot.isBlank()) {
                return resolved;
            }

            List<DatabaseObject> tables = queryExecutorService.getDatabaseObjects(connectionId).stream()
                .filter(object -> object.getName() != null && object.getType() != null && "table".equalsIgnoreCase(object.getType()))
                .toList();
            ConnectionRequest connection = credentialService.getDecryptedConnection(connectionId);
            for (DatabaseObject lookupTable : rankLookupTables(entityRoot, columnName, tables)) {
                if (lookupTable.getColumns() == null || lookupTable.getColumns().isEmpty()) {
                    continue;
                }

                String idColumn = pickIdColumn(columnName, entityRoot, lookupTable.getColumns());
                String labelColumn = pickLabelColumn(entityRoot, lookupTable.getColumns());
                if (idColumn == null || labelColumn == null) {
                    continue;
                }

                String sql = buildLookupSql(connection, lookupTable, idColumn, labelColumn, unresolvedValues);
                QueryResult queryResult = queryExecutorService.executeQuery(
                    connectionId,
                    new QueryRequest(sql, 20, 20),
                    QueryExecutionContext.scheduled()
                );
                int idIndex = findColumnIndexIgnoreCase(queryResult.getColumns(), "lookup_id");
                int labelIndex = findColumnIndexIgnoreCase(queryResult.getColumns(), "lookup_label");
                if (idIndex < 0) {
                    idIndex = findColumnIndexIgnoreCase(queryResult.getColumns(), idColumn);
                }
                if (labelIndex < 0) {
                    labelIndex = findColumnIndexIgnoreCase(queryResult.getColumns(), labelColumn);
                }
                if (idIndex < 0 && queryResult.getColumns() != null && !queryResult.getColumns().isEmpty()) {
                    idIndex = 0;
                }
                if (labelIndex < 0 && queryResult.getColumns() != null && queryResult.getColumns().size() > 1) {
                    labelIndex = 1;
                }
                if (idIndex < 0 || labelIndex < 0 || queryResult.getRows() == null) {
                    continue;
                }

                for (List<Object> row : queryResult.getRows()) {
                    if (row.size() <= Math.max(idIndex, labelIndex)) {
                        continue;
                    }
                    String rawId = Objects.toString(row.get(idIndex), null);
                    String label = Objects.toString(row.get(labelIndex), null);
                    if (rawId != null && label != null && !label.isBlank()) {
                        resolved.put(rawId, label);
                    }
                }

                if (unresolvedValues.stream().allMatch(resolved::containsKey)) {
                    break;
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve labels for {} on {}: {}", columnName, connectionId, e.getMessage());
        }

        return resolved;
    }

    private int findColumnIndexIgnoreCase(@Nullable List<String> columns, String columnName) {
        if (columns == null || columnName == null) {
            return -1;
        }
        for (int i = 0; i < columns.size(); i++) {
            if (columnName.equalsIgnoreCase(columns.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private List<DatabaseObject> rankLookupTables(String entityRoot, String columnName, List<DatabaseObject> tables) {
        return tables.stream()
            .filter(table -> table.getColumns() != null && !table.getColumns().isEmpty())
            .map(table -> Map.entry(table, scoreLookupTable(table, entityRoot, columnName)))
            .filter(entry -> entry.getValue() > 0)
            .sorted(Map.Entry.<DatabaseObject, Integer>comparingByValue().reversed())
            .map(Map.Entry::getKey)
            .toList();
    }

    private int scoreLookupTable(DatabaseObject table, String entityRoot, String columnName) {
        String tableName = table.getName().toLowerCase(Locale.ROOT);
        int score = 0;
        if (tableName.equals(entityRoot)) score += 120;
        if (tableName.equals(entityRoot + "s") || tableName.equals(entityRoot + "es")) score += 110;
        if (tableName.startsWith(entityRoot + "_") || tableName.endsWith("_" + entityRoot)) score += 90;
        if (tableName.contains(entityRoot)) score += 60;

        String idColumn = pickIdColumn(columnName, entityRoot, table.getColumns());
        String labelColumn = pickLabelColumn(entityRoot, table.getColumns());
        if (idColumn != null) score += 40;
        if (labelColumn != null) score += 35;
        if (hasColumn(table.getColumns(), columnName)) score += 45;
        if (hasColumn(table.getColumns(), entityRoot + "_name")) score += 55;
        if (hasColumn(table.getColumns(), columnName) && hasColumn(table.getColumns(), entityRoot + "_name")) score += 80;
        return score;
    }

    private boolean hasColumn(@Nullable List<ColumnInfo> columns, String columnName) {
        if (columns == null || columnName == null) {
            return false;
        }
        return columns.stream()
            .map(ColumnInfo::getName)
            .filter(Objects::nonNull)
            .anyMatch(name -> name.equalsIgnoreCase(columnName));
    }

    private String pickIdColumn(String columnName, String entityRoot, List<ColumnInfo> columns) {
        if (columns == null || columns.isEmpty()) {
            return null;
        }
        List<String> candidates = List.of(
            columnName,
            entityRoot + "_id",
            entityRoot + "id",
            "id"
        );
        for (String candidate : candidates) {
            Optional<ColumnInfo> match = columns.stream()
                .filter(column -> column.getName() != null && column.getName().equalsIgnoreCase(candidate))
                .findFirst();
            if (match.isPresent()) {
                return match.get().getName();
            }
        }
        return columns.stream()
            .filter(column -> column.getName() != null && column.getName().toLowerCase(Locale.ROOT).endsWith("_id"))
            .map(ColumnInfo::getName)
            .findFirst()
            .orElse(null);
    }

    private String pickLabelColumn(String entityRoot, List<ColumnInfo> columns) {
        if (columns == null || columns.isEmpty()) {
            return null;
        }
        List<String> preferred = new ArrayList<>();
        preferred.add(entityRoot + "_name");
        preferred.addAll(LABEL_COLUMN_CANDIDATES);
        for (String candidate : preferred) {
            Optional<ColumnInfo> match = columns.stream()
                .filter(column -> column.getName() != null && column.getName().equalsIgnoreCase(candidate))
                .findFirst();
            if (match.isPresent()) {
                return match.get().getName();
            }
        }
        return null;
    }

    private String buildLookupSql(ConnectionRequest connection,
                                  DatabaseObject table,
                                  String idColumn,
                                  String labelColumn,
                                  List<String> values) {
        String dbType = connection.getDbType() != null ? connection.getDbType().toLowerCase(Locale.ROOT) : "";
        String tableRef = qualifyIdentifier(table.getSchema(), table.getName(), dbType);
        String idRef = quoteIdentifier(idColumn, dbType);
        String labelRef = quoteIdentifier(labelColumn, dbType);
        String literalList = values.stream()
            .map(this::toSqlLiteral)
            .collect(Collectors.joining(", "));
        return String.format(
            "SELECT %s AS lookup_id, %s AS lookup_label FROM %s WHERE %s IN (%s)",
            idRef,
            labelRef,
            tableRef,
            idRef,
            literalList
        );
    }

    private String deriveEntityRoot(String columnName) {
        if (columnName == null || columnName.isBlank()) {
            return null;
        }
        String normalized = columnName.toLowerCase(Locale.ROOT);
        if (normalized.endsWith("_id")) {
            return normalized.substring(0, normalized.length() - 3);
        }
        if (normalized.endsWith("id") && normalized.length() > 2) {
            return normalized.substring(0, normalized.length() - 2);
        }
        return normalized;
    }

    private String blastRadiusLabel(String columnName) {
        String root = deriveEntityRoot(columnName);
        if (root == null || root.isBlank()) {
            return columnName;
        }
        return root.endsWith("s") ? root : root + "s";
    }

    private String formatResolvedValue(String rawValue, @Nullable String resolvedLabel) {
        if (resolvedLabel == null || resolvedLabel.isBlank() || resolvedLabel.equalsIgnoreCase(rawValue)) {
            return "`" + rawValue + "`";
        }
        return "`" + resolvedLabel + "` (`" + rawValue + "`)";
    }

    private String qualifyIdentifier(@Nullable String schema, String name, String dbType) {
        if (schema == null || schema.isBlank() || "public".equalsIgnoreCase(schema)) {
            return quoteIdentifier(name, dbType);
        }
        return quoteIdentifier(schema, dbType) + "." + quoteIdentifier(name, dbType);
    }

    private String quoteIdentifier(String identifier, String dbType) {
        String quote = dbType.contains("mysql") ? "`" : "\"";
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    private String toSqlLiteral(String value) {
        if (value != null && value.matches("-?\\d+(\\.\\d+)?")) {
            return value;
        }
        return "'" + value.replace("'", "''") + "'";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data fetchers (all with graceful fallback)
    // ─────────────────────────────────────────────────────────────────────────

    private SlowQueryInsightsResponse fetchInsights(String connectionId) {
        try {
            return slowQueryInsightsService.getInsights(connectionId, "24h", 5);
        } catch (Exception e) {
            log.debug("Could not fetch insights for {}: {}", connectionId, e.getMessage());
            return null;
        }
    }

    private SlowQueryAnalysis fetchSlowQueryAnalysis(String connectionId) {
        try {
            return slowQueryService.analyzeSlowQueries(
                connectionId, SlowQueryAnalysis.TimeRange.LAST_24_HOURS, 100.0, TOP_SLOW_QUERIES);
        } catch (Exception e) {
            log.debug("Could not fetch slow query analysis for {}: {}", connectionId, e.getMessage());
            return null;
        }
    }

    private List<SlowQueryHistorySummary> fetchHistory(String connectionId) {
        try {
            return slowQueryHistoryService.getRecentHistorySummaries(connectionId);
        } catch (Exception e) {
            log.debug("Could not fetch slow query history for {}: {}", connectionId, e.getMessage());
            return List.of();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Slack posting
    // ─────────────────────────────────────────────────────────────────────────

    private static final int SLACK_MAX_CHARS = 3900; // Slack limit is 4000; leave headroom

    private void postMessage(String channelId, String text) {
        // Slack hard-limits text to 4000 chars; truncate gracefully
        String payload = text.length() > SLACK_MAX_CHARS
            ? text.substring(0, SLACK_MAX_CHARS) + "\n…_(truncated)_"
            : text;

        MethodsClient client = Slack.getInstance().methods(slackRuntimeSettingsService.current().botToken());
        try {
            var response = client.chatPostMessage(ChatPostMessageRequest.builder()
                .channel(channelId)
                .text(payload)
                .build());
            if (!response.isOk()) {
                log.error("Slack API returned error for channel {}: {}", channelId, response.getError());
            }
        } catch (IOException | SlackApiException e) {
            log.error("Failed to post daily digest to channel {}: {}", channelId, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String connectionName(String connectionId) {
        try {
            return credentialService.getAllConnections().stream()
                .filter(c -> connectionId.equals(c.getId()))
                .map(c -> c.getConnectionName())
                .findFirst()
                .orElse(connectionId);
        } catch (Exception e) {
            return connectionId;
        }
    }

    private static String healthEmoji(String health) {
        return switch (health.toUpperCase()) {
            case "EXCELLENT", "GOOD" -> "✅";
            case "FAIR" -> "🟡";
            case "POOR" -> "🟠";
            case "CRITICAL" -> "🔴";
            default -> "⚪";
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cost model — rough RDS-equivalent rates so the digest can attach a
    // dollar figure to waste/opportunity. These are deliberately conservative
    // and easy to override later via @Value if we want per-deployment tuning.
    // Defaults reflect us-east-1 RDS list pricing (Feb 2026):
    //   • gp3 storage:           ~$0.115 / GB-month
    //   • db.r6g.large CPU-hour: ~$0.252 / instance-hour for 2 vCPU = ~$0.126 / vCPU-hour
    // ─────────────────────────────────────────────────────────────────────────
    private static final double USD_PER_GB_MONTH = 0.115;
    private static final double USD_PER_VCPU_HOUR = 0.13;
    /** Don't bother surfacing the savings section unless the total tops this floor. */
    private static final double SAVINGS_USD_FLOOR_MONTHLY = 10.0;

    /** Storage cost for a given byte size, charged monthly. */
    private static double storageCostUsdMonthly(long bytes) {
        if (bytes <= 0) return 0.0;
        double gb = bytes / (double) (1L << 30);
        return gb * USD_PER_GB_MONTH;
    }

    /**
     * Compute cost projection for a query: today's DB time, extrapolated to a month,
     * priced as vCPU-hours. Effectively: "if this query keeps running at this rate
     * for a month, you're paying $X for its cycles."
     */
    private static double computeCostUsdMonthly(double dbTimeMsToday) {
        if (dbTimeMsToday <= 0) return 0.0;
        double hoursPerDay = dbTimeMsToday / 3_600_000.0;
        return hoursPerDay * 30.0 * USD_PER_VCPU_HOUR;
    }

    private static String formatUsd(double dollars) {
        if (dollars >= 1000) return String.format("$%.0f", dollars);
        if (dollars >= 100) return String.format("$%.0f", dollars);
        if (dollars >= 10) return String.format("$%.1f", dollars);
        if (dollars >= 1) return String.format("$%.2f", dollars);
        return String.format("$%.2f", dollars); // never shown below floor anyway
    }

    /** Human-friendly DB-time formatter: minutes → hours → days. */
    private static String formatDbTime(double totalMs) {
        if (totalMs <= 0) return "—";
        double minutes = totalMs / 60_000.0;
        if (minutes < 1) return String.format("%.1f s", totalMs / 1000.0);
        if (minutes < 60) return String.format("%.1f min", minutes);
        double hours = minutes / 60.0;
        if (hours < 24) return String.format("%.1f hr", hours);
        double days = hours / 24.0;
        return String.format("%.1f days", days);
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1_073_741_824L) return String.format("%.1f GB", bytes / 1_073_741_824.0);
        if (bytes >= 1_048_576L) return String.format("%.1f MB", bytes / 1_048_576.0);
        if (bytes >= 1_024L) return String.format("%.1f KB", bytes / 1_024.0);
        return bytes + " B";
    }

    private static String formatCount(long n) {
        if (n >= 1_000_000L) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000L) return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    /**
     * Used by every digest section that needs a one-line query identifier:
     * Slow Query Snapshot, Newcomers, lock-contention blocker queries, etc.
     *
     * <p>Was: {@code q.substring(0, 77) + "…"} — a partial SQL prefix that
     * cut off mid-clause and was unreadable in Slack
     * (e.g. {@code SELECT 'STRTODATE' ( 'pb' . 'date' , ? ) AS 'report' date,
     * r . , plan nam…}).
     *
     * <p>Now: a human-readable label via {@link QueryLabeler} ("Booking
     * lookup by hotel_id", "Room reservations aggregation"). Callers that
     * have a queryId/fingerprint pair the label with {@link #shortId} so
     * users can look up the full text via the CLI / Slow Queries tab.
     */
    private static String normalizedPreview(String query) {
        if (query == null || query.isBlank()) return "(unknown)";
        String label = QueryLabeler.label(query);
        return label != null && !label.isBlank() ? label : "Query";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    static String extractHeadline(String content) {
        if (content == null) return "";
        // Second line of the digest contains the date · headline in italic: _date · headline_
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("_") && trimmed.endsWith("_") && trimmed.contains("·")) {
                String inner = trimmed.substring(1, trimmed.length() - 1);
                int dot = inner.indexOf('·');
                return dot >= 0 ? inner.substring(dot + 1).trim() : inner;
            }
        }
        return "";
    }
}
