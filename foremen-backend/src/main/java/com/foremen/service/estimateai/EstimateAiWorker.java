package com.foremen.service.estimateai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomsImportJobDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.RoomsImportJobEntity;
import com.foremen.dao.model.RoomsImportStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.estimate.CellFactsResolver;
import com.foremen.service.estimate.EstimateScratchService;
import com.foremen.service.estimateai.EstimateAiContextLoader.Context;
import com.foremen.service.estimateai.ai.EstimateAiException;
import com.foremen.service.estimateai.ai.EstimatePlanClient;
import com.foremen.service.estimateai.PlanCatalog.CatalogWork;
import com.foremen.service.roomsimport.EstimateAiErrors;
import com.foremen.service.roomsimport.JobActionLog;
import com.foremen.service.roomsimport.JobCancelledException;
import com.foremen.service.roomsimport.RoomsImportCancellation;
import com.foremen.service.roomsimport.RoomsImportErrors;
import com.foremen.service.roomsimport.RoomsImportFailureException;
import com.foremen.service.roomsimport.RoomsImportJobMapper;
import com.foremen.service.roomsimport.RoomsImportJobTerminator;
import com.foremen.service.roomsimport.RoomsImportPipeline;
import com.foremen.service.roomsimport.RoomsImportRequested;
import com.foremen.service.roomsimport.RoomsImportStageMarker;
import com.foremen.service.roomsimport.RoomsImportStorage;
import com.foremen.service.roomsimport.StoredInput;
import com.foremen.service.roomsimport.model.DraftRoom;
import com.foremen.service.roomsimport.model.ImportResult;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-05-04 (design B2; Requirements 2.2, 3.5, 4.4, 4.6, 4.7, 6.3, 6.6, 18.2, 18.3) - drives one
 * {@code ESTIMATE}-mode job to a terminal state on the dedicated {@code estimateAiTaskExecutor}.
 *
 * <p>Stages, each committed in its own {@code REQUIRES_NEW} transaction with a cancellation check at every
 * boundary ({@link RoomsImportStageMarker#markStage} updates 0 rows once the job row is gone):
 * <ol>
 *   <li>the FOR-05-02 {@code ROOMS} phase through {@link RoomsImportPipeline#run} (it ends at
 *       {@code VALIDATING}: the draft is handed over here, not completed); skipped for
 *       {@code useExistingRooms};</li>
 *   <li>the draft {@link ImportResult} is stored on the job row, then {@code SAVING_ROOMS}
 *       ({@link EstimateAiRoomsSaver}; reads the draft media through the stored result);</li>
 *   <li>{@code PLANNING_WORKS} ({@link EstimateAiPlanner}, the job's input files re-attached);</li>
 *   <li>{@code ASSIGNING}: {@link AssignWorkService} once per resolved assignment, in plan order, each call
 *       in its own transaction (cells, materials and {@code ai_meta} are visible on commit); a rejected call
 *       is a warning and the loop continues; {@code works_total}/{@code works_done} track progress;</li>
 *   <li>{@code FINALIZING}: {@link AiSummaryWriter} stores the summary, then the job completes.</li>
 * </ol>
 *
 * <p>No offer is created. Rooms and assignments committed so far are kept when the job is cancelled or
 * fails; then the partial summary (incomplete) is written when at least one work was assigned. A
 * {@link JobCancelledException} is swallowed. Every other failure becomes a {@code FAILED} job with a stable
 * {@code error.estimateai.failed.*} (or the ROOMS-phase {@code error.roomsimport.failed.*}) reason. A worker
 * that dies without a terminal write is failed by the sweep with the job timeout. The action log gets
 * entries for rooms measured/saved, the ready plan, assignments, rejections and the summary.
 *
 * <p>The whole run executes under the job creator's identity so audit rows carry the right user.
 */
@Slf4j
@Component
public class EstimateAiWorker {

    /** Total size of the input files re-attached to the planning call. */
    static final long MAX_ATTACHMENT_BYTES = 15L * 1024 * 1024;

    private final RoomsImportJobDao jobDao;
    private final RoomsImportJobMapper mapper;
    private final RoomsImportPipeline pipeline;
    private final RoomsImportStageMarker stages;
    private final RoomsImportCancellation cancellation;
    private final RoomsImportJobTerminator terminator;
    private final RoomsImportStorage storage;
    private final EstimateAiRoomsSaver roomsSaver;
    private final EstimateAiContextLoader contextLoader;
    private final EstimateAiPlanner planner;
    private final CellFactsResolver cellFacts;
    private final AssignWorkService assignWork;
    private final AiSummaryWriter summaryWriter;
    private final JobActionLog actionLog;
    private final JdbcTemplate jdbc;
    private final RoomDao roomDao;
    private final UserDao userDao;
    private final com.foremen.service.EstimateService estimateService;
    private final TransactionTemplate readTx;
    private final TransactionTemplate requiresNewTx;

    public EstimateAiWorker(RoomsImportJobDao jobDao, RoomsImportJobMapper mapper, RoomsImportPipeline pipeline,
                            RoomsImportStageMarker stages, RoomsImportCancellation cancellation,
                            RoomsImportJobTerminator terminator, RoomsImportStorage storage,
                            EstimateAiRoomsSaver roomsSaver, EstimateAiContextLoader contextLoader,
                            EstimateAiPlanner planner, CellFactsResolver cellFacts,
                            AssignWorkService assignWork, AiSummaryWriter summaryWriter,
                            JobActionLog actionLog, JdbcTemplate jdbc, RoomDao roomDao, UserDao userDao,
                            com.foremen.service.EstimateService estimateService,
                            PlatformTransactionManager transactionManager) {
        this.jobDao = jobDao;
        this.mapper = mapper;
        this.pipeline = pipeline;
        this.stages = stages;
        this.cancellation = cancellation;
        this.terminator = terminator;
        this.storage = storage;
        this.roomsSaver = roomsSaver;
        this.contextLoader = contextLoader;
        this.planner = planner;
        this.cellFacts = cellFacts;
        this.assignWork = assignWork;
        this.summaryWriter = summaryWriter;
        this.actionLog = actionLog;
        this.jdbc = jdbc;
        this.roomDao = roomDao;
        this.userDao = userDao;
        this.estimateService = estimateService;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Async("estimateAiTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRequested(RoomsImportRequested event) {
        if (event == null || event.jobId() == null || !event.isEstimate()) {
            return; // ROOMS_ONLY jobs belong to RoomsImportWorker
        }
        process(event.jobId());
    }

    /** Runs one job to its terminal state; synchronous so tests can call it directly. */
    public void process(Long jobId) {
        SecurityContext previous = SecurityContextHolder.getContext();
        try {
            JobInfo job = loadJob(jobId);
            if (!job.estimate()) {
                return;
            }
            runAs(job.creatorId());
            run(job);
        } catch (JobCancelledException e) {
            log.debug("Estimate AI job {} cancelled; worker stops", jobId);
        } catch (RoomsImportFailureException e) {
            log.warn("Estimate AI job {} failed: {}", jobId, e.getReason());
            failQuietly(jobId, e.getReason());
        } catch (EstimateAiException e) {
            log.warn("Estimate AI job {} failed in the model call: {}", jobId, e.reason());
            failQuietly(jobId, reasonOf(e));
        } catch (RuntimeException e) {
            String reason = isScratchTimeout(e) ? EstimateAiErrors.FAILED_TIMEOUT
                    : RoomsImportErrors.FAILED_INTERNAL;
            log.error("Estimate AI job {} failed unexpectedly ({})", jobId, reason, e);
            failQuietly(jobId, reason);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    // ------------------------------------------------------------------------------------------

    private void run(JobInfo job) {
        Long jobId = job.jobId();
        cancellation.assertActive(jobId);
        List<ReportWarning> extra = new ArrayList<>();

        // --- ROOMS phase (skipped for useExistingRooms) ------------------------------------------
        ImportResult draft;
        if (job.useExistingRooms()) {
            draft = new ImportResult(List.of(), List.of(), List.of(), null);
        } else {
            draft = pipeline.run(jobId);
            persistDraft(jobId, draft);
            List<DraftRoom> drafts = draft.rooms() == null ? List.of() : draft.rooms();
            jdbc.update("UPDATE rooms_import_jobs SET rooms_total = ?, rooms_done = 0 WHERE id = ?",
                    drafts.size(), jobId);
            for (DraftRoom d : drafts) {
                record(jobId, RoomsImportStatus.MEASURING, JobActionLog.ROOM_MEASURED, roomParams(d));
            }

            // --- SAVING_ROOMS --------------------------------------------------------------------
            enter(jobId, RoomsImportStatus.SAVING_ROOMS);
            EstimateAiRoomsSaver.Outcome outcome = roomsSaver.save(jobId, draft);
            extra.addAll(outcome.questions());
            int savedCount = 0;
            for (DraftRoom d : drafts) {
                if (d.tempId() != null && outcome.savedByTempId().containsKey(d.tempId())) {
                    savedCount++;
                    jdbc.update("UPDATE rooms_import_jobs SET rooms_done = ? WHERE id = ?", savedCount, jobId);
                    record(jobId, RoomsImportStatus.SAVING_ROOMS, JobActionLog.ROOM_SAVED, roomParams(d));
                }
            }
            stages.annotate(jobId, RoomsImportStatus.SAVING_ROOMS, Map.of(
                    "roomsSaved", outcome.savedRoomIds().size(), "roomsSkipped", outcome.skipped().size()));
        }

        // --- PLANNING_WORKS ----------------------------------------------------------------------
        enter(jobId, RoomsImportStatus.PLANNING_WORKS);
        Context ctx = contextLoader.load(job.projectId());
        if (ctx.rooms().isEmpty()) {
            throw new RoomsImportFailureException(EstimateAiRoomsSaver.FAILED_NO_ROOMS);
        }
        EstimateAiPlanner.PlanResult planned = planner.plan(job.prompt(), ctx.categories(), ctx.roomsText(),
                ctx.rooms(), ctx.catalog(), attachments(job));
        EstimatePlan plan = planned.plan();
        stages.annotate(jobId, RoomsImportStatus.PLANNING_WORKS, Map.of(
                "worksProposed", plan.works().size() + plan.unpriced().size(),
                "worksResolved", plan.works().size(),
                "unpriced", plan.unpriced().size()));
        jdbc.update("UPDATE rooms_import_jobs SET works_total = ?, works_done = coalesce(works_done, 0) "
                + "WHERE id = ?", plan.works().size(), jobId);
        record(jobId, RoomsImportStatus.PLANNING_WORKS, JobActionLog.PLAN_READY,
                Map.of("count", plan.works().size()));

        // The live estimate must exist before the first assignment.
        estimateService.getOrCreateForProject(job.projectId());

        // --- ASSIGNING + FINALIZING --------------------------------------------------------------
        String packageCode = job.packageCode() != null ? job.packageCode() : ctx.appliedPackageCode();
        AiJobContext jobContext = new AiJobContext(jobId, job.projectId(), packageCode);
        Progress progress = new Progress();
        try {
            assign(job, jobContext, ctx, planned, plan, packageCode, extra, progress);
            enter(jobId, RoomsImportStatus.FINALIZING);
            Map<String, Object> summary = writeSummary(job, plan, ctx, extra, progress, true,
                    planned.promptVersion());
            Map<String, Object> estimate = new LinkedHashMap<>();
            estimate.put("worksDone", progress.done);
            estimate.put("worksRejected", progress.rejected);
            if (summary != null) {
                estimate.put("report", summary);
            }
            terminator.complete(jobId, draft, estimate);
        } catch (RuntimeException e) {
            if (progress.done > 0 && !progress.summaryWritten) {
                writeSummary(job, plan, ctx, extra, progress, false, planned.promptVersion());
            }
            throw e;
        }
    }

    /** Mutable counters of the assignment loop. */
    private static final class Progress {
        int done;
        int rejected;
        boolean summaryWritten;
    }

    /**
     * Calls {@link AssignWorkService} once per resolved assignment, in plan order, each in its own
     * transaction. A rejected call is a warning and the loop continues; an unexpected exception is retried
     * once per call and then treated as rejected.
     */
    private void assign(JobInfo job, AiJobContext jobContext, Context ctx, EstimateAiPlanner.PlanResult planned,
                        EstimatePlan plan, String packageCode, List<ReportWarning> extra, Progress progress) {
        Long jobId = job.jobId();
        enter(jobId, RoomsImportStatus.ASSIGNING);
        List<PromptCoefficient> coefficients =
                EstimateAiContextLoader.coefficients(planned.coefficients(), ctx);
        EngineFacts facts = readTx.execute(status -> cellFacts.engineFacts(packageCode));
        Map<Long, String> roomLabels = roomLabels(job.projectId());
        Set<PromptCoefficient> applied = new LinkedHashSet<>();
        for (PlannedWork w : plan.works()) {
            cancellation.assertActive(jobId);
            List<PromptCoefficient> inScope = new ArrayList<>();
            for (PromptCoefficient c : coefficients) {
                if (inScope(c, w, facts)) {
                    inScope.add(c);
                }
            }
            // A price adjustment is stated once for its scope; it is sent with the first call that succeeds.
            List<AssignWorkCommand.Coefficient> toApply = inScope.stream().filter(c -> !applied.contains(c))
                    .map(c -> new AssignWorkCommand.Coefficient(c.scope(), c.targetId(), c.pct())).toList();
            String priceReason = inScope.stream().map(PromptCoefficient::reason)
                    .filter(r -> r != null && !r.isBlank()).distinct().collect(Collectors.joining("; "));
            AssignWorkCommand cmd = new AssignWorkCommand(w.workItemId(), List.of(w.roomId()), w.volume(),
                    w.volumeReason(), w.rationale(), w.status(), w.confidence(), w.source(),
                    priceReason.isEmpty() ? null : priceReason, toApply);
            Map<String, Object> rejection = callAssign(jobContext, cmd);
            if (rejection == null) {
                progress.done++;
                applied.addAll(inScope);
            } else {
                progress.rejected++;
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("room", roomLabels.getOrDefault(w.roomId(), String.valueOf(w.roomId())));
                params.put("workCode", w.workCode());
                CatalogWork cw = ctx.catalog().get(w.workCode());
                params.put("workName", cw == null ? w.workCode() : cw.name());
                params.put("reason", String.valueOf(rejection.get("reason")));
                record(jobId, RoomsImportStatus.ASSIGNING, JobActionLog.ASSIGNMENT_REJECTED, params);
                Map<String, Object> wp = new LinkedHashMap<>(params);
                wp.put("roomId", w.roomId());
                extra.add(ReportWarning.warning(WarningCodes.EDIT_REJECTED, w.roomId(), w.workCode(), wp));
            }
        }
        stages.annotate(jobId, RoomsImportStatus.ASSIGNING, Map.of(
                "assigned", progress.done, "rejected", progress.rejected));
    }

    /**
     * One assignment call with one retry of an unexpected exception.
     *
     * @return {@code null} when committed, otherwise the rejection params with a {@code reason}
     */
    private Map<String, Object> callAssign(AiJobContext jobContext, AssignWorkCommand cmd) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                assignWork.assign(jobContext, cmd);
                return null;
            } catch (AssignRejected e) {
                Map<String, Object> out = new LinkedHashMap<>(e.params());
                out.putIfAbsent("reason", e.code());
                return out;
            } catch (JobCancelledException e) {
                throw e;
            } catch (RuntimeException e) {
                last = e;
                cancellation.assertActive(jobContext.jobId());
                log.warn("Estimate AI job {}: assignment of work {} failed (attempt {}): {}",
                        jobContext.jobId(), cmd.workItemId(), attempt + 1, e.toString());
            }
        }
        log.error("Estimate AI job {}: assignment of work {} dropped after a retry", jobContext.jobId(),
                cmd.workItemId(), last);
        return new LinkedHashMap<>(Map.of("reason", RoomsImportErrors.FAILED_INTERNAL));
    }

    private static boolean inScope(PromptCoefficient c, PlannedWork w, EngineFacts facts) {
        return switch (c.scope()) {
            case PROJECT -> true;
            case WORK_ITEM -> Objects.equals(c.targetId(), w.workItemId());
            case WORK_CATEGORY -> facts != null && Objects.equals(c.targetId(), facts.categoryId(w.workItemId()));
        };
    }

    /** FINALIZING write of the AI summary (9.6); a failure is logged and never fails the job. */
    private Map<String, Object> writeSummary(JobInfo job, EstimatePlan plan, Context ctx,
                                             List<ReportWarning> extra, Progress progress, boolean completed,
                                             String promptVersion) {
        try {
            Map<String, Object> summary = summaryWriter.write(new AiSummaryWriter.Request(job.jobId(),
                    job.projectId(), plan, ctx.roomsArea(), extra, completed, progress.done, progress.rejected,
                    promptVersion));
            progress.summaryWritten = true;
            if (summary != null) {
                record(job.jobId(), RoomsImportStatus.FINALIZING, JobActionLog.SUMMARY_WRITTEN, Map.of());
            }
            return summary;
        } catch (RuntimeException e) {
            log.warn("Estimate AI job {}: summary not written: {}", job.jobId(), e.toString());
            return null;
        }
    }

    private Map<Long, String> roomLabels(Long projectId) {
        Map<Long, String> out = new LinkedHashMap<>();
        readTx.executeWithoutResult(status -> roomDao.findByProjectId(projectId)
                .forEach(r -> out.put(r.getId(), AssignWorkService.roomLabel(r))));
        return out;
    }

    private static Map<String, Object> roomParams(DraftRoom d) {
        String label = d.room() != null && d.room().label() != null && !d.room().label().isBlank()
                ? d.room().label() : d.tempId();
        return label == null ? Map.of() : Map.of("room", label);
    }

    /** Action-log append that never fails the job (the log is informational). */
    private void record(Long jobId, RoomsImportStatus stage, String code, Map<String, Object> params) {
        try {
            actionLog.append(jobId, stage, code, params);
        } catch (RuntimeException e) {
            log.debug("Estimate AI job {}: action log entry {} skipped: {}", jobId, code, e.toString());
        }
    }

    /** Cancellation check, then the committed stage transition. */
    private void enter(Long jobId, RoomsImportStatus stage) {
        cancellation.assertActive(jobId);
        stages.markStage(jobId, stage);
    }

    private static boolean isScratchTimeout(Throwable e) {
        return e instanceof ForemenApiException api
                && api.getStatus().value() == HttpStatus.SERVICE_UNAVAILABLE.value()
                && EstimateScratchService.TIMEOUT_MESSAGE.equals(api.getMessageCode());
    }

    // ------------------------------------------------------------------------------------------
    // Persistence helpers
    // ------------------------------------------------------------------------------------------

    /**
     * Stores the draft on the job row before {@code SAVING_ROOMS}: the rooms saver attaches the draft media
     * by reading it from the stored result. A job that is gone or terminal is a cancellation.
     */
    private void persistDraft(Long jobId, ImportResult draft) {
        requiresNewTx.executeWithoutResult(status -> {
            RoomsImportJobEntity job = jobDao.findById(jobId).orElseThrow(() -> new JobCancelledException(jobId));
            if (job.getStatus() == null || job.getStatus().isTerminal()) {
                throw new JobCancelledException(jobId);
            }
            job.setResult(mapper.resultToMap(draft));
            jobDao.save(job);
        });
    }

    private record JobInfo(Long jobId, Long projectId, Long creatorId, String prompt, boolean estimate,
                           boolean useExistingRooms, String packageCode,
                           List<StoredInput> inputs) {
    }

    private JobInfo loadJob(Long jobId) {
        JobInfo info = readTx.execute(status -> jobDao.findById(jobId).map(j -> new JobInfo(j.getId(),
                j.getProject().getId(), j.getCreator().getId(), j.getPromptText(), "ESTIMATE".equals(j.getMode()),
                j.isUseExistingRooms(), j.getPackageCode(), mapper.readInputs(j))).orElse(null));
        if (info == null) {
            throw new JobCancelledException(jobId);
        }
        return info;
    }

    /** The job's input images and PDFs, re-attached to the planning call within a total size bound. */
    private List<EstimatePlanClient.Attachment> attachments(JobInfo job) {
        List<EstimatePlanClient.Attachment> out = new ArrayList<>();
        long total = 0;
        for (StoredInput in : job.inputs()) {
            String type = in.contentType() == null ? "" : in.contentType().toLowerCase(java.util.Locale.ROOT);
            if (!(type.startsWith("image/") || type.equals("application/pdf")) || in.objectKey() == null) {
                continue;
            }
            if (total + Math.max(0, in.sizeBytes()) > MAX_ATTACHMENT_BYTES) {
                continue;
            }
            try {
                byte[] bytes = storage.read(in.objectKey());
                if (bytes != null && bytes.length > 0 && total + bytes.length <= MAX_ATTACHMENT_BYTES) {
                    out.add(new EstimatePlanClient.Attachment(type, bytes));
                    total += bytes.length;
                }
            } catch (RuntimeException e) {
                log.debug("Estimate AI job {}: input could not be re-attached", job.jobId());
            }
        }
        return out;
    }

    /** Installs the job creator as the caller so audit rows and permission-aware services see them. */
    private void runAs(Long creatorId) {
        String role = userDao.findRoleCodeById(creatorId).orElse(null);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        List<SimpleGrantedAuthority> authorities = role == null ? List.of()
                : List.of(new SimpleGrantedAuthority("ROLE_" + role));
        context.setAuthentication(new UsernamePasswordAuthenticationToken(String.valueOf(creatorId), null,
                authorities));
        SecurityContextHolder.setContext(context);
    }

    private static String reasonOf(EstimateAiException e) {
        return switch (e.reason()) {
            case TIMEOUT -> EstimateAiErrors.FAILED_TIMEOUT;
            case PLAN_INVALID, INVALID_RESPONSE -> EstimateAiErrors.FAILED_PLAN_INVALID;
            case DISABLED, LLM_UNAVAILABLE -> EstimateAiErrors.FAILED_LLM_UNAVAILABLE;
        };
    }

    private void failQuietly(Long jobId, String reason) {
        try {
            terminator.fail(jobId, reason);
        } catch (RuntimeException e) {
            // the sweep fails the job once it is stuck
            log.error("Could not mark estimate AI job {} failed", jobId, e);
        }
    }
}
