package com.dbaagent.controller;

import com.dbaagent.model.Project;
import com.dbaagent.service.ProjectService;
import com.dbaagent.service.security.AccessControlService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for projects, optionally filtered by connection.
 *
 * <p><b>Authorization:</b> a project belongs to a connection, so every endpoint is gated
 * on that connection's ACL — directly where the request carries a {@code connectionId},
 * and via the project's own {@code connectionId} where the path carries only a
 * {@code projectId}. {@code SecurityConfig} only requires an authenticated principal;
 * nothing upstream inspects a connection id.
 *
 * <p>The id-keyed endpoints report 404 rather than 403 for a project the caller may not
 * touch, so the route cannot be used to test which project ids exist.
 */
@RestController
@RequestMapping("/projects")
@RequiredArgsConstructor
public class ProjectController {
    private final ProjectService projectService;
    private final AccessControlService accessControlService;

    @PostMapping
    public ResponseEntity<Project> createProject(@RequestBody CreateProjectRequest request) {
        accessControlService.assertCanManageConnectionContent(request.getConnectionId());
        Project project = projectService.createProject(
            request.getName(),
            request.getDescription(),
            request.getConnectionId()
        );
        return ResponseEntity.ok(project);
    }

    @GetMapping
    public ResponseEntity<List<Project>> listProjects(
        @RequestParam(required = false) String connectionId
    ) {
        if (connectionId != null) {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return ResponseEntity.ok(projectService.getProjectsByConnection(connectionId));
        }
        // No filter means "every project on every connection", which cannot be authorized
        // against a single connection's grants — so it is scoped to the caller instead.
        // Access is resolved once per distinct connection, not once per project:
        // ConnectionAccessService.resolveAccess is uncached and hits the grant table, and
        // many projects share a connection.
        Map<String, Boolean> readable = new HashMap<>();
        return ResponseEntity.ok(projectService.getAllProjects().stream()
            .filter(p -> readable.computeIfAbsent(
                    String.valueOf(p.getConnectionId()), c -> canRead(p.getConnectionId())))
            .toList());
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<Project> getProject(@PathVariable String projectId) {
        Project project = requireProject(projectId);
        accessControlService.assertCanReadConnectionContentOrNotFound(
                project.getConnectionId(), "Project");
        return ResponseEntity.ok(project);
    }

    @PutMapping("/{projectId}")
    public ResponseEntity<Project> updateProject(
        @PathVariable String projectId,
        @RequestBody UpdateProjectRequest request
    ) {
        assertCanManageProject(projectId);
        return projectService.updateProject(projectId, request.getName(), request.getDescription())
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> deleteProject(@PathVariable String projectId) {
        assertCanManageProject(projectId);
        return projectService.deleteProject(projectId)
            ? ResponseEntity.ok().build()
            : ResponseEntity.notFound().build();
    }

    @Data
    public static class CreateProjectRequest {
        private String name;
        private String description;
        private String connectionId;
    }

    private Project requireProject(String projectId) {
        return projectService.getProject(projectId)
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Project not found"));
    }

    /** A project id is not a capability: authorize the connection that owns the project. */
    private void assertCanManageProject(String projectId) {
        accessControlService.assertCanManageConnectionContentOrNotFound(
                requireProject(projectId).getConnectionId(), "Project");
    }

    /** Non-throwing read check, for filtering a cross-connection list. */
    private boolean canRead(String connectionId) {
        if (connectionId == null) {
            return false;
        }
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            return true;
        } catch (org.springframework.web.server.ResponseStatusException e) {
            return false;
        }
    }

    @Data
    public static class UpdateProjectRequest {
        private String name;
        private String description;
    }
}
