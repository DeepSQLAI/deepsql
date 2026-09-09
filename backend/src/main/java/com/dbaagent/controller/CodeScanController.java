package com.dbaagent.controller;

import com.dbaagent.model.code.CodeKnowledgeSuggestion;
import com.dbaagent.model.code.CodeScanJob;
import com.dbaagent.model.code.CodeScanSource;
import com.dbaagent.service.codescan.CodeScanService;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/code-scan")
@RequiredArgsConstructor
@Slf4j
public class CodeScanController {

    private final CodeScanService codeScanService;
    private final AccessControlService accessControlService;

    // ----- sources -----

    @PostMapping(value = "/sources", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CodeScanSource> createSource(
        @RequestParam("connectionId") String connectionId,
        @RequestParam(value = "name", required = false) String name,
        @RequestParam(value = "focus", required = false) String focus,
        @RequestParam("file") MultipartFile file
    ) throws IOException {
        accessControlService.assertCanManageConnectionContent(connectionId);
        return ResponseEntity.ok(
            codeScanService.createSourceFromUpload(
                connectionId,
                file,
                name,
                focus,
                accessControlService.getCurrentUsername()
            )
        );
    }

    /** Update focus text without triggering a scan. */
    @PutMapping("/sources/{sourceId}/focus")
    public ResponseEntity<CodeScanSource> updateFocus(
        @PathVariable String sourceId,
        @RequestParam("connectionId") String connectionId,
        @RequestBody UpdateFocusRequest body
    ) {
        accessControlService.assertCanManageConnectionContent(connectionId);
        return ResponseEntity.ok(
            codeScanService.updateFocus(sourceId, body == null ? null : body.focus())
        );
    }

    @GetMapping("/sources")
    public ResponseEntity<List<CodeScanSource>> listSources(@RequestParam("connectionId") String connectionId) {
        accessControlService.assertCanManageConnectionContent(connectionId);
        return ResponseEntity.ok(codeScanService.listSources(connectionId));
    }

    @DeleteMapping("/sources/{sourceId}")
    public ResponseEntity<Void> deleteSource(@PathVariable String sourceId,
                                             @RequestParam("connectionId") String connectionId) {
        accessControlService.assertCanManageConnectionContent(connectionId);
        codeScanService.deleteSource(sourceId);
        return ResponseEntity.ok().build();
    }

    // ----- jobs -----

    @PostMapping(value = "/sources/{sourceId}/scan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CodeScanJob> startScan(
        @PathVariable String sourceId,
        @RequestParam("connectionId") String connectionId,
        @RequestParam(value = "focus", required = false) String focus,
        @RequestParam("file") MultipartFile file
    ) throws IOException {
        accessControlService.assertCanManageConnectionContent(connectionId);
        return ResponseEntity.ok(
            codeScanService.startScan(sourceId, file, focus, accessControlService.getCurrentUsername())
        );
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<CodeScanJob> getJob(@PathVariable String jobId,
                                              @RequestParam("connectionId") String connectionId) {
        accessControlService.assertCanManageConnectionContent(connectionId);
        return codeScanService.getJob(jobId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/sources/{sourceId}/jobs")
    public ResponseEntity<List<CodeScanJob>> listJobs(@PathVariable String sourceId,
                                                      @RequestParam("connectionId") String connectionId) {
        accessControlService.assertCanManageConnectionContent(connectionId);
        return ResponseEntity.ok(codeScanService.recentJobs(sourceId));
    }

    @GetMapping(value = "/jobs/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamJob(@PathVariable String jobId,
                                @RequestParam("connectionId") String connectionId) {
        accessControlService.assertCanManageConnectionContent(connectionId);
        return codeScanService.subscribeJob(jobId);
    }

    // ----- suggestions -----

    @GetMapping("/suggestions")
    public ResponseEntity<Page<CodeKnowledgeSuggestion>> listSuggestions(
        @RequestParam("connectionId") String connectionId,
        @RequestParam(value = "status", defaultValue = "PENDING") String status,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "50") int size
    ) {
        accessControlService.assertCanManageConnectionContent(connectionId);
        CodeKnowledgeSuggestion.Status parsed = parseStatus(status);
        return ResponseEntity.ok(
            codeScanService.listSuggestions(connectionId, parsed, page, Math.min(size, 200))
        );
    }

    @PostMapping("/suggestions/{suggestionId}/decide")
    public ResponseEntity<CodeKnowledgeSuggestion> decide(
        @PathVariable String suggestionId,
        @RequestParam("connectionId") String connectionId,
        @RequestBody DecideRequest body
    ) {
        accessControlService.assertCanManageConnectionContent(connectionId);
        return ResponseEntity.ok(
            codeScanService.decide(
                suggestionId,
                body == null ? null : body.decision(),
                accessControlService.getCurrentUsername(),
                body == null ? null : body.note()
            )
        );
    }

    @PostMapping("/suggestions/bulk-decide")
    public ResponseEntity<Map<String, Object>> bulkDecide(
        @RequestParam("connectionId") String connectionId,
        @RequestBody BulkDecideRequest body
    ) {
        accessControlService.assertCanManageConnectionContent(connectionId);
        if (body == null || body.ids() == null || body.ids().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ids required"));
        }
        var result = codeScanService.bulkDecide(
            body.ids(),
            body.decision(),
            accessControlService.getCurrentUsername(),
            body.note()
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requested", body.ids().size());
        payload.put("succeeded", result.succeeded().size());
        payload.put("failed", result.failures().size());
        payload.put("failures", result.failures());
        return ResponseEntity.ok(payload);
    }

    private static CodeKnowledgeSuggestion.Status parseStatus(String s) {
        try {
            return CodeKnowledgeSuggestion.Status.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return CodeKnowledgeSuggestion.Status.PENDING;
        }
    }

    public record DecideRequest(String decision, String note) {}
    public record BulkDecideRequest(List<String> ids, String decision, String note) {}
    public record UpdateFocusRequest(String focus) {}

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> handleIO(IOException e) {
        log.warn("code-scan upload rejected: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
