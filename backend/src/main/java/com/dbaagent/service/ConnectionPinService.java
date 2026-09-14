package com.dbaagent.service;

import com.dbaagent.model.ConnectionPin;
import com.dbaagent.repository.ConnectionPinRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The per-user default connection.
 *
 * <p>Callers are responsible for authorizing the connection first — this service takes an
 * already-checked id. {@code ConnectionController} calls
 * {@code assertCanReadConnectionContent} before every pin write, so a pin cannot be used
 * to assert an interest in a connection the caller cannot see.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConnectionPinService {

    private final ConnectionPinRepository pinRepository;

    /** The connection this user opens by default, if they have chosen one. */
    public Optional<String> pinnedConnectionId(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return pinRepository.findByUsernameIgnoreCase(username).map(ConnectionPin::getConnectionId);
    }

    /**
     * Make {@code connectionId} this user's default, replacing any previous pin.
     *
     * <p>Moving the existing row rather than inserting a second one is what keeps "the
     * default" singular; the unique constraint on {@code username} is the backstop. Two
     * pins racing in from different tabs can still collide on that constraint, so the
     * loser re-reads and updates instead of surfacing a 500 for what is really a
     * last-write-wins preference.
     */
    @Transactional
    public void pin(String username, String connectionId) {
        if (username == null || username.isBlank() || connectionId == null || connectionId.isBlank()) {
            return;
        }
        Optional<ConnectionPin> existing = pinRepository.findByUsernameIgnoreCase(username);
        if (existing.isPresent()) {
            ConnectionPin pin = existing.get();
            if (connectionId.equals(pin.getConnectionId())) {
                return;
            }
            pin.setConnectionId(connectionId);
            pinRepository.save(pin);
            return;
        }

        ConnectionPin pin = new ConnectionPin();
        pin.setUsername(username);
        pin.setConnectionId(connectionId);
        try {
            pinRepository.save(pin);
        } catch (DataIntegrityViolationException e) {
            pinRepository.findByUsernameIgnoreCase(username).ifPresent(concurrent -> {
                concurrent.setConnectionId(connectionId);
                pinRepository.save(concurrent);
            });
        }
    }

    /**
     * Clear this user's default, but only when it is still the connection they asked to
     * unpin. A stale click in another tab must not silently drop a pin the user has since
     * moved somewhere else.
     */
    @Transactional
    public void unpin(String username, String connectionId) {
        if (username == null || username.isBlank()) {
            return;
        }
        pinRepository.findByUsernameIgnoreCase(username)
            .filter(pin -> connectionId == null || connectionId.equals(pin.getConnectionId()))
            .ifPresent(pinRepository::delete);
    }

    /** Drop every user's pin on a connection that is being deleted. */
    public void clearPinsForConnection(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return;
        }
        pinRepository.deleteByConnectionId(connectionId);
    }
}
