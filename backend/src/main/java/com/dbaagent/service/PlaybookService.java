package com.dbaagent.service;

import com.dbaagent.model.Playbook;
import com.dbaagent.model.PlaybookAlert;
import com.dbaagent.repository.PlaybookAlertRepository;
import com.dbaagent.repository.PlaybookRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlaybookService {

    private final PlaybookRepository playbookRepository;
    private final PlaybookAlertRepository playbookAlertRepository;

    /**
     * Get all active playbooks
     */
    public List<Playbook> getAllActivePlaybooks() {
        return playbookRepository.findByIsActiveTrue();
    }

    /**
     * Get all playbooks
     */
    public List<Playbook> getAllPlaybooks() {
        return playbookRepository.findAll();
    }

    /**
     * Get playbooks by category
     */
    public List<Playbook> getPlaybooksByCategory(String category) {
        return playbookRepository.findByCategory(category);
    }

    /**
     * Get playbooks compatible with a database type
     */
    public List<Playbook> getPlaybooksForDbType(String dbType) {
        return playbookRepository.findByDbTypeOrDbType(dbType, "all");
    }

    /**
     * Get system playbooks
     */
    public List<Playbook> getSystemPlaybooks() {
        return playbookRepository.findByIsSystemTrue();
    }

    /**
     * Get playbook by ID
     */
    public Playbook getPlaybook(String playbookId) {
        return playbookRepository.findById(playbookId)
            .orElseThrow(() -> new IllegalArgumentException("Playbook not found: " + playbookId));
    }

    /**
     * Create a new playbook
     */
    @Transactional
    public Playbook createPlaybook(Playbook playbook) {
        playbook.setId(null); // Let the system generate ID
        playbook.setIsSystem(false); // User-created playbooks are not system playbooks
        return playbookRepository.save(playbook);
    }

    /**
     * Update a playbook
     */
    @Transactional
    public Playbook updatePlaybook(String playbookId, Playbook updatedPlaybook) {
        Playbook existing = getPlaybook(playbookId);

        // System playbooks cannot be modified
        if (existing.getIsSystem()) {
            throw new IllegalStateException("Cannot modify system playbook");
        }

        existing.setName(updatedPlaybook.getName());
        existing.setDescription(updatedPlaybook.getDescription());
        existing.setDbType(updatedPlaybook.getDbType());
        existing.setCategory(updatedPlaybook.getCategory());
        existing.setScheduleCron(updatedPlaybook.getScheduleCron());
        existing.setSteps(updatedPlaybook.getSteps());
        existing.setAlertConfig(updatedPlaybook.getAlertConfig());

        return playbookRepository.save(existing);
    }

    /**
     * Toggle playbook active status
     */
    @Transactional
    public Playbook toggleActive(String playbookId) {
        Playbook playbook = getPlaybook(playbookId);
        playbook.setIsActive(!playbook.getIsActive());
        return playbookRepository.save(playbook);
    }

    /**
     * Delete a playbook
     */
    @Transactional
    public void deletePlaybook(String playbookId) {
        Playbook playbook = getPlaybook(playbookId);

        // System playbooks cannot be deleted
        if (playbook.getIsSystem()) {
            throw new IllegalStateException("Cannot delete system playbook");
        }

        playbookRepository.deleteById(playbookId);
    }

    /**
     * Get alerts for a connection
     */
    public List<PlaybookAlert> getAlertsForConnection(String connectionId) {
        return playbookAlertRepository.findByConnectionId(connectionId);
    }

    /**
     * Get unacknowledged alerts for a connection
     */
    public List<PlaybookAlert> getUnacknowledgedAlerts(String connectionId) {
        return playbookAlertRepository.findByConnectionIdAndAcknowledgedFalse(connectionId);
    }

    /**
     * Get recent alerts
     */
    public List<PlaybookAlert> getRecentAlerts(String connectionId, int hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        return playbookAlertRepository.findRecentAlerts(connectionId, since);
    }

    /**
     * Acknowledge an alert
     */
    @Transactional
    public PlaybookAlert acknowledgeAlert(String alertId, String acknowledgedBy) {
        PlaybookAlert alert = playbookAlertRepository.findById(alertId)
            .orElseThrow(() -> new IllegalArgumentException("Alert not found: " + alertId));

        alert.acknowledge(acknowledgedBy);
        return playbookAlertRepository.save(alert);
    }

    /**
     * Get alert statistics for a connection
     */
    public Map<String, Object> getAlertStatistics(String connectionId) {
        long totalAlerts = playbookAlertRepository.findByConnectionId(connectionId).size();
        long unacknowledgedAlerts = playbookAlertRepository.countUnacknowledged(connectionId);

        return Map.of(
            "total_alerts", totalAlerts,
            "unacknowledged_alerts", unacknowledgedAlerts,
            "acknowledged_alerts", totalAlerts - unacknowledgedAlerts
        );
    }
}
