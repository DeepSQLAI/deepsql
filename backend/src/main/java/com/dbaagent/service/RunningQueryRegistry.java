package com.dbaagent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks in-flight queries by a client-supplied execution id so a client that
 * gives up can terminate the query it started. Aborting the HTTP request only
 * closes the socket; without this the statement keeps running and holds a pooled
 * connection until it finishes on its own.
 *
 * <p>Entries are short-lived: registered when execution begins and removed in a
 * finally block. The sweep on write is a backstop for a process that died between
 * those two points.
 */
@Service
@Slf4j
public class RunningQueryRegistry {

    private static final Duration MAX_AGE = Duration.ofHours(1);
    private static final int SWEEP_THRESHOLD = 256;

    public record RunningQuery(String connectionId, String sessionPid, String username, Instant startedAt) {}

    private final Map<String, RunningQuery> running = new ConcurrentHashMap<>();

    public void register(String executionId, String connectionId, String sessionPid, String username) {
        if (executionId == null || executionId.isBlank() || sessionPid == null || sessionPid.isBlank()) {
            return;
        }
        if (running.size() > SWEEP_THRESHOLD) {
            sweepExpired();
        }
        running.put(executionId, new RunningQuery(connectionId, sessionPid, username, Instant.now()));
    }

    public void unregister(String executionId) {
        if (executionId != null && !executionId.isBlank()) {
            running.remove(executionId);
        }
    }

    public Optional<RunningQuery> find(String executionId) {
        if (executionId == null || executionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(running.get(executionId));
    }

    private void sweepExpired() {
        Instant cutoff = Instant.now().minus(MAX_AGE);
        Iterator<Map.Entry<String, RunningQuery>> it = running.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().startedAt().isBefore(cutoff)) {
                it.remove();
            }
        }
    }
}
