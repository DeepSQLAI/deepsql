package com.dbaagent.controller;

import com.dbaagent.model.SavedDashboard;
import com.dbaagent.service.SavedDashboardService;
import com.dbaagent.service.security.AccessControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #54: create/update/delete (and GETs) must check connection ACL.
 * Write paths authorize against the persisted connectionId, never a body
 * connectionId that could re-home a dashboard onto another connection.
 */
@ExtendWith(MockitoExtension.class)
class SavedDashboardControllerAccessTest {

    @Mock private SavedDashboardService savedDashboardService;
    @Mock private AccessControlService accessControlService;

    private SavedDashboardController controller;

    @BeforeEach
    void setUp() {
        controller = new SavedDashboardController();
        // Field injection mirrors production @Autowired wiring.
        setField(controller, "savedDashboardService", savedDashboardService);
        setField(controller, "accessControlService", accessControlService);
    }

    @Test
    void create_assertsManageOnBodyConnectionId() {
        SavedDashboard incoming = dashboard("conn-allowed", "Ops");
        SavedDashboard saved = dashboard("conn-allowed", "Ops");
        saved.setId(UUID.randomUUID());
        when(savedDashboardService.saveDashboard(incoming)).thenReturn(saved);

        ResponseEntity<Map<String, Object>> response = controller.createDashboard(incoming);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(accessControlService).assertCanManageConnectionContent("conn-allowed");
        verify(savedDashboardService).saveDashboard(incoming);
    }

    @Test
    void create_denied_neverPersists() {
        SavedDashboard incoming = dashboard("conn-denied", "Leak");
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Content access denied for this connection"))
            .when(accessControlService).assertCanManageConnectionContent("conn-denied");

        assertThatThrownBy(() -> controller.createDashboard(incoming))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        verify(savedDashboardService, never()).saveDashboard(any());
    }

    @Test
    void update_assertsManageOnPersistedConnection_notBody() {
        UUID id = UUID.randomUUID();
        SavedDashboard existing = dashboard("conn-real", "Existing");
        existing.setId(id);
        SavedDashboard body = dashboard("conn-spoofed", "Hijack");
        SavedDashboard updated = dashboard("conn-real", "Existing");
        updated.setId(id);

        when(savedDashboardService.getDashboardById(id)).thenReturn(Optional.of(existing));
        when(savedDashboardService.updateDashboard(id, body)).thenReturn(updated);

        ResponseEntity<Map<String, Object>> response = controller.updateDashboard(id, body);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(accessControlService).assertCanManageConnectionContent("conn-real");
        verify(accessControlService, never()).assertCanManageConnectionContent("conn-spoofed");
        verify(savedDashboardService).updateDashboard(id, body);
    }

    @Test
    void update_denied_neverMutates() {
        UUID id = UUID.randomUUID();
        SavedDashboard existing = dashboard("conn-real", "Existing");
        existing.setId(id);
        when(savedDashboardService.getDashboardById(id)).thenReturn(Optional.of(existing));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Content access denied for this connection"))
            .when(accessControlService).assertCanManageConnectionContent("conn-real");

        assertThatThrownBy(() -> controller.updateDashboard(id, dashboard("conn-spoofed", "x")))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        verify(savedDashboardService, never()).updateDashboard(any(), any());
    }

    @Test
    void delete_assertsManageOnPersistedConnection() {
        UUID id = UUID.randomUUID();
        SavedDashboard existing = dashboard("conn-real", "Existing");
        existing.setId(id);
        when(savedDashboardService.getDashboardById(id)).thenReturn(Optional.of(existing));

        ResponseEntity<Map<String, Object>> response = controller.deleteDashboard(id);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(accessControlService).assertCanManageConnectionContent("conn-real");
        verify(savedDashboardService).deleteDashboard(id);
    }

    @Test
    void delete_denied_neverDeletes() {
        UUID id = UUID.randomUUID();
        SavedDashboard existing = dashboard("conn-real", "Existing");
        existing.setId(id);
        when(savedDashboardService.getDashboardById(id)).thenReturn(Optional.of(existing));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Content access denied for this connection"))
            .when(accessControlService).assertCanManageConnectionContent("conn-real");

        assertThatThrownBy(() -> controller.deleteDashboard(id))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);

        verify(savedDashboardService, never()).deleteDashboard(any());
    }

    @Test
    void delete_missing_returns404() {
        UUID id = UUID.randomUUID();
        when(savedDashboardService.getDashboardById(id)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.deleteDashboard(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(accessControlService, never()).assertCanManageConnectionContent(any());
        verify(savedDashboardService, never()).deleteDashboard(any());
    }

    @Test
    void favorite_assertsManageOnPersistedConnection() {
        UUID id = UUID.randomUUID();
        SavedDashboard existing = dashboard("conn-real", "Existing");
        existing.setId(id);
        SavedDashboard toggled = dashboard("conn-real", "Existing");
        toggled.setId(id);
        toggled.setIsFavorite(true);
        when(savedDashboardService.getDashboardById(id)).thenReturn(Optional.of(existing));
        when(savedDashboardService.toggleFavorite(id)).thenReturn(toggled);

        ResponseEntity<Map<String, Object>> response = controller.toggleFavorite(id);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(accessControlService).assertCanManageConnectionContent("conn-real");
        verify(savedDashboardService).toggleFavorite(id);
    }

    @Test
    void getById_assertsReadOnPersistedConnection() {
        UUID id = UUID.randomUUID();
        SavedDashboard existing = dashboard("conn-real", "Existing");
        existing.setId(id);
        when(savedDashboardService.getDashboardById(id)).thenReturn(Optional.of(existing));

        ResponseEntity<Map<String, Object>> response = controller.getDashboardById(id);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(accessControlService).assertCanReadConnectionContent("conn-real");
    }

    @Test
    void listByConnection_assertsRead() {
        when(savedDashboardService.getDashboardsByConnection("conn-1")).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.getDashboardsByConnection("conn-1");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(accessControlService).assertCanReadConnectionContent("conn-1");
    }

    private static SavedDashboard dashboard(String connectionId, String name) {
        SavedDashboard d = new SavedDashboard();
        d.setConnectionId(connectionId);
        d.setName(name);
        d.setDashboardConfig("{}");
        d.setIsFavorite(false);
        d.setIsPublic(false);
        return d;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var field = SavedDashboardController.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
