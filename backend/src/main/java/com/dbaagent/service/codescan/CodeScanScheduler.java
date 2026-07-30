package com.dbaagent.service.codescan;

import com.dbaagent.model.code.CodeScanSource;
import com.dbaagent.repository.CodeScanSourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps active sources with a {@code schedule_cron} value and triggers a
 * rescan when due. v1 only has UPLOAD sources, which cannot re-fetch on their
 * own (we don't retain archive bytes), so this is a no-op for them. The hook
 * is wired now so v2 (Git connector) can plug straight in.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CodeScanScheduler {

    private final CodeScanSourceRepository sourceRepository;

    @Scheduled(cron = "0 */15 * * * *") // every 15 min
    public void sweep() {
        for (CodeScanSource source : sourceRepository.findByActiveTrueAndScheduleCronIsNotNull()) {
            if (source.getKind() == CodeScanSource.Kind.UPLOAD) {
                // upload sources cannot self-rescan in v1 — UI will surface this
                continue;
            }
            // v2: Git source — pull the latest commit and call CodeScanService.startScan(...)
            log.debug("scheduled rescan deferred to v2 for source {}", source.getId());
        }
    }
}
