package com.foremen.service.procurement.proforma;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.ClientProformaDao;
import com.foremen.dao.ClientProformaLineDao;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.ClientProformaLineEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.model.ClientProformaServiceExtendedModel;
import com.foremen.service.model.mapper.ClientProformaServiceMapper;
import com.foremen.service.procurement.position.PositionLifecycleService;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * FOR-11-03 (R5.1 - R5.4): cancels a draft or an unpaid issued proforma.
 *
 * <p>A draft never moved its positions, so cancelling it only frees the lines. An issued proforma also
 * returns every position {@code IN_PROFORMA -> ACCEPTED} (SYSTEM). A proforma with {@code paidGross > 0}
 * or any {@code PROFORMA_PAYMENT} ledger link is refused. The stored PDF is kept.
 */
@Service
public class ProformaCancelService {

    static final String HAS_PAYMENTS = "error.proforma.cancel.hasPayments";
    static final String REASON_REQUIRED = "error.proforma.cancel.reasonRequired";
    static final int MAX_REASON_LENGTH = 500;

    private final ClientProformaDao dao;
    private final ClientProformaLineDao lineDao;
    private final ClientProformaService proformaService;
    private final ClientProformaServiceMapper mapper;
    private final PositionLifecycleService lifecycle;
    private final ProformaLinkLookup linkLookup;
    private final EntityManager entityManager;

    public ProformaCancelService(ClientProformaDao dao,
                                 ClientProformaLineDao lineDao,
                                 ClientProformaService proformaService,
                                 ClientProformaServiceMapper mapper,
                                 PositionLifecycleService lifecycle,
                                 ProformaLinkLookup linkLookup,
                                 EntityManager entityManager) {
        this.dao = dao;
        this.lineDao = lineDao;
        this.proformaService = proformaService;
        this.mapper = mapper;
        this.lifecycle = lifecycle;
        this.linkLookup = linkLookup;
        this.entityManager = entityManager;
    }

    @Transactional
    public ClientProformaServiceExtendedModel cancel(Long id, String reason) {
        proformaService.assertProjectAccess(id);
        ClientProformaEntity proforma = entityManager.find(ClientProformaEntity.class, id,
                LockModeType.PESSIMISTIC_WRITE);
        if (proforma == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ClientProformaService.ENTITY_NOT_FOUND, id);
        }
        ProformaStatus status = proforma.getStatus();
        boolean hasMoney = proforma.getPaidGross() != null && proforma.getPaidGross().signum() > 0;
        if (hasMoney) {
            throw new ForemenApiException(HttpStatus.CONFLICT, HAS_PAYMENTS);
        }
        if (status != ProformaStatus.DRAFT && status != ProformaStatus.ISSUED) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ProformaIssueService.STATUS_NOT_ALLOWED);
        }
        if (status == ProformaStatus.ISSUED && linkLookup.hasLinks(id)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, HAS_PAYMENTS);
        }
        String cleanReason = reason == null ? null : reason.trim();
        if (cleanReason != null && cleanReason.isEmpty()) {
            cleanReason = null;
        }
        if (status == ProformaStatus.ISSUED && cleanReason == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, REASON_REQUIRED);
        }
        if (cleanReason != null && cleanReason.length() > MAX_REASON_LENGTH) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, REASON_REQUIRED);
        }
        if (!ProformaStatusMachine.canMove(status, ProformaStatus.CANCELLED, proforma.getPaidGross(),
                proforma.getReleasedAt() != null)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ProformaIssueService.STATUS_NOT_ALLOWED);
        }

        String beforeSnapshot = proformaService.serializeEntity(proforma);
        List<ClientProformaLineEntity> lines = lineDao.findByProformaIdOrderByLineNo(id);
        List<Long> positionIds = lines.stream().map(l -> l.getPosition().getId()).toList();

        proforma.setStatus(ProformaStatus.CANCELLED);
        proforma.setCancelledAt(OffsetDateTime.now());
        proforma.setCancelReason(cleanReason);
        dao.save(proforma);
        lineDao.deactivateByProformaId(id);
        entityManager.flush();

        if (status == ProformaStatus.ISSUED) {
            for (Long positionId : positionIds) {
                lifecycle.transition(positionId, PositionStatus.ACCEPTED, Actor.SYSTEM);
            }
        }
        proformaService.saveAuditWithSnapshot(beforeSnapshot, proforma, "UPDATE");
        return mapper.toServiceExtendedModel(proforma);
    }
}
