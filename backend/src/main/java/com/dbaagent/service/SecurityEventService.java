package com.dbaagent.service;

import com.dbaagent.model.SecurityEvent;
import com.dbaagent.model.SecurityEventOutcome;
import com.dbaagent.model.SecurityEventType;
import com.dbaagent.repository.SecurityEventRepository;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SecurityEventService {
    private final SecurityEventRepository securityEventRepository;

    @Transactional
    public SecurityEvent log(EventRequest request) {
        SecurityEvent event = new SecurityEvent();
        event.setEventTypeEnum(request.eventType());
        event.setOutcomeEnum(request.outcome());
        event.setUserId(request.userId());
        event.setActorUserId(request.actorUserId());
        event.setEmail(request.email());
        event.setTargetResource(request.targetResource());
        event.setReason(request.reason());
        event.setClientIp(request.clientIp());
        event.setUserAgent(request.userAgent());
        event.setRequestId(request.requestId());
        event.setEventMetadata(request.metadata());
        return securityEventRepository.save(event);
    }

    @Transactional(readOnly = true)
    public Page<SecurityEvent> search(
        Long userId,
        List<Long> userIds,
        String email,
        String eventType,
        String outcome,
        String clientIp,
        LocalDateTime fromTime,
        LocalDateTime toTime,
        Pageable pageable
    ) {
        Pageable effectivePageable = pageable;
        if (effectivePageable == null) {
            effectivePageable = PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt"));
        } else if (effectivePageable.getSort().isUnsorted()) {
            effectivePageable = PageRequest.of(
                effectivePageable.getPageNumber(),
                effectivePageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")
            );
        }

        String normalizedEmail = normalizeEmail(email);
        String normalizedEventType = normalizeOptional(eventType);
        String normalizedOutcome = normalizeOptional(outcome);
        String normalizedClientIp = normalizeOptional(clientIp);
        Set<Long> normalizedUserIds = normalizeUserIds(userId, userIds);

        Specification<SecurityEvent> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();

            if (!normalizedUserIds.isEmpty()) {
                predicates.add(root.get("userId").in(normalizedUserIds));
            }
            if (normalizedEmail != null) {
                predicates.add(cb.equal(root.get("email"), normalizedEmail));
            }
            if (normalizedEventType != null) {
                predicates.add(cb.equal(root.get("eventType"), normalizedEventType));
            }
            if (normalizedOutcome != null) {
                predicates.add(cb.equal(root.get("outcome"), normalizedOutcome));
            }
            if (normalizedClientIp != null) {
                predicates.add(cb.equal(root.get("clientIp"), normalizedClientIp));
            }
            if (fromTime != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromTime));
            }
            if (toTime != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), toTime));
            }

            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };

        return securityEventRepository.findAll(spec, effectivePageable);
    }

    private Set<Long> normalizeUserIds(Long userId, List<Long> userIds) {
        Set<Long> normalized = new LinkedHashSet<>();
        if (userId != null) {
            normalized.add(userId);
        }
        if (userIds != null) {
            userIds.stream()
                .filter(java.util.Objects::nonNull)
                .forEach(normalized::add);
        }
        return normalized;
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        String normalized = email.trim().toLowerCase();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    @Builder
    public record EventRequest(
        SecurityEventType eventType,
        SecurityEventOutcome outcome,
        Long userId,
        Long actorUserId,
        String email,
        String targetResource,
        String reason,
        String clientIp,
        String userAgent,
        String requestId,
        Map<String, Object> metadata
    ) {
    }
}
