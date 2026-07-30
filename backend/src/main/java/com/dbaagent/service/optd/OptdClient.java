package com.dbaagent.service.optd;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Optional;

import static com.dbaagent.service.optd.OptdModels.OptdOptimizeRequest;
import static com.dbaagent.service.optd.OptdModels.OptdOptimizeResponse;

@Component
@Slf4j
public class OptdClient {

    private final WebClient webClient;
    private final boolean enabled;
    private final Duration timeout;

    public OptdClient(
            @Value("${optd.sidecar.base-url:http://localhost:8088}") String baseUrl,
            @Value("${optd.enabled:true}") boolean enabled,
            @Value("${optd.request-timeout-ms:3000}") long timeoutMs
    ) {
        this.webClient = WebClient.builder()
            .baseUrl(baseUrl)
            .build();
        this.enabled = enabled;
        this.timeout = Duration.ofMillis(timeoutMs);
        log.info("optd client configured: enabled={}, baseUrl={}, timeoutMs={}", enabled, baseUrl, timeoutMs);
    }

    public Optional<OptdOptimizeResponse> optimize(OptdOptimizeRequest request) {
        if (!enabled) {
            return Optional.empty();
        }

        try {
            String queryPreview = request.getQuery() != null
                ? request.getQuery().substring(0, Math.min(300, request.getQuery().length()))
                : "null";
            int tableCount = request.getSchema() != null && request.getSchema().getTables() != null
                ? request.getSchema().getTables().size()
                : 0;
            log.info("optd request - dbType: {}, queryLen: {}, schemaTableCount: {}",
                request.getDbType(),
                request.getQuery() != null ? request.getQuery().length() : 0,
                tableCount);
            log.debug("optd query preview: {}", queryPreview);

            OptdOptimizeResponse response = webClient.post()
                .uri("/v1/optimize")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(OptdOptimizeResponse.class)
                .block(timeout);

            if (response != null) {
                log.info("optd response - hasPlanSig: {}, cost: {}, rows: {}, warnings: {}",
                    response.getPlanSignature() != null && !response.getPlanSignature().isBlank(),
                    response.getEstimatedCost(),
                    response.getEstimatedRows(),
                    response.getWarnings() != null ? response.getWarnings().size() : 0);
            } else {
                log.warn("optd returned null response");
            }
            return Optional.ofNullable(response);
        } catch (Exception e) {
            log.warn("optd optimize failed for dbType={}: {}", request.getDbType(), e.getMessage());
            log.debug("optd failed query (first 200 chars): {}",
                request.getQuery() != null ? request.getQuery().substring(0, Math.min(200, request.getQuery().length())) : "null");
            return Optional.empty();
        }
    }
}
