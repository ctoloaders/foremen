package com.foremen.service.estimate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

import com.foremen.config.estimateai.EstimateAiProperties;
import com.foremen.dao.EstimateDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.EstimateService;
import com.foremen.service.OfferService;
import com.foremen.service.estimate.EstimateAssignmentService.StagedEdit;
import com.foremen.service.offer.EstimateReplaceHelper;
import com.foremen.service.offer.OfferSnapshot;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;

/**
 * FOR-05-04 (Requirement 12; design B6): computes an estimate on a seed and returns the resulting
 * {@link OfferSnapshot} value while persisting nothing.
 *
 * <p>One {@code REQUIRES_NEW} transaction with the configured time box
 * ({@code foremen.estimate-ai.scratch-timeout}): lock the project's estimate row (the lock Save takes),
 * apply the {@link Seed}, replay the edits through {@link StagedEditReplayer} (the preview/Save core),
 * recompute, run the optional cell meta overlay, build the snapshot inside the transaction and mark it
 * rollback-only. The replay publishes no {@code EstimateChangedEvent} and sends no collaboration
 * broadcast; audit rows and totals written inside the transaction are rolled back.
 */
@Service
public class EstimateScratchService {

    /** Message code of the 503 raised when the lock wait or the whole scratch run exceeds the time box. */
    public static final String TIMEOUT_MESSAGE = "error.estimateai.scratch.timeout";
    static final String NOT_FOUND = "error.entity.not.found";
    static final String SNAPSHOT_MISSING = "error.offer.snapshot.missing";
    static final int MIN_SNAPSHOT_VERSION = 2;

    /** Where the scratch estimate starts from. */
    public sealed interface Seed {
        /** The lines are replaced from the offer's snapshot (chat). */
        record Snapshot(Long offerId) implements Seed {
        }

        /** The live estimate as it is. */
        record Live() implements Seed {
        }

        Seed LIVE = new Live();

        static Seed snapshot(Long offerId) {
            return new Snapshot(offerId);
        }
    }

    /** A snapshot cell room that no longer exists in the project (reported, never changed). */
    public record SkippedRoom(Long roomId, String label) {
    }

    /** The scratch result: the value snapshot plus what the seed had to skip. */
    public record ScratchResult(OfferSnapshot snapshot, int skippedWorks, List<SkippedRoom> skippedRooms,
                                int skippedMaterials) {
        public ScratchResult(OfferSnapshot snapshot, int skippedWorks, List<SkippedRoom> skippedRooms) {
            this(snapshot, skippedWorks, skippedRooms, 0);
        }
    }

    private final EstimateDao estimateDao;
    private final EstimateService estimateService;
    private final RoomDao roomDao;
    private final EntityManager entityManager;
    private final EstimateReplaceHelper replaceHelper;
    private final StagedEditReplayer replayer;
    private final EstimateRecomputeService recomputeService;
    private final OfferService offerService;
    private final ObjectProvider<ScratchMetaOverlay> overlayProvider;
    private final PlatformTransactionManager transactionManager;
    private final EstimateAiProperties properties;

    public EstimateScratchService(EstimateDao estimateDao, EstimateService estimateService, RoomDao roomDao,
                                  EntityManager entityManager, EstimateReplaceHelper replaceHelper,
                                  StagedEditReplayer replayer, EstimateRecomputeService recomputeService,
                                  OfferService offerService, ObjectProvider<ScratchMetaOverlay> overlayProvider,
                                  PlatformTransactionManager transactionManager,
                                  EstimateAiProperties properties) {
        this.estimateDao = estimateDao;
        this.estimateService = estimateService;
        this.roomDao = roomDao;
        this.entityManager = entityManager;
        this.replaceHelper = replaceHelper;
        this.replayer = replayer;
        this.recomputeService = recomputeService;
        this.offerService = offerService;
        this.overlayProvider = overlayProvider;
        this.transactionManager = transactionManager;
        this.properties = properties;
    }

    /**
     * Computes the snapshot of {@code seed} + {@code edits} (+ cell meta) without persisting anything.
     *
     * @param metaInputs model-side cell meta passed to the overlay of task 3.11; an empty list is a no-op
     */
    public OfferSnapshot compute(Long projectId, Seed seed, List<StagedEdit> edits,
                                 List<CellMetaInput> metaInputs) {
        return computeDetailed(projectId, seed, edits, metaInputs).snapshot();
    }

