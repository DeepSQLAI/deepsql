package com.dbaagent.controller;

import com.dbaagent.model.Project;
import com.dbaagent.service.ProjectService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/projects")
@RequiredArgsConstructor
public class ProjectController {
    private final ProjectService projectService;

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
