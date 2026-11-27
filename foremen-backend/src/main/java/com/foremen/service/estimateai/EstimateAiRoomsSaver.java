package com.foremen.service.estimateai;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.foremen.controller.model.RoomCreateRequest;
import com.foremen.controller.model.mapper.RoomControllerMapper;
import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.RoomsImportJobDao;
import com.foremen.dao.UserDao;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.estimate.recalc.RoomsSaveService;
import com.foremen.service.model.RoomServiceExtendedModel;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import com.foremen.service.roomsimport.JobCancelledException;
import com.foremen.service.roomsimport.RoomsImportFailureException;
import com.foremen.service.roomsimport.model.DraftMedia;
import com.foremen.service.roomsimport.model.DraftRoom;
import com.foremen.service.roomsimport.model.ImportMediaRef;
import com.foremen.service.roomsimport.model.ImportResult;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-05-04 (Requirements 3.1, 3.2, 3.3, 3.4, 4.4; design B3) - the {@code SAVING_ROOMS} stage of an
 * {@code ESTIMATE} job.
 *
 * <p>Every draft room becomes a {@link RoomCreateRequest} (project forced to the job's project,
 * {@code importJobId} = the job, the draft media as {@link ImportMediaRef}s) and the whole set is saved
 * through {@link RoomsSaveService} in <b>one</b> transaction, inside an {@link AiJobContext} so only this
 * job passes the project lock. The save runs under the <b>job creator's</b> identity: the creator's
 * {@code ROOMS} CREATE permission and project access are re-checked here, so the lock guard and ABAC are not
 * bypassed for another user.
 *
 * <p>Drafts that cannot be saved are skipped and reported ({@link WarningCodes#ROOM_NOT_SAVED}); if the
 * batch itself fails for a room-level reason it is retried room by room (each in its own transaction) so a
 * single bad room never loses the others. The stage is append-only: only creates are sent, never updates or
 * deletes. If nothing could be saved and the project had no rooms the job fails with
 * {@value #FAILED_NO_ROOMS}. Otherwise {@code rooms_saved_at} / {@code rooms_created} are committed in a
 * {@code REQUIRES_NEW} transaction so the Rooms tab can render the rooms while the job is still active.
 *
 * <p>Media: attaching reads the draft bytes through the job's stored {@code result}, so the caller must have
 * persisted the draft result on the job row before this stage; media that cannot be attached is skipped
 * softly by {@code RoomsSaveService}.
 */
@Slf4j
@Component
public class EstimateAiRoomsSaver {

    /** Failure reason when no room could be saved and the project had none. */
    public static final String FAILED_NO_ROOMS = "error.estimateai.failed.no_rooms";

    static final String REASON_TYPE_UNRESOLVED = "ROOM_TYPE_UNRESOLVED";
    static final String REASON_TYPE_UNKNOWN = "ROOM_TYPE_UNKNOWN";
    static final String REASON_NO_DRAFT = "DRAFT_EMPTY";
    static final String REASON_SAVE_FAILED = "SAVE_FAILED";

    private static final String ROOMS_RESOURCE = "ROOMS";
    private static final String CREATE_OPERATION = "CREATE";
    private static final String ADMIN_ROLE = ForemenPermissionEvaluator.ADMIN_ROLE_CODE;

    private final RoomsSaveService roomsSaveService;
    private final RoomsImportJobDao jobDao;
    private final UserDao userDao;
    private final RoomDao roomDao;
    private final RoomTypeDao roomTypeDao;
    private final ForemenPermissionEvaluator permissionEvaluator;
    private final ProjectAccessCache projectAccessCache;
    private final RoomControllerMapper roomMapper;
    private final TransactionTemplate readTx;
    private final TransactionTemplate requiresNewTx;

    public EstimateAiRoomsSaver(RoomsSaveService roomsSaveService, RoomsImportJobDao jobDao, UserDao userDao,
                                RoomDao roomDao, RoomTypeDao roomTypeDao,
                                ForemenPermissionEvaluator permissionEvaluator,
                                ProjectAccessCache projectAccessCache, RoomControllerMapper roomMapper,
                                PlatformTransactionManager transactionManager) {
        this.roomsSaveService = roomsSaveService;
        this.jobDao = jobDao;
        this.userDao = userDao;
        this.roomDao = roomDao;
        this.roomTypeDao = roomTypeDao;
        this.permissionEvaluator = permissionEvaluator;
        this.projectAccessCache = projectAccessCache;
        this.roomMapper = roomMapper;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** A draft room that was not saved, with a stable reason code. */
    public record SkippedRoom(String tempId, String label, String reason) {

        /** The {@code ROOM_NOT_SAVED} report question: {@code params { label, reason }}. */
        public ReportWarning toQuestion() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", label);
            params.put("reason", reason);
            return ReportWarning.warning(WarningCodes.ROOM_NOT_SAVED, null, null, params);
        }
    }

    /**
     * The outcome of the stage.
     *
     * @param savedRoomIds      ids of the rooms created by the job, in draft order
     * @param savedByTempId     draft {@code tempId} to the saved room id
     * @param skipped           the drafts that were not saved
     * @param preExistingRooms  number of rooms the project had before the stage
     */
    public record Outcome(List<Long> savedRoomIds, Map<String, Long> savedByTempId, List<SkippedRoom> skipped,
                          int preExistingRooms) {

        /** One {@code ROOM_NOT_SAVED} question per skipped draft. */
        public List<ReportWarning> questions() {
            return skipped.stream().map(SkippedRoom::toQuestion).toList();
        }
    }

    private record JobInfo(Long jobId, Long projectId, Long creatorId) {
    }

    /**
     * Saves the draft rooms of the job and commits the stage marks.
     *
     * @param jobId the active {@code ESTIMATE} job
     * @param draft the draft result of the rooms phase
     * @return the outcome
     * @throws JobCancelledException       when the job is gone or terminal when the stage is committed
     * @throws RoomsImportFailureException {@value #FAILED_NO_ROOMS} when nothing was saved and none existed
     * @throws ForemenApiException         403 when the creator lost the {@code ROOMS} CREATE permission, 404 when
     *                                     the creator lost access to the project, 409 when another job holds the lock
     */
    public Outcome save(Long jobId, ImportResult draft) {
        JobInfo job = loadJob(jobId);
        assertCreatorMayCreateRooms(job);

        int before = roomDao.findByProjectId(job.projectId()).size();
        List<DraftRoom> drafts = draft == null || draft.rooms() == null ? List.of() : draft.rooms();

        List<SkippedRoom> skipped = new ArrayList<>();
        List<DraftRoom> candidates = new ArrayList<>();
        List<RoomServiceExtendedModel> models = new ArrayList<>();
        for (DraftRoom d : drafts) {
            String problem = problemOf(d);
            if (problem != null) {
                skipped.add(skipped(d, problem));
                continue;
            }
            candidates.add(d);
            models.add(roomMapper.toServiceExtendedModel(toRequest(d, job)));
        }

        Map<String, Long> savedByTempId = new LinkedHashMap<>();
        List<Long> saved = new ArrayList<>();
        if (!candidates.isEmpty()) {
            runAsCreator(job, () -> saveAll(job, candidates, models, saved, savedByTempId, skipped));
        }

        if (saved.isEmpty() && before == 0) {
            throw new RoomsImportFailureException(FAILED_NO_ROOMS);
        }
        markSaved(job.jobId(), saved.size());
        log.info("Estimate AI job {}: saved {} room(s), skipped {}", jobId, saved.size(), skipped.size());
        return new Outcome(List.copyOf(saved), Map.copyOf(savedByTempId), List.copyOf(skipped), before);
    }

    // ------------------------------------------------------------------------------------ saving

    private void saveAll(JobInfo job, List<DraftRoom> candidates, List<RoomServiceExtendedModel> models,
                         List<Long> saved, Map<String, Long> savedByTempId, List<SkippedRoom> skipped) {
        AiJobContext context = new AiJobContext(job.jobId(), job.projectId());
        try {
            RoomsSaveService.Result result = roomsSaveService.save(
                    job.projectId(), models, List.of(), List.of(), true, context);
            record(candidates, result.created(), saved, savedByTempId);
            return;
        } catch (ForemenApiException e) {
            if (isFatal(e)) {
                throw e;
            }
            log.warn("Estimate AI job {}: batch room save failed ({}), retrying room by room",
                    job.jobId(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Estimate AI job {}: batch room save failed, retrying room by room", job.jobId(), e);
        }
        // The batch transaction rolled back, so nothing is saved yet; each room now has its own transaction.
        for (int i = 0; i < candidates.size(); i++) {
            DraftRoom d = candidates.get(i);
            try {
                RoomsSaveService.Result one = roomsSaveService.save(
                        job.projectId(), List.of(models.get(i)), List.of(), List.of(), true, context);
                record(List.of(d), one.created(), saved, savedByTempId);
            } catch (ForemenApiException e) {
                if (isFatal(e)) {
                    throw e;
                }
                skipped.add(skipped(d, e.getMessage() == null ? REASON_SAVE_FAILED : e.getMessage()));
            } catch (RuntimeException e) {
                log.warn("Estimate AI job {}: room '{}' could not be saved", job.jobId(), labelOf(d), e);
                skipped.add(skipped(d, REASON_SAVE_FAILED));
            }
        }
    }

    private static void record(List<DraftRoom> drafts, List<Long> createdIds, List<Long> saved,
                               Map<String, Long> savedByTempId) {
        for (int i = 0; i < createdIds.size() && i < drafts.size(); i++) {
            saved.add(createdIds.get(i));
            String tempId = drafts.get(i).tempId();
            if (tempId != null) {
                savedByTempId.put(tempId, createdIds.get(i));
            }
        }
    }

    /** Lock, permission and access failures are job-level, not a property of one room. */
    private static boolean isFatal(ForemenApiException e) {
        HttpStatus s = HttpStatus.resolve(e.getStatus().value());
        return s == HttpStatus.UNAUTHORIZED || s == HttpStatus.FORBIDDEN || s == HttpStatus.CONFLICT
                || AiProjectLockGuard.LOCKED_MESSAGE.equals(e.getMessage());
    }

    // ------------------------------------------------------------------------------------ mapping

    private String problemOf(DraftRoom d) {
        if (d == null || d.room() == null) {
            return REASON_NO_DRAFT;
        }
        Long typeId = d.room().roomTypeId();
        if (typeId == null) {
            return REASON_TYPE_UNRESOLVED;
        }
        if (!roomTypeDao.existsById(typeId)) {
            return REASON_TYPE_UNKNOWN;
        }
        return null;
    }

    private static RoomCreateRequest toRequest(DraftRoom d, JobInfo job) {
        RoomCreateRequest r = d.room();
        List<ImportMediaRef> media = new ArrayList<>();
        if (d.media() != null) {
            for (DraftMedia m : d.media()) {
                if (m != null && m.mediaId() != null && !m.mediaId().isBlank()) {
                    media.add(new ImportMediaRef(job.jobId(), m.mediaId()));
                }
            }
        }
        return new RoomCreateRequest(job.projectId(), r.roomTypeId(), r.label(), r.ceilingHeight(),
                r.internalCorners(), r.geometry(), r.floorArea(), r.wallArea(), r.perimeter(), r.doorArea(),
                r.windowArea(), r.doorCount(), r.windowCount(), r.doorHeight(), r.doorWidth(), r.windowHeight(),
                r.windowWidth(), r.narrowPlace(), r.wallGap(), r.finishGap(), r.dimensionFormulas(), media,
                job.jobId(), r.wallGapSource(), r.finishGapSource(), r.doorHeightSource(), r.doorWidthSource(),
                r.windowHeightSource(), r.windowWidthSource());
    }

    private static SkippedRoom skipped(DraftRoom d, String reason) {
        return new SkippedRoom(d == null ? null : d.tempId(), labelOf(d), reason);
    }

    private static String labelOf(DraftRoom d) {
        return d == null || d.room() == null ? null : d.room().label();
    }

    // ------------------------------------------------------------------------------------ identity

    private JobInfo loadJob(Long jobId) {
        JobInfo info = readTx.execute(s -> jobDao.findById(jobId)
                .map(j -> new JobInfo(j.getId(), j.getProject().getId(), j.getCreator().getId()))
                .orElse(null));
        if (info == null) {
            throw new JobCancelledException(jobId);
        }
        return info;
    }

    private void assertCreatorMayCreateRooms(JobInfo job) {
        String role = userDao.findRoleCodeById(job.creatorId()).orElse(null);
        if (role == null || !permissionEvaluator.isAllowed(role, ROOMS_RESOURCE, CREATE_OPERATION)) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.access.denied");
        }
        if (!ADMIN_ROLE.equals(role)) {
            Set<Long> allowed = projectAccessCache.get(job.creatorId());
            if (allowed == null || !allowed.contains(job.projectId())) {
                throw new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", job.projectId());
            }
        }
    }

    private void runAsCreator(JobInfo job, Runnable action) {
        String role = userDao.findRoleCodeById(job.creatorId()).orElse(null);
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        Authentication auth = new UsernamePasswordAuthenticationToken(String.valueOf(job.creatorId()), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        try {
            action.run();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    // ------------------------------------------------------------------------------------ stage commit

    private void markSaved(Long jobId, int created) {
        Integer updated = requiresNewTx.execute(s -> jobDao.markRoomsSavedIfActive(
                jobId, LocalDateTime.now(), created, RoomsImportJobDao.TERMINAL));
        if (updated == null || updated == 0) {
            throw new JobCancelledException(jobId);
        }
    }
}