    /** Same as {@link #compute} and also reports the works/rooms a {@code Snapshot} seed skipped (R12.4). */
    public ScratchResult computeDetailed(Long projectId, Seed seed, List<StagedEdit> edits,
                                         List<CellMetaInput> metaInputs) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        long seconds = Math.max(1L, (properties.scratchTimeout().toMillis() + 999) / 1000);
        template.setTimeout((int) Math.min(seconds, Integer.MAX_VALUE));
        try {
            return template.execute(status -> {
                try {
                    ScratchResult result = doCompute(projectId, seed, edits, metaInputs);
                    status.setRollbackOnly();
                    return result;
                } catch (RuntimeException e) {
                    status.setRollbackOnly();
                    throw e;
                }
            });
        } catch (TransactionTimedOutException | CannotAcquireLockException | PessimisticLockException
                 | LockTimeoutException e) {
            throw timeout(e);
        } catch (RuntimeException e) {
            if (isTimeout(e)) {
                throw timeout(e);
            }
            throw e;
        }
    }

    private ScratchResult doCompute(Long projectId, Seed seed, List<StagedEdit> edits,
                                    List<CellMetaInput> metaInputs) {
        EstimateEntity estimate = estimateDao.findByProjectIdForUpdate(projectId)
                .orElseGet(() -> {
                    estimateService.getOrCreateEntityForProject(projectId);
                    return estimateDao.findByProjectIdForUpdate(projectId).orElseThrow(
                            () -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, "projectId", projectId));
                });
        int skippedWorks = 0;
        int skippedMaterials = 0;
        List<SkippedRoom> skippedRooms = List.of();
        if (seed instanceof Seed.Snapshot s) {
            OfferSnapshot source = loadSeedSnapshot(projectId, s.offerId());
            Map<Long, RoomEntity> projectRooms = new LinkedHashMap<>();
            for (RoomEntity room : roomDao.findByProjectId(projectId)) {
                projectRooms.put(room.getId(), room);
            }
            EstimateReplaceHelper.Plan plan = replaceHelper.plan(source, projectRooms);
            estimate = replaceHelper.replace(estimate.getId(), plan.lines(), source.appliedPackageCode());
            skippedWorks = plan.skippedWorks();
            skippedMaterials = plan.skippedMaterials();
            skippedRooms = plan.skippedRooms().entrySet().stream()
                    .map(e -> new SkippedRoom(e.getKey(), e.getValue())).toList();
        }
        replayer.replay(estimate, projectId, edits);
        if (metaInputs != null && !metaInputs.isEmpty()) {
            ScratchMetaOverlay overlay = overlayProvider.getIfAvailable();
            if (overlay == null) {
                throw new IllegalStateException("No ScratchMetaOverlay registered for non-empty cell meta");
            }
            entityManager.flush();
            overlay.apply(estimate, projectId, metaInputs);
        }
        recomputeService.recomputeEstimate(estimate);
        entityManager.flush();
        return new ScratchResult(offerService.buildScratchSnapshot(estimate), skippedWorks, skippedRooms,
                skippedMaterials);
    }

    private OfferSnapshot loadSeedSnapshot(Long projectId, Long offerId) {
        OfferEntity offer = offerId != null ? entityManager.find(OfferEntity.class, offerId) : null;
        if (offer == null || offer.getProject() == null || !projectId.equals(offer.getProject().getId())) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, "offerId", offerId);
        }
        OfferSnapshot snapshot = offer.getSnapshot();
        if (snapshot == null || snapshot.version() < MIN_SNAPSHOT_VERSION) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, SNAPSHOT_MISSING, "offerId", offerId);
        }
        return snapshot;
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof TransactionTimedOutException || t instanceof LockTimeoutException
                    || t instanceof PessimisticLockException || t instanceof CannotAcquireLockException
                    || t instanceof org.springframework.dao.QueryTimeoutException
                    || t instanceof org.hibernate.QueryTimeoutException
                    || t instanceof java.sql.SQLTimeoutException) {
                return true;
            }
            if (t == t.getCause()) {
                break;
            }
        }
        return false;
    }

    private static ForemenApiException timeout(Throwable cause) {
        ForemenApiException ex = new ForemenApiException(HttpStatus.SERVICE_UNAVAILABLE, TIMEOUT_MESSAGE);
        ex.initCause(cause);
        return ex;
    }
}
