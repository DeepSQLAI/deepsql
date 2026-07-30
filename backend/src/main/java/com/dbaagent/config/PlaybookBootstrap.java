package com.dbaagent.config;

import com.dbaagent.model.Playbook;
import com.dbaagent.repository.PlaybookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bootstrap system playbooks on application startup
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlaybookBootstrap implements ApplicationRunner {

    private final PlaybookRepository playbookRepository;

    @Override
    public void run(ApplicationArguments args) {
        log.info("Bootstrapping system playbooks...");

        // Check if system playbooks already exist
        List<Playbook> existingPlaybooks = playbookRepository.findByIsSystemTrue();
        if (!existingPlaybooks.isEmpty()) {
            log.info("System playbooks already exist ({} found), skipping bootstrap", existingPlaybooks.size());
            return;
        }

        // Create system playbooks
        List<Playbook> systemPlaybooks = createSystemPlaybooks();

        // Save all playbooks
        playbookRepository.saveAll(systemPlaybooks);

        log.info("Successfully bootstrapped {} system playbooks", systemPlaybooks.size());
    }

    private List<Playbook> createSystemPlaybooks() {
        List<Playbook> playbooks = new ArrayList<>();

        // 1. Daily Health Check
        Playbook dailyHealth = Playbook.builder()
            .id("pb-daily-health")
            .name("Daily Health Check")
            .description("Comprehensive daily database health assessment covering connections, slow queries, index usage, and table bloat")
            .dbType("all")
            .category("health")
            .scheduleCron("0 9 * * *")
            .isActive(true)
            .isSystem(true)
            .steps(List.of(
                createStep("check_connection_count", "Check Active Connections",
                    null, Map.of("warning", 80, "critical", 95)),
                createStep("analyze_slow_queries", "Analyze Slow Queries",
                    Map.of("limit", 10, "min_duration_ms", 1000), null),
                createStep("check_index_usage", "Check Index Usage",
                    Map.of("min_scans", 100), null),
                createStep("check_table_bloat", "Check Table Bloat",
                    null, Map.of("bloat_ratio", 0.3)),
                createStep("generate_summary", "Generate Health Summary", null, null)
            ))
            .alertConfig(createAlertConfig(List.of("browser"), "warning"))
            .build();
        playbooks.add(dailyHealth);

        // 2. High CPU Investigation
        Playbook highCpu = Playbook.builder()
            .id("pb-high-cpu")
            .name("High CPU Investigation")
            .description("Troubleshooting workflow for high CPU usage - identifies expensive queries, missing indexes, and connection issues")
            .dbType("all")
            .category("troubleshooting")
            .scheduleCron(null)
            .isActive(true)
            .isSystem(true)
            .steps(List.of(
                createStep("get_active_queries", "Get Active Queries", null, null),
                createStep("get_long_running_queries", "Find Long Running Queries",
                    Map.of("min_duration_ms", 5000), null),
                createStep("analyze_query_plans", "Analyze Query Plans", null, null),
                createStep("check_missing_indexes", "Check for Missing Indexes", null, null),
                createStep("check_connection_count", "Check Connection Count", null, null),
                createStep("generate_recommendations", "Generate Recommendations", null, null)
            ))
            .alertConfig(createAlertConfig(List.of("browser", "slack"), "critical"))
            .build();
        playbooks.add(highCpu);

        // 3. Slow Query Analysis
        Playbook slowQuery = Playbook.builder()
            .id("pb-slow-query")
            .name("Slow Query Analysis")
            .description("In-depth analysis of slow queries with execution plans and optimization recommendations")
            .dbType("all")
            .category("performance")
            .scheduleCron(null)
            .isActive(true)
            .isSystem(true)
            .steps(List.of(
                createStep("get_slow_queries", "Get Slow Queries",
                    Map.of("limit", 20, "min_duration_ms", 1000), null),
                createStep("analyze_query_patterns", "Analyze Query Patterns", null, null),
                createStep("explain_queries", "Explain Query Plans", null, null),
                createStep("suggest_indexes", "Suggest Index Optimizations", null, null),
                createStep("generate_recommendations", "Generate Optimization Plan", null, null)
            ))
            .alertConfig(createAlertConfig(List.of("browser"), "warning"))
            .build();
        playbooks.add(slowQuery);

        // 4. Connection Pool Monitoring
        Playbook connectionPool = Playbook.builder()
            .id("pb-connection-pool")
            .name("Connection Pool Monitoring")
            .description("Monitors connection pool health, identifies connection leaks, and recommends pool sizing")
            .dbType("all")
            .category("monitoring")
            .scheduleCron("*/30 * * * *")
            .isActive(true)
            .isSystem(true)
            .steps(List.of(
                createStep("get_connection_stats", "Get Connection Statistics", null, null),
                createStep("detect_connection_leaks", "Detect Connection Leaks", null, null),
                createStep("analyze_connection_patterns", "Analyze Connection Patterns", null, null),
                createStep("recommend_pool_size", "Recommend Pool Sizing", null, null)
            ))
            .alertConfig(createAlertConfig(List.of("browser"), "warning"))
            .build();
        playbooks.add(connectionPool);

        // 5. Index Optimization Workflow
        Playbook indexOpt = Playbook.builder()
            .id("pb-index-optimization")
            .name("Index Optimization Workflow")
            .description("Comprehensive index analysis: identifies missing, unused, and duplicate indexes")
            .dbType("all")
            .category("performance")
            .scheduleCron("0 2 * * 0")
            .isActive(true)
            .isSystem(true)
            .steps(List.of(
                createStep("scan_all_indexes", "Scan All Indexes", null, null),
                createStep("find_unused_indexes", "Find Unused Indexes",
                    Map.of("min_days", 30), null),
                createStep("find_duplicate_indexes", "Find Duplicate Indexes", null, null),
                createStep("find_missing_indexes", "Find Missing Indexes", null, null),
                createStep("suggest_indexes", "Calculate Index Impact", null, null),
                createStep("generate_recommendations", "Generate Index Maintenance Plan", null, null)
            ))
            .alertConfig(createAlertConfig(List.of("browser", "email"), "info"))
            .build();
        playbooks.add(indexOpt);

        return playbooks;
    }

    private Playbook.PlaybookStep createStep(String tool, String name,
                                            Map<String, Object> params,
                                            Map<String, Object> thresholds) {
        Playbook.PlaybookStep step = new Playbook.PlaybookStep();
        step.setTool(tool);
        step.setName(name);
        step.setParams(params);
        step.setThresholds(thresholds);
        return step;
    }

    private Playbook.AlertConfig createAlertConfig(List<String> channels, String severityThreshold) {
        Playbook.AlertConfig config = new Playbook.AlertConfig();
        config.setChannels(channels);
        config.setSeverityThreshold(severityThreshold);
        return config;
    }
}
