package com.foremen.service.procurement.proforma;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.foremen.dao.ClientProformaDao;
import com.foremen.dao.ClientProformaLineDao;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.ClientProformaLineEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.model.ClientProformaServiceExtendedModel;
import com.foremen.service.model.mapper.ClientProformaServiceMapper;
import com.foremen.service.procurement.PurchaseRequestOverdue;
import com.foremen.service.procurement.position.PositionLifecycleService;
import com.foremen.service.procurement.position.PositionTransitionPolicy.Actor;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * FOR-11-03 (R4.1 - R4.5, R4.8): issues a draft proforma in one transaction.
 *
 * <p>Order of work: lock the proforma row, re-validate the positions (R3.2), re-snapshot the lines from
 * current position data, check the total, set number and dates, render and store the PDF, move the
 * positions {@code ACCEPTED -> IN_PROFORMA}, publish {@link ProformaIssuedEvent}. The object-storage
 * write is the last step before the position transitions. Storage is not transactional, so once the
 * object is stored it is deleted again whenever the transaction does not commit (a failed transition,
 * a failed flush or a failed commit); a stored object therefore never outlives a rolled-back issue.
 */
@Service
public class ProformaIssueService {

    private static final Logger log = LoggerFactory.getLogger(ProformaIssueService.class);

    static final String DUE_DATE_INVALID = "error.proforma.dueDate.invalid";
    static final String TOTAL_ZERO = "error.proforma.total.zero";
    static final String STATUS_NOT_ALLOWED = "error.proforma.status.notAllowed";

    private final ClientProformaDao dao;
    private final ClientProformaLineDao lineDao;
    private final ClientProformaService proformaService;
    private final ClientProformaServiceMapper mapper;
    private final ProformaLineSnapshotBuilder snapshotBuilder;
    private final ProformaNumberService numberService;
    private final ProformaPdfService pdfService;
    private final PositionLifecycleService lifecycle;
    private final ImageStorage objectStorage;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;
    private final Clock clock;
    private final int defaultDueDays;

    @Autowired
    public ProformaIssueService(ClientProformaDao dao,
                                ClientProformaLineDao lineDao,
                                ClientProformaService proformaService,
                                ClientProformaServiceMapper mapper,
                                ProformaLineSnapshotBuilder snapshotBuilder,
                                ProformaNumberService numberService,
                                ProformaPdfService pdfService,
                                PositionLifecycleService lifecycle,
                                ImageStorage objectStorage,
                                ApplicationEventPublisher events,
                                EntityManager entityManager,
                                @Value("${foremen.proforma.default-due-days:7}") int defaultDueDays) {
        this(dao, lineDao, proformaService, mapper, snapshotBuilder, numberService, pdfService, lifecycle,
                objectStorage, events, entityManager, defaultDueDays, Clock.system(PurchaseRequestOverdue.ZONE));
    }

    ProformaIssueService(ClientProformaDao dao,
                         ClientProformaLineDao lineDao,
                         ClientProformaService proformaService,
                         ClientProformaServiceMapper mapper,
                         ProformaLineSnapshotBuilder snapshotBuilder,
                         ProformaNumberService numberService,
                         ProformaPdfService pdfService,
                         PositionLifecycleService lifecycle,
                         ImageStorage objectStorage,
                         ApplicationEventPublisher events,
                         EntityManager entityManager,
                         int defaultDueDays,
                         Clock clock) {
        this.dao = dao;
        this.lineDao = lineDao;
        this.proformaService = proformaService;
        this.mapper = mapper;
        this.snapshotBuilder = snapshotBuilder;
        this.numberService = numberService;
        this.pdfService = pdfService;
        this.lifecycle = lifecycle;
        this.objectStorage = objectStorage;
        this.events = events;
        this.entityManager = entityManager;
        this.defaultDueDays = defaultDueDays;
        this.clock = clock;
    }

