package com.dbaagent.service;

import com.dbaagent.model.Project;
import com.dbaagent.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectService {
    private final ProjectRepository projectRepository;

    @Transactional
    public Project createProject(String name, String description, String connectionId) {
        Project project = new Project();
        project.setName(name);
        project.setDescription(description);
        project.setConnectionId(connectionId);

        log.info("Creating project: {}", name);
        return projectRepository.save(project);
    }

    public List<Project> getAllProjects() {
        return projectRepository.findAllByOrderByUpdatedAtDesc();
    }

    public List<Project> getProjectsByConnection(String connectionId) {
        return projectRepository.findByConnectionIdOrderByUpdatedAtDesc(connectionId);
    }

    public Optional<Project> getProject(String projectId) {
        return projectRepository.findById(projectId);
    }

    @Transactional
    public Optional<Project> updateProject(String projectId, String name, String description) {
        return projectRepository.findById(projectId).map(project -> {
            if (name != null) project.setName(name);
            if (description != null) project.setDescription(description);
            log.info("Updating project: {}", projectId);
            return projectRepository.save(project);
        });
    }

    @Transactional
    public boolean deleteProject(String projectId) {
        if (projectRepository.existsById(projectId)) {
            log.info("Deleting project: {}", projectId);
            projectRepository.deleteById(projectId);
            return true;
        }
        return false;
    }
}
