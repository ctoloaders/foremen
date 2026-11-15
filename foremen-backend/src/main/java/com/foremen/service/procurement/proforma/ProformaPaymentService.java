package com.foremen.service.procurement.proforma;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.ClientProformaDao;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.link.ProformaLedgerEffects;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * FOR-11-03 (R8): makes the proforma follow the money. Called by {@code LinkEffects} after a
 * {@code PROFORMA_PAYMENT} link was created, replaced or removed, in the same transaction: locks the
 * proforma row, recomputes {@code paidGross = Σ links}, derives the status with
 * {@link ProformaStatusDeriver} and, when it becomes {@code PAID} for the first time, runs the release.
 *
 * <p>Idempotent and safe under concurrent links (pessimistic row lock). A released proforma is never
 * touched again (its links are locked, R8.3 / R9.6); a draft or cancelled proforma is ignored.
 */
@Service
public class ProformaPaymentService implements ProformaLedgerEffects {

    private final ClientProformaDao dao;
    private final BankTransactionLinkDao linkDao;
    private final ProformaReleaseService releaseService;
    private final ClientProformaService proformaService;
    private final EntityManager entityManager;

    public ProformaPaymentService(ClientProformaDao dao,
                                  BankTransactionLinkDao linkDao,
                                  ProformaReleaseService releaseService,
                                  ClientProformaService proformaService,
                                  EntityManager entityManager) {
        this.dao = dao;
        this.linkDao = linkDao;
        this.releaseService = releaseService;
        this.proformaService = proformaService;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void onLinkChanged(Long proformaId) {
        ClientProformaEntity proforma = entityManager.find(ClientProformaEntity.class, proformaId,
                LockModeType.PESSIMISTIC_WRITE);
        if (proforma == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ClientProformaService.ENTITY_NOT_FOUND, proformaId);
        }
        ProformaStatus current = proforma.getStatus();
        if (current == ProformaStatus.DRAFT || current == ProformaStatus.CANCELLED
                || proforma.getReleasedAt() != null) {
            return;
        }
        // The just-saved / just-deleted link must be visible to the aggregate query.
        entityManager.flush();
        BigDecimal paid = linkDao.sumAmountByClientProformaId(proformaId);
        paid = paid == null ? BigDecimal.ZERO : paid;
        ProformaStatus target = ProformaStatusDeriver.derive(proforma.getTotalGross(), paid, current);

        boolean paidChanged = proforma.getPaidGross() == null || proforma.getPaidGross().compareTo(paid) != 0;
        if (!paidChanged && target == current) {
            return;
        }
        String before = proformaService.serializeEntity(proforma);
        proforma.setPaidGross(paid);
        if (target != current) {
            if (!ProformaStatusMachine.canMove(current, target, paid, false)) {
                throw new ForemenApiException(HttpStatus.CONFLICT, ProformaIssueService.STATUS_NOT_ALLOWED);
            }
            proforma.setStatus(target);
        }
        if (target == ProformaStatus.PAID) {
            if (proforma.getPaidAt() == null) {
                proforma.setPaidAt(OffsetDateTime.now());
            }
        } else {
            proforma.setPaidAt(null);
        }
        dao.save(proforma);
        if (target == ProformaStatus.PAID) {
            releaseService.release(proforma);
        }
        entityManager.flush();
        proformaService.saveAuditWithSnapshot(before, proforma, "UPDATE");
    }
}