    /**
     * @param language {@code pl} (default) or {@code ru}; the language of the PDF and of the item names
     */
    @Transactional
    public ClientProformaServiceExtendedModel issue(Long id, String language) {
        proformaService.assertProjectAccess(id);
        ClientProformaEntity proforma = entityManager.find(ClientProformaEntity.class, id,
                LockModeType.PESSIMISTIC_WRITE);
        if (proforma == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ClientProformaService.ENTITY_NOT_FOUND, id);
        }
        if (proforma.getStatus() != ProformaStatus.DRAFT) {
            throw new ForemenApiException(HttpStatus.CONFLICT, ClientProformaService.NOT_DRAFT);
        }
        if (!ProformaStatusMachine.canMove(proforma.getStatus(), ProformaStatus.ISSUED,
                proforma.getPaidGross(), proforma.getReleasedAt() != null)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, STATUS_NOT_ALLOWED);
        }
        String lang = "ru".equalsIgnoreCase(language) ? "ru" : ProformaLineSnapshotBuilder.DEFAULT_LANGUAGE;
        String beforeSnapshot = proformaService.serializeEntity(proforma);

        // R4.1: re-validate and re-snapshot from the current position data.
        List<ClientProformaLineEntity> existing = lineDao.findByProformaIdOrderByLineNo(id);
        List<Long> positionIds = existing.stream().map(l -> l.getPosition().getId()).toList();
        List<PurchasePositionEntity> positions =
                proformaService.validatePositions(proforma.getProject().getId(), positionIds, id);
        ProformaLineSnapshotBuilder.Snapshot snapshot = snapshotBuilder.build(proforma, positions, lang);

        // R4.3: nothing to pay, nothing to issue.
        if (snapshot.totals().gross().signum() <= 0) {
            throw new ForemenApiException(HttpStatus.CONFLICT, TOTAL_ZERO);
        }

        // R4.2: due date defaults to issue date + N days and never precedes the issue date.
        LocalDate today = LocalDate.now(clock);
        LocalDate dueDate = proforma.getDueDate();
        if (dueDate == null) {
            dueDate = today.plusDays(defaultDueDays);
        } else if (dueDate.isBefore(today)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, DUE_DATE_INVALID);
        }

        replaceLines(existing, snapshot.lines());
        proforma.setTotalNet(snapshot.totals().net());
        proforma.setTotalVat(snapshot.totals().vat());
        proforma.setTotalGross(snapshot.totals().gross());
        proforma.setNumber(numberService.next(today));
        proforma.setIssueDate(today);
        proforma.setDueDate(dueDate);
        proforma.setStatus(ProformaStatus.ISSUED);
        proforma.setPdfLanguage(lang);
        proforma.setIssuedBy(currentUserReference());

        // R4.4: a render or storage failure (502) or an incomplete company profile (409) aborts here,
        // before anything was stored.
        String objectKey = pdfService.render(proforma, snapshot.lines(), lang);
        compensateUnlessCommitted(objectKey);
        try {
            proforma.setPdfObjectKey(objectKey);
            dao.save(proforma);
            entityManager.flush();

            for (PurchasePositionEntity p : positions) {
                lifecycle.transition(p.getId(), PositionStatus.IN_PROFORMA, Actor.SYSTEM);
            }
            proformaService.saveAuditWithSnapshot(beforeSnapshot, proforma, "UPDATE");
            events.publishEvent(new ProformaIssuedEvent(id));
        } catch (RuntimeException e) {
            deleteStoredObjectNow(objectKey);
            throw e;
        }
        return mapper.toServiceExtendedModel(proforma);
    }

    /** Old lines out, fresh frozen lines in; flushed so the unique indexes never see both generations. */
    private void replaceLines(List<ClientProformaLineEntity> existing, List<ClientProformaLineEntity> fresh) {
        try {
            lineDao.deleteAll(existing);
            entityManager.flush();
            lineDao.saveAll(fresh);
            entityManager.flush();
        } catch (RuntimeException e) {
            if (!(e instanceof ForemenApiException) && ClientProformaService.isPositionClaimViolation(e)) {
                throw new ForemenApiException(HttpStatus.CONFLICT,
                        ClientProformaService.POSITION_ALREADY_IN_PROFORMA);
            }
            throw e;
        }
    }

    /**
     * Registers the safety net: when the surrounding transaction ends without a commit, the stored
     * object is deleted. It runs after the rollback, when the proforma row no longer references the key.
     */
    private void compensateUnlessCommitted(String objectKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteQuietly(objectKey);
                }
            }
        });
    }

    /**
     * Immediate compensation when no transaction synchronization is active (plain calls); inside a
     * transaction the after-completion hook deletes the object once the rollback is done, because until
     * then the flushed proforma row still references the key and {@code deleteIfOrphan} would keep it.
     */
    private void deleteStoredObjectNow(String objectKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteQuietly(objectKey);
        }
    }

    private void deleteQuietly(String objectKey) {
        try {
            objectStorage.deleteIfOrphan(objectKey);
        } catch (RuntimeException e) {
            log.warn("Could not delete proforma PDF {} after a failed issue: {}", objectKey, e.toString());
        }
    }

    private UserEntity currentUserReference() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Long userId) {
            return entityManager.getReference(UserEntity.class, userId);
        }
        return null;
    }
}
