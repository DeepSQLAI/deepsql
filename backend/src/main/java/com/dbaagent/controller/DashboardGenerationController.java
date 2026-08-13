package com.dbaagent.controller;

import com.dbaagent.service.DashboardAgentService;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Brain-grounded, self-validating dashboard generation for the web Dashboards surface.
 *
 * <ul>
 *   <li>{@code POST /api/dashboards/generate} — blocking; returns the validated config.</li>
 *   <li>{@code POST /api/dashboards/generate/stream} — SSE; streams the agent's live
 *       steps ({@code step} events: grounding → planning → validating) then either a
 *       {@code chat} event (out-of-context reply) or a {@code done} event with the
 *       artifact config (or an {@code error} event).</li>
 * </ul>
 *
 * Read-only: generates a config and validates queries by running them read-only; it never
 * mutates the database. Backed by {@link DashboardAgentService}.
 */
@Slf4j
@RestController
@RequestMapping("/dashboards")
@RequiredArgsConstructor
public class DashboardGenerationController {

    private final DashboardAgentService dashboardAgentService;
    private final AccessControlService accessControlService;

    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody GenerateRequest request) {
        try {
            requireValid(request);
            accessControlService.assertCanReadConnectionContent(request.connectionId());
            Map<String, Object> config = dashboardAgentService.generate(
                request.connectionId(), request.prompt(), request.currentConfig(),
                DashboardAgentService.StepListener.NOOP);
            return ResponseEntity.ok(Map.of("success", true, "dashboardConfig", config));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", safe(e)));
        } catch (Exception e) {
            log.error("Dashboard generation failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "error", safe(e)));
        }
    }

    @PostMapping(value = "/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateStream(@RequestBody GenerateRequest request) {
        requireValid(request);
        accessControlService.assertCanReadConnectionContent(request.connectionId());
        // Coding a whole dashboard (ground + verify every query + write the HTML) can
        // run for minutes. Give it real headroom (10 min) and keep the stream alive
        // with a heartbeat — otherwise it emits only 3 step events and the long idle
        // gap gets cut by nginx/emitter timeouts before `done`, surfacing to the user
        // as "Generation ended unexpectedly".
        SseEmitter emitter = new SseEmitter(600_000L);
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dashboard-generate-hb");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (Exception ignore) {
                // client gone / stream closed — the worker's completion will stop us
            }
        }, 15, 15, TimeUnit.SECONDS);
        // Propagate auth into the worker so downstream RBAC/audit still sees the user.
        SecurityContext securityContext = SecurityContextHolder.getContext();
        Thread.ofVirtual().name("dashboard-generate").start(() -> {
            SecurityContextHolder.setContext(securityContext);
            try {
                Map<String, Object> config = dashboardAgentService.generate(
                    request.connectionId(), request.prompt(), request.currentConfig(),
                    (type, message) -> {
                        try {
                            emitter.send(SseEmitter.event().name("step")
                                .data(Map.of("type", type, "message", message)));
                        } catch (IOException io) {
                            throw new ClientGoneException(io);
                        }
                    });
                // Chat-only replies (greetings / tool questions) must not share the
                // `done` event with a real artifact — the FE's done handler always
                // appends "Done — built…" and auto-saves. A dedicated `chat` event
                // keeps that path from swallowing out-of-context messages.
                if (Boolean.TRUE.equals(config.get("chat"))) {
                    emitter.send(SseEmitter.event().name("chat")
                        .data(Map.of(
                            "success", true,
                            "reply", String.valueOf(config.getOrDefault("reply", "")),
                            "dashboardConfig", config)));
                } else {
                    emitter.send(SseEmitter.event().name("done")
                        .data(Map.of("success", true, "dashboardConfig", config)));
                }
                emitter.complete();
            } catch (ClientGoneException gone) {
                emitter.complete();
            } catch (Exception e) {
                log.warn("Streamed dashboard generation failed: {}", e.getMessage());
                try {
                    emitter.send(SseEmitter.event().name("error")
                        .data(Map.of("success", false, "error", safe(e))));
                } catch (IOException ignore) { }
                emitter.complete();
            } finally {
                heartbeat.shutdownNow();
                SecurityContextHolder.clearContext();
            }
        });
        return emitter;
    }

    private static void requireValid(GenerateRequest request) {
        if (request == null || request.connectionId() == null
            || request.prompt() == null || request.prompt().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "connectionId and prompt are required");
        }
    }

    private static String safe(Exception e) {
        return e.getMessage() == null ? "generation failed" : e.getMessage();
    }

    private static final class ClientGoneException extends RuntimeException {
        ClientGoneException(Throwable cause) { super(cause); }
    }

    public record GenerateRequest(String connectionId, String prompt, Object currentConfig) { }
}
