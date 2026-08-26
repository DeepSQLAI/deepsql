package com.dbaagent.controller;

import com.dbaagent.model.Project;
import com.dbaagent.service.ProjectService;
import com.dbaagent.service.security.AccessControlService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for projects, optionally filtered by connection.
 *
 * <p><b>Authorization:</b> every endpoint here takes a caller-supplied connection id, so
 * each one asserts access itself ({@code assertCanReadConnectionContent} for reads,
 * {@code assertCanManageConnectionContent} for writes). {@code SecurityConfig} only
 * requires an authenticated principal — nothing upstream inspects a connection id. See
 * {@code ConnectionScopedAuthorizationSafetyTest}.
 */
@RestController
@RequestMapping("/projects")
@RequiredArgsConstructor
public class ProjectController {
    private final ProjectService projectService;
    private final AccessControlService accessControlService;

    @PostMapping
    public ResponseEntity<Project> createProject(@RequestBody CreateProjectRequest request) {
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
        }
        List<Project> projects = connectionId != null
            ? projectService.getProjectsByConnection(connectionId)
            : projectService.getAllProjects();
        return ResponseEntity.ok(projects);
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<Project> getProject(@PathVariable String projectId) {
        return projectService.getProject(projectId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{projectId}")
    public ResponseEntity<Project> updateProject(
        @PathVariable String projectId,
        @RequestBody UpdateProjectRequest request
    ) {
        return projectService.updateProject(projectId, request.getName(), request.getDescription())
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> deleteProject(@PathVariable String projectId) {
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

    @Data
    public static class UpdateProjectRequest {
        private String name;
        private String description;
    }
}
