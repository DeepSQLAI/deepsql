package com.dbaagent.service;

import com.dbaagent.model.ConnectionPin;
import com.dbaagent.repository.ConnectionPinRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectionPinServiceTest {

    @Mock private ConnectionPinRepository pinRepository;
    @InjectMocks private ConnectionPinService service;

    /**
     * The whole point of the feature: a pinned connection is <em>the</em> default. A
     * second pin has to move the existing row, not add another one — otherwise
     * "always the default" is decided by whichever row a query happens to return first.
     */
    @Test
    void pinningASecondConnectionMovesTheExistingPin() {
        ConnectionPin existing = new ConnectionPin();
        existing.setId(7L);
        existing.setUsername("analyst");
        existing.setConnectionId("conn-a");
        when(pinRepository.findByUsernameIgnoreCase("analyst")).thenReturn(Optional.of(existing));

        service.pin("analyst", "conn-b");

        ArgumentCaptor<ConnectionPin> saved = ArgumentCaptor.forClass(ConnectionPin.class);
        verify(pinRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(7L);
        assertThat(saved.getValue().getConnectionId()).isEqualTo("conn-b");
    }

    @Test
    void pinningTheAlreadyPinnedConnectionWritesNothing() {
        ConnectionPin existing = new ConnectionPin();
        existing.setUsername("analyst");
        existing.setConnectionId("conn-a");
        when(pinRepository.findByUsernameIgnoreCase("analyst")).thenReturn(Optional.of(existing));

        service.pin("analyst", "conn-a");

        verify(pinRepository, never()).save(any());
    }

    @Test
    void aFirstPinInsertsARowForThatUser() {
        when(pinRepository.findByUsernameIgnoreCase("analyst")).thenReturn(Optional.empty());

        service.pin("analyst", "conn-a");

        ArgumentCaptor<ConnectionPin> saved = ArgumentCaptor.forClass(ConnectionPin.class);
        verify(pinRepository).save(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("analyst");
        assertThat(saved.getValue().getConnectionId()).isEqualTo("conn-a");
    }

    /**
     * Two tabs pinning at once collide on the unique constraint. That is a preference,
     * not a conflict worth a 500 — the loser re-reads and updates.
     */
    @Test
    void aConcurrentInsertLosesTheRaceAndUpdatesInstead() {
        ConnectionPin winner = new ConnectionPin();
        winner.setId(3L);
        winner.setUsername("analyst");
        winner.setConnectionId("conn-a");
        when(pinRepository.findByUsernameIgnoreCase("analyst"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(winner));
        when(pinRepository.save(any(ConnectionPin.class)))
            .thenThrow(new DataIntegrityViolationException("duplicate key"))
            .thenReturn(winner);

        service.pin("analyst", "conn-b");

        assertThat(winner.getConnectionId()).isEqualTo("conn-b");
    }

    /**
     * A stale click in a background tab must not clear a pin the user has since moved.
     */
    @Test
    void unpinningADifferentConnectionLeavesTheCurrentPinAlone() {
        ConnectionPin existing = new ConnectionPin();
        existing.setUsername("analyst");
        existing.setConnectionId("conn-a");
        when(pinRepository.findByUsernameIgnoreCase("analyst")).thenReturn(Optional.of(existing));

        service.unpin("analyst", "conn-b");

        verify(pinRepository, never()).delete(any());
    }

    @Test
    void unpinningThePinnedConnectionRemovesIt() {
        ConnectionPin existing = new ConnectionPin();
        existing.setUsername("analyst");
        existing.setConnectionId("conn-a");
        when(pinRepository.findByUsernameIgnoreCase("analyst")).thenReturn(Optional.of(existing));

        service.unpin("analyst", "conn-a");

        verify(pinRepository).delete(existing);
    }

    @Test
    void anAnonymousCallerHasNoPinAndWritesNone() {
        assertThat(service.pinnedConnectionId(null)).isEmpty();
        assertThat(service.pinnedConnectionId("  ")).isEmpty();

        service.pin(null, "conn-a");
        service.pin("analyst", null);

        verify(pinRepository, never()).save(any());
    }
}
