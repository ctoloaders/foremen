package com.foremen.service.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.foremen.dao.EstimateDao;
import com.foremen.dao.OfferDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.service.OfferService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.audit.AuditLogEntity;
import com.foremen.service.estimate.recalc.EstimateChangedEvent;

import jakarta.persistence.EntityManager;

/**
 * FOR-05-07 additional tasks 1–3 — the offer reaction to an estimate change: a client-visible
 * non-approved offer is withdrawn regardless of the acting user's role, an approved one gets a new
 * DRAFT version (revision = max + 1), a DRAFT is left alone, and every reaction is audited with the
 * acting user and the trigger.
 */
class OfferEstimateReactionServiceTest {

    private static final long PROJECT = 2L;
    private static final long ACTOR = 77L;

    private final OfferDao offerDao = mock(OfferDao.class);
    private final EstimateDao estimateDao = mock(EstimateDao.class);
    private final OfferPackageDao offerPackageDao = mock(OfferPackageDao.class);
    private final OfferService offerService = mock(OfferService.class);
    private final AuditLogDao auditLogDao = mock(AuditLogDao.class);
    private final EntityManager entityManager = mock(EntityManager.class);

    private OfferEstimateReactionService service;
    private final EstimateChangedEvent event =
            new EstimateChangedEvent(PROJECT, 9L, ACTOR, EstimateChangedEvent.Trigger.ROOMS_CHANGED);

    @BeforeEach
    void setUp() {
        service = new OfferEstimateReactionService(offerDao, estimateDao, offerPackageDao,
                new OfferStatusMachine(), offerService, auditLogDao, entityManager);
        when(offerDao.save(any(OfferEntity.class))).thenAnswer(inv -> {
            OfferEntity o = inv.getArgument(0);
            if (o.getId() == null) {
                o.setId(500L);
            }
            return o;
        });
    }

    @Test
    void sentOfferIsWithdrawnBySystemAndAudited() {
        OfferEntity sent = offer(10L, OfferStatus.SENT, 1);
        when(offerDao.findByProjectIdAndStatusNotIn(eq(PROJECT), anyList())).thenReturn(List.of(sent));

        assertThat(service.impactOf(PROJECT)).isEqualTo(OfferEstimateImpact.WITHDRAW);
        OfferEstimateReactionService.Reaction reaction = service.react(event);

        assertThat(reaction.kind()).isEqualTo(OfferEstimateImpact.WITHDRAW);
        assertThat(sent.getStatus()).isEqualTo(OfferStatus.WITHDRAWN);
        AuditLogEntity audit = capturedAudit();
        assertThat(audit.getOperation()).isEqualTo(OfferEstimateReactionService.AUDIT_WITHDRAW);
        assertThat(audit.getEntityId()).isEqualTo(10L);
        assertThat(audit.getPerformedBy()).isEqualTo(String.valueOf(ACTOR));
        assertThat(audit.getSnapshotBefore()).contains("SENT");
        assertThat(audit.getSnapshotAfter()).contains("WITHDRAWN").contains("ESTIMATE_CHANGED")
                .contains("ROOMS_CHANGED").contains("\"actingUserId\":77");
    }

    @Test
    void counteredAndChangesRequestedAreWithdrawableToo() {
        for (OfferStatus status : List.of(OfferStatus.COUNTERED, OfferStatus.CHANGES_REQUESTED)) {
            when(offerDao.findByProjectIdAndStatusNotIn(eq(PROJECT), anyList()))
                    .thenReturn(List.of(offer(11L, status, 2)));
            assertThat(service.impactOf(PROJECT)).isEqualTo(OfferEstimateImpact.WITHDRAW);
        }
    }

    @Test
    void draftOfferIsLeftAlone() {
        OfferEntity draft = offer(12L, OfferStatus.DRAFT, 1);
        when(offerDao.findByProjectIdAndStatusNotIn(eq(PROJECT), anyList())).thenReturn(List.of(draft));

        assertThat(service.impactOf(PROJECT)).isEqualTo(OfferEstimateImpact.NONE);
        assertThat(service.react(event)).isNull();
        assertThat(draft.getStatus()).isEqualTo(OfferStatus.DRAFT);
        verify(auditLogDao, never()).save(any());
    }

    @Test
    void approvedOfferGetsANewDraftVersionWithTheNextRevision() {
        OfferEntity withdrawn = offer(20L, OfferStatus.WITHDRAWN, 3);
        OfferEntity approved = offer(21L, OfferStatus.APPROVED, 2);
        OfferPackageEntity lux = new OfferPackageEntity();
        lux.setCode("lux");
        EstimateEntity estimate = new EstimateEntity();
        estimate.setId(9L);
        estimate.setProject(approved.getProject());
        estimate.setAppliedPackageCode("lux");
        when(offerDao.findByProjectIdAndStatusNotIn(eq(PROJECT), anyList())).thenReturn(List.of());
        when(offerDao.findByProjectIdOrderByIdAsc(PROJECT)).thenReturn(List.of(withdrawn, approved));
        when(estimateDao.findByProjectId(PROJECT)).thenReturn(Optional.of(estimate));
        when(offerPackageDao.findByCode("lux")).thenReturn(Optional.of(lux));

        assertThat(service.impactOf(PROJECT)).isEqualTo(OfferEstimateImpact.REVISE);
        OfferEstimateReactionService.Reaction reaction = service.react(event);

        ArgumentCaptor<OfferEntity> saved = ArgumentCaptor.forClass(OfferEntity.class);
        verify(offerDao).save(saved.capture());
        OfferEntity revision = saved.getValue();
        assertThat(revision.getStatus()).isEqualTo(OfferStatus.DRAFT);
        assertThat(revision.getRevision()).isEqualTo(4); // max(3, 2) + 1
        assertThat(revision.getEstimate()).isSameAs(estimate);
        assertThat(revision.getSelectedPackage()).isSameAs(lux);
        verify(offerService).recomputeTotals(revision);
        assertThat(approved.getStatus()).isEqualTo(OfferStatus.APPROVED); // the agreed version stays
        assertThat(reaction.kind()).isEqualTo(OfferEstimateImpact.REVISE);
        assertThat(reaction.revision()).isEqualTo(4);
        assertThat(capturedAudit().getOperation()).isEqualTo(OfferEstimateReactionService.AUDIT_REVISE);
    }

    @Test
    void closedOrMissingOffersAreNotAffected() {
        when(offerDao.findByProjectIdAndStatusNotIn(eq(PROJECT), anyList())).thenReturn(List.of());
        when(offerDao.findByProjectIdOrderByIdAsc(PROJECT)).thenReturn(List.of());
        assertThat(service.impactOf(PROJECT)).isEqualTo(OfferEstimateImpact.NONE);

        when(offerDao.findByProjectIdOrderByIdAsc(PROJECT))
                .thenReturn(List.of(offer(30L, OfferStatus.REJECTED, 1)));
        assertThat(service.impactOf(PROJECT)).isEqualTo(OfferEstimateImpact.NONE);
        assertThat(service.react(event)).isNull();
    }

    private AuditLogEntity capturedAudit() {
        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogDao).save(captor.capture());
        return captor.getValue();
    }

    private static OfferEntity offer(Long id, OfferStatus status, int revision) {
        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT);
        OfferEntity offer = new OfferEntity();
        offer.setId(id);
        offer.setProject(project);
        offer.setStatus(status);
        offer.setRevision(revision);
        return offer;
    }
}
