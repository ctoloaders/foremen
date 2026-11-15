package com.foremen.service.procurement.proforma;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.ClientProformaLineDao;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.ClientProformaLineEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.service.procurement.position.PositionLifecycleService;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;

/**
 * FOR-11-03 (R9): the release of a fully paid proforma. Every position goes
 * {@code IN_PROFORMA -> CLIENT_PAID -> WITH_BUYER} through {@link PositionLifecycleService} (the gate rule
 * stays in {@code PositionTransitionPolicy}). A position that is no longer {@code IN_PROFORMA} is skipped
 * and logged. Idempotent: once {@code releasedAt} is set a second call changes nothing. Runs in the
 * caller's transaction (the ledger link transaction).
 */
@Service
public class ProformaReleaseService {

    private static final Logger log = LoggerFactory.getLogger(ProformaReleaseService.class);

    private final ClientProformaLineDao lineDao;
    private final PositionLifecycleService lifecycle;
    private final ApplicationEventPublisher events;

    public ProformaReleaseService(ClientProformaLineDao lineDao,
                                  PositionLifecycleService lifecycle,
                                  ApplicationEventPublisher events) {
        this.lineDao = lineDao;
        this.lifecycle = lifecycle;
        this.events = events;
    }

    /**
     * Releases the positions of the (already locked, already {@code PAID}) proforma.
     *
     * @param proforma the managed proforma entity
     * @return {@code true} when the release ran, {@code false} when it was already released
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean release(ClientProformaEntity proforma) {
        if (proforma.getReleasedAt() != null) {
            return false;
        }
        List<Long> released = new ArrayList<>();
        List<Long> skipped = new ArrayList<>();
        for (ClientProformaLineEntity line : lineDao.findByProformaIdOrderByLineNo(proforma.getId())) {
            if (!line.isActive()) {
                continue;
            }
            Long positionId = line.getPosition().getId();
            if (line.getPosition().getStatus() != PositionStatus.IN_PROFORMA) {
                skipped.add(positionId);
                continue;
            }
            lifecycle.transition(positionId, PositionStatus.CLIENT_PAID, Actor.SYSTEM);
            lifecycle.transition(positionId, PositionStatus.WITH_BUYER, Actor.SYSTEM);
            released.add(positionId);
        }
        if (!skipped.isEmpty()) {
            log.info("Proforma {} release skipped positions not in IN_PROFORMA: {}", proforma.getId(), skipped);
        }
        proforma.setReleasedAt(OffsetDateTime.now());
        events.publishEvent(new ProformaPaidEvent(proforma.getId(), proforma.getProject().getId(),
                List.copyOf(released), currentUserId()));
        return true;
    }

    private static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Long userId) {
            return userId;
        }
        return null;
    }
}
