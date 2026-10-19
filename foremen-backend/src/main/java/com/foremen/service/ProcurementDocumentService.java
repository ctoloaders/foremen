package com.foremen.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import com.foremen.config.image.ImageStorageProperties;
import com.foremen.dao.AdminDao;
import com.foremen.dao.ProcurementDocumentDao;
import com.foremen.dao.model.MatchTier;
import com.foremen.dao.model.ProcessingStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProcurementLineEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.document.pdf.InMemoryMultipartFile;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.model.ProcurementDocumentServiceExtendedModel;
import com.foremen.service.model.ProcurementDocumentServiceModel;
import com.foremen.service.model.mapper.ProcurementDocumentServiceMapper;
import com.foremen.service.ocr.OcrFailedException;
import com.foremen.service.ocr.OcrResult;
import com.foremen.service.ocr.OcrService;
import com.foremen.service.ocr.OcrUnavailableException;
import com.foremen.service.ocr.ParsedLine;
import com.foremen.service.ocr.ProcessedPage;
import com.foremen.service.procurement.ExtractedLine;
import com.foremen.service.procurement.MatchEngine;
import com.foremen.service.procurement.MatchResult;
import com.foremen.service.procurement.PlannedMaterial;
import com.foremen.service.ocr.OcrStageListener;
import com.foremen.service.procurement.PlannedMaterialCandidateProvider;
import com.foremen.service.procurement.ProcurementOcrRequested;
import com.foremen.service.procurement.ReprocessLinePartitioner;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-03 (Requirements 2, 3, 7, 8, 1.5, 9.1, 11.1; design §5.1) — the project-scoped service
 * behind procurement intake + automatic matching. It owns the {@code ProcurementDocument} lifecycle:
 * the {@link #upload(Long, MultipartFile) upload} of a raw receipt/invoice through the shared
 * {@link ImageStorage} seam (R2), the OCR + per-line parse + match {@link #process(ProcurementDocumentEntity)
 * orchestration} that transitions a document {@code UPLOADED → PROCESSING → PARSED/FAILED} (R3), the
 * {@link #reprocess(Long) re-process} on the already-stored raw bytes (R8), and {@link #delete(Long)}
 * with cascade + object-key cleanup (R1.4, R11.1).
 *
 * <p>Following the FOR-03-04a single-contract shape (mirrors {@link WorkReportService}), it
 * implements exactly one CRUD contract — {@link ProjectScopedService} — supplies the standard CRUD
 * plumbing ({@link #getDao()}, {@link #getMapper()}, {@link #getEntityManager()},
 * {@link #getDaoModelClass()}, {@link #getAuditLogDao()}), the single mandatory per-entity override
 * {@link #getProjectIdPath()} &rarr; {@code "project.id"} (entity-creation-rules step 4, R7.2), and
 * wires {@link #allowedProjectIds(Long)} to {@link ProjectAccessCache}.
 *
 * <p><b>Stage gate (R7.1).</b> {@link #upload}/{@link #reprocess} are refused unless the owning
 * project is {@code ACTIVE} ({@link #STAGE_NOT_ACTIVE}); reads are allowed on {@code ACTIVE} and
 * {@code COMPLETED} (the tab itself is hidden on {@code DRAFT}). <b>R1.5 invariant.</b> Each line's
 * {@code (matchedMaterialId, tier, status)} is checked via
 * {@link MatchEngine#satisfiesInvariant(MatchResult)} before it is persisted. <b>No notifications</b>
 * are emitted (R9.1) — procurement intake is a synchronous authoring action with no cross-role
 * handoff.
 */
@Service
@Transactional
public class ProcurementDocumentService
        implements ProjectScopedService<ProcurementDocumentServiceModel,
        ProcurementDocumentServiceExtendedModel, ProcurementDocumentEntity, Long> {

    // --- Message codes (bundle wiring is task 9.1) ---
    static final String NOT_FOUND_MESSAGE = "error.entity.not.found";
    static final String UPLOAD_STORAGE_UNAVAILABLE = "error.procurement.upload.storageUnavailable";
    static final String UPLOAD_UNSUPPORTED_TYPE = "error.procurement.upload.unsupportedType";
    static final String UPLOAD_TOO_LARGE = "error.procurement.upload.tooLarge";
    static final String STAGE_NOT_ACTIVE = "error.procurement.stage.notActive";

    // --- Stable failure reason codes surfaced to the UI on FAILED (R3.4/R3.5; A1 R18.1) ---
    static final String FAILURE_OCR_DISABLED = "procurement.ocr.disabled";
    static final String FAILURE_OCR_NO_LINES = "procurement.ocr.noLines";
    static final String FAILURE_OCR_FAILED = "procurement.ocr.failed";
    /** A1 R18.1 — stage-specific failure codes so the UI renders a descriptive reason. */
    static final String FAILURE_OCR_RASTERIZE = "procurement.ocr.failed.rasterize";
    static final String FAILURE_OCR_VISION = "procurement.ocr.failed.vision";
    static final String FAILURE_OCR_NO_TEXT = "procurement.ocr.failed.noText";
    static final String FAILURE_OCR_PARSE = "procurement.ocr.failed.parse";
    static final String FAILURE_OCR_STORAGE = "procurement.ocr.failed.storage";

    /** The accepted upload content types (R2.2): the raster images plus PDF. */
    static final Set<String> ACCEPTED_CONTENT_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp", "application/pdf");

    /** Fallback max upload size when {@code foremen.image-storage.max-upload-size} is unset (mirrors GcsImageStorage). */
    private static final DataSize DEFAULT_MAX_UPLOAD_SIZE = DataSize.ofMegabytes(5);

    /** Signed-download-URL TTL for the raw/processed renditions resolved on the read path. */
    private static final long IMAGE_URL_TTL_SECONDS = 600L;

    /**
     * FOR-06-04 R5.1 — a disjoint temporary {@code line_no} band used while re-numbering a re-processed
     * document's preserved manual lines, so shifting them to their final contiguous positions never
     * transiently collides with an as-yet-unmoved sibling under the {@code (document_id, line_no)}
     * unique constraint. Far above any realistic per-document line count.
     */
    private static final int LINE_NO_RENUMBER_OFFSET = 1_000_000;

    private final ProcurementDocumentDao documentDao;
    private final ProjectAccessCache projectAccessCache;
    private final ProcurementDocumentServiceMapper serviceMapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final ImageStorage imageStorage;
    private final OcrService ocrService;
    private final MatchEngine matchEngine;
    private final PlannedMaterialCandidateProvider candidateProvider;
    private final ReprocessLinePartitioner reprocessLinePartitioner;
    private final ApplicationEventPublisher eventPublisher;
    private final DataSize maxUploadSize;

    public ProcurementDocumentService(ProcurementDocumentDao documentDao,
                                      ProjectAccessCache projectAccessCache,
                                      ProcurementDocumentServiceMapper serviceMapper,
                                      AuditLogDao auditLogDao,
                                      EntityManager entityManager,
                                      ImageStorage imageStorage,
                                      OcrService ocrService,
                                      MatchEngine matchEngine,
                                      PlannedMaterialCandidateProvider candidateProvider,
                                      ReprocessLinePartitioner reprocessLinePartitioner,
                                      ApplicationEventPublisher eventPublisher,
                                      ImageStorageProperties imageStorageProperties) {
        this.documentDao = documentDao;
        this.projectAccessCache = projectAccessCache;
        this.serviceMapper = serviceMapper;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.imageStorage = imageStorage;
        this.ocrService = ocrService;
        this.matchEngine = matchEngine;
        this.candidateProvider = candidateProvider;
        this.reprocessLinePartitioner = reprocessLinePartitioner;
        this.eventPublisher = eventPublisher;
        this.maxUploadSize = parseMaxUploadSize(
                imageStorageProperties == null ? null : imageStorageProperties.maxUploadSize());
    }

    // --- CRUD plumbing (inherited via ProjectScopedService) ------------------------------------

    @Override
    public AdminDao<ProcurementDocumentEntity, Long> getDao() {
        return documentDao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<ProcurementDocumentEntity, ProcurementDocumentServiceModel,
            ProcurementDocumentServiceExtendedModel> getMapper() {
        return serviceMapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<ProcurementDocumentEntity> getDaoModelClass() {
        return ProcurementDocumentEntity.class;
    }

    // --- ProjectScopedService overrides --------------------------------------------------------

    /** The single mandatory per-entity override (entity-creation-rules step 4, R7.2). */
    @Override
    public String getProjectIdPath() {
        return "project.id";
    }

    @Override
    public Set<Long> allowedProjectIds(Long userId) {
        return projectAccessCache.get(userId);
    }

    // --- Read path (fill the mapper-left-null uploader name + image URLs + matched names) ------

    /**
     * Single-document review read (R6): the inherited project-scope access guard runs first, then
     * the service fills the fields the mapper deliberately left {@code null} — the uploader's display
     * name, the resolved raw + processed image URLs, and each line's matched-material localized name
     * (from the FOR-05-05b candidate projection, {@code ru → pl → code}).
     */
    @Override
    @Transactional(readOnly = true)
    public ProcurementDocumentServiceExtendedModel findById(Long id) {
        assertProjectAccess(id);
        ProcurementDocumentEntity entity = documentDao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, id));
        ProcurementDocumentServiceExtendedModel model = serviceMapper.toServiceExtendedModel(entity);
        resolveReadFields(model, entity);
        return model;
    }

    /**
     * Amendment A1 (R16.1) — the lightweight status read the client polls while a document is
     * processing. Resolves ONLY the lifecycle fields ({@code status} + {@code failureReason}) under
     * the same project-scope access guard as {@link #findById}, without building the heavy read model
     * (no image-URL signing, no matched-material name resolution, no lines). Returns the resolved
     * pair so the controller can project it into the cheap {@code ProcurementDocumentStatusResponse}.
     *
     * @param id the document id
     * @return the document's current status + failure reason
     */
    @Transactional(readOnly = true)
    public StatusView status(Long id) {
        assertProjectAccess(id);
        ProcurementDocumentEntity entity = documentDao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, id));
        return new StatusView(entity.getId(), entity.getStatus(), entity.getFailureReason());
    }

    /** Lightweight lifecycle projection for the poll endpoint (A1 R16.1). */
    public record StatusView(Long id, ProcessingStatus status, String failureReason) {
    }

    /**
     * A1 R17 — the uploader + project + failure-reason context the OCR worker needs to emit the
     * terminal notification, read in its own {@code REQUIRES_NEW} transaction after the terminal
     * commit. Returns {@code null} when the document no longer exists.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public NotificationContext notificationContext(Long id) {
        ProcurementDocumentEntity document = documentDao.findById(id).orElse(null);
        if (document == null) {
            return null;
        }
        Long uploaderId = document.getUploadedBy() == null ? null : document.getUploadedBy().getId();
        Long projectId = document.getProject() == null ? null : document.getProject().getId();
        return new NotificationContext(uploaderId, projectId, document.getFailureReason());
    }

    /** Notification context for the terminal-state emit (A1 R17). */
    public record NotificationContext(Long uploaderId, Long projectId, String failureReason) {
    }

    // --- Upload (R2) ---------------------------------------------------------------------------

    /**
     * Uploads a primary procurement document on an {@code ACTIVE} project (R2): validates storage
     * availability (R2.4), the content type (R2.2) and the size (R2.3) <b>before</b> storing
     * anything, stores the raw bytes through {@link ImageStorage#storeObject} namespaced per project
     * (R2.1), persists an {@code UPLOADED} document with {@code uploadedAt}/{@code uploadedBy}/
     * {@code sourceMimeType}, writes a CREATE audit row (R11.1), and — Amendment A1 (R14) — commits
     * the document as {@code QUEUED} and publishes a {@link ProcurementOcrRequested} event so the
     * asynchronous {@code ProcurementOcrWorker} runs the OCR pipeline AFTER this transaction commits.
     * The id is returned immediately, never blocking the request on OCR (A1 R14.1/R14.3). When the
     * OCR provider is disabled the document rests {@code UPLOADED} and is NOT enqueued (A1 R14.4).
     *
     * @param projectId the owning {@code ACTIVE} project
     * @param file      the uploaded image or PDF
     * @return the persisted document as a read-extended model (status {@code QUEUED}, or
     *         {@code UPLOADED} when the OCR provider is disabled)
     */
    @Transactional
    public ProcurementDocumentServiceExtendedModel upload(Long projectId, MultipartFile file) {
        assertProjectActive(projectId);

        // R2.4 — object store not configured: reject gracefully, storing nothing, creating no document.
        if (!imageStorage.isConfigured()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, UPLOAD_STORAGE_UNAVAILABLE);
        }
        // R2.2 — content type must be an accepted image or PDF.
        String contentType = file == null ? null : file.getContentType();
        if (contentType == null || !ACCEPTED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, UPLOAD_UNSUPPORTED_TYPE, contentType);
        }
        // R2.3 — size guard (reusing the ImageStorage size rule), rejecting before storing.
        if (file.isEmpty() || file.getSize() > maxUploadSize.toBytes()) {
            if (file.getSize() > maxUploadSize.toBytes()) {
                throw new ForemenApiException(HttpStatus.BAD_REQUEST, UPLOAD_TOO_LARGE,
                        file.getSize(), maxUploadSize.toBytes());
            }
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, UPLOAD_UNSUPPORTED_TYPE, contentType);
        }

        // R2.1 — store the raw bytes.
        String rawObjectKey = imageStorage.storeObject(file, "procurement/" + projectId + "/");

        // A1 R14.4 — when the OCR provider is disabled the document rests UPLOADED and is not enqueued.
        boolean ocrEnabled = ocrService.isEnabled();
        ProcurementDocumentEntity document = new ProcurementDocumentEntity();
        document.setProject(reference(ProjectEntity.class, projectId));
        document.setUploadedBy(requireCaller());
        document.setRawObjectKey(rawObjectKey);
        document.setSourceMimeType(contentType);
        document.setStatus(ocrEnabled ? ProcessingStatus.QUEUED : ProcessingStatus.UPLOADED);
        if (!ocrEnabled) {
            document.setFailureReason(FAILURE_OCR_DISABLED);
        }
        document.setUploadedAt(OffsetDateTime.now());

        ProcurementDocumentEntity saved = documentDao.save(document);
        entityManager.flush();
        saveAudit(null, saved, "CREATE");

        // A1 R14.3 — hand off to the async worker AFTER this transaction commits (never inline).
        if (ocrEnabled) {
            eventPublisher.publishEvent(new ProcurementOcrRequested(saved.getId()));
        }
        return findReadModel(saved);
    }

    // --- Re-process (R8) -----------------------------------------------------------------------

    /**
     * Re-runs OCR + parse + matching on an already-stored document's raw bytes (R8), only for a
     * {@code FAILED} or {@code PARSED} document on an {@code ACTIVE} project. Reads the raw bytes back
     * from {@link ImageStorage#readObject}, cleans up the superseded processed rendition
     * ({@link ImageStorage#deleteIfOrphan}), replaces all lines atomically, and transitions
     * {@code PROCESSING → PARSED/FAILED}; on failure the raw upload stays intact (R8.3). Writes an
     * UPDATE audit row (R11.1).
     *
     * @param id the document to re-process
     * @return the re-processed document as a read-extended model
     */
    @Transactional
    public ProcurementDocumentServiceExtendedModel reprocess(Long id) {
        assertProjectAccess(id);
        ProcurementDocumentEntity document = documentDao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, id));

        // R7.1 — re-process is a mutation; refuse it unless the project is ACTIVE.
        assertProjectActive(document.getProject() == null ? null : document.getProject().getId());

        // R8.1 — only FAILED or PARSED documents are re-processable.
        if (document.getStatus() != ProcessingStatus.FAILED
                && document.getStatus() != ProcessingStatus.PARSED) {
            throw new ForemenApiException(HttpStatus.CONFLICT, NOT_FOUND_MESSAGE, id);
        }

        // A1 R18.3 — retry is asynchronous exactly like upload: re-enqueue (QUEUED), commit, and let
        // the async worker re-run the pipeline AFTER this transaction commits. Clear any prior
        // failure reason so the row reads as freshly queued.
        document.setStatus(ProcessingStatus.QUEUED);
        document.setFailureReason(null);
        ProcurementDocumentEntity saved = documentDao.save(document);
        entityManager.flush();
        saveAudit(null, saved, "REPROCESS");
        eventPublisher.publishEvent(new ProcurementOcrRequested(saved.getId()));
        return findReadModel(saved);
    }

    // --- Async processing entry points (Amendment A1) ------------------------------------------

    /**
     * Amendment A1 (R15.2) — commits a single stage transition in its own {@code REQUIRES_NEW}
     * transaction so a poller observes the progression while the worker runs off-thread. Safe to call
     * from the OCR worker thread (no ambient transaction required). Writes a lifecycle audit row.
     *
     * @param id    the document being processed
     * @param stage the processing stage to record (a non-terminal {@code ProcessingStatus})
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markStage(Long id, ProcessingStatus stage) {
        ProcurementDocumentEntity document = documentDao.findById(id).orElse(null);
        if (document == null) {
            return;
        }
        document.setStatus(stage);
        documentDao.save(document);
        entityManager.flush();
    }

    /**
     * Amendment A1 (R14.3, R15, R18.1) — the asynchronous OCR orchestration the worker invokes on the
     * {@code ocrTaskExecutor} thread, in its OWN {@code REQUIRES_NEW} transaction (it runs outside any
     * request transaction). Reads the stored raw bytes, runs the OCR pipeline reporting
     * CLEANING/OCR_TEXT/PARSING stages via the {@code stageSink}, stores the processed renditions,
     * matches + persists lines atomically, and transitions the document to the terminal
     * {@code PARSED}/{@code FAILED} (raw retained on failure, A1 R18.1). Returns the terminal status so
     * the worker can emit the matching notification.
     *
     * @param id        the {@code QUEUED} document to process
     * @param stageSink invoked (committed via {@link #markStage}) as the OCR pipeline enters a stage
     * @return the terminal status reached ({@code PARSED} or {@code FAILED}), or {@code UPLOADED} when
     *         the provider turned out disabled; {@code null} when the document no longer exists
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProcessingStatus processQueued(Long id, java.util.function.Consumer<ProcessingStatus> stageSink) {
        ProcurementDocumentEntity document = documentDao.findById(id).orElse(null);
        if (document == null) {
            return null;
        }
        byte[] rawBytes;
        try {
            stageSink.accept(ProcessingStatus.RASTERIZING);
            rawBytes = imageStorage.readObject(document.getRawObjectKey());
        } catch (RuntimeException e) {
            markFailed(document, FAILURE_OCR_RASTERIZE);
            return ProcessingStatus.FAILED;
        }
        process(document, rawBytes, stageSink);
        return document.getStatus();
    }

    // --- Delete (R1.4, R11.1) ------------------------------------------------------------------

    /**
     * Deletes a document: the inherited project-scope access guard runs first, the owned
     * {@code procurement_lines} are discarded by the cascade (R1.4), both stored object keys are
     * cleaned up via {@link ImageStorage#deleteIfOrphan} (keeping a key still referenced elsewhere),
     * and a DELETE audit row is written (R11.1).
     */
    @Override
    @Transactional
    public void deleteById(Long id) {
        assertProjectAccess(id);
        ProcurementDocumentEntity document = documentDao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, id));
        String rawKey = document.getRawObjectKey();
        String processedKey = document.getProcessedObjectKey();

        saveAudit(document, null, "DELETE");
        documentDao.deleteById(id);
        entityManager.flush();

        // deleteIfOrphan re-checks the DB; the just-deleted document's row is already gone, so a key
        // referenced ONLY by it is reclaimed, while a key shared with another document is kept.
        imageStorage.deleteIfOrphan(processedKey);
        imageStorage.deleteIfOrphan(rawKey);
    }

    /** Public alias for the delete endpoint (R6/R8 controller, task 6.1). */
    @Transactional
    public void delete(Long id) {
        deleteById(id);
    }

    // --- OCR + parse + match orchestration (R3) ------------------------------------------------

    /**
     * The OCR + parse + match orchestration (R3). Runs the {@link OcrService} on the stored raw
     * bytes, stores the processed white-background rendition(s) (R3.0), matches each extracted line
     * via the {@link MatchEngine} over the project's candidate set, replaces the document's lines
     * atomically with sequential {@code lineNo} from 1 (R3.3), sets {@code supplierName}/
     * {@code documentTotal}/{@code parsedAt}, and transitions {@code PARSED}. On a provider failure,
     * unparseable output or no lines it transitions {@code FAILED} with a stable {@code failureReason}
     * while retaining the raw object + document (R3.4). When the OCR provider is disabled
     * ({@link OcrUnavailableException}) the document is left {@code UPLOADED} (R3.5) — not {@code FAILED}.
     * Writes an audit row for the lifecycle op (R11.1).
     *
     * @param document the document to process (status already {@code PROCESSING})
     * @param rawBytes the stored raw upload bytes
     */
    void process(ProcurementDocumentEntity document, byte[] rawBytes,
                 java.util.function.Consumer<ProcessingStatus> stageSink) {
        Long projectId = document.getProject() == null ? null : document.getProject().getId();
        String keyNamespace = "procurement/" + projectId + "/processed/";

        // Map the OCR layer's internal phases onto the persisted processing stages (A1 R15).
        OcrStageListener listener = phase -> stageSink.accept(switch (phase) {
            case CLEANING -> ProcessingStatus.CLEANING;
            case OCR_TEXT -> ProcessingStatus.OCR_TEXT;
            case PARSING -> ProcessingStatus.PARSING;
        });

        OcrResult result;
        try {
            result = ocrService.process(rawBytes, document.getSourceMimeType(), keyNamespace, listener);
        } catch (OcrUnavailableException disabled) {
            // R3.5 — provider disabled: leave the document UPLOADED (OCR never attempted), no lines.
            document.setStatus(ProcessingStatus.UPLOADED);
            document.setFailureReason(FAILURE_OCR_DISABLED);
            persistLifecycle(document, "PROCESS");
            return;
        } catch (OcrFailedException failed) {
            // R3.4 / A1 R18.1 — configured but this document could not be processed: FAILED, raw
            // retained, with a stage-specific reason classified from the failure message.
            markFailed(document, classifyFailure(failed));
            return;
        } catch (RuntimeException unexpected) {
            // Any other provider/runtime error maps to the generic FAILED-with-raw-retained outcome.
            markFailed(document, FAILURE_OCR_FAILED);
            return;
        }

        // R3.4 — a configured provider that extracted no lines is a failure (nothing to persist).
        if (result == null || result.lines() == null || result.lines().isEmpty()) {
            markFailed(document, FAILURE_OCR_NO_LINES);
            return;
        }

        // A1 R15 — entering the match + persist stage.
        stageSink.accept(ProcessingStatus.MATCHING);

        // R3.0 — store the processed white-background rendition(s); the FIRST page's key is kept on
        // the document (per-page keys share the "{documentUuid}/p{n}.png" convention for the viewer).
        String previousProcessedKey = document.getProcessedObjectKey();
        String processedObjectKey;
        try {
            processedObjectKey = storeProcessedPages(result.pages(), keyNamespace);
        } catch (RuntimeException storageFailure) {
            // A1 R18.1 — storing the processed rendition failed; FAILED, raw retained.
            markFailed(document, FAILURE_OCR_STORAGE);
            return;
        }

        // R5/R4 — match each extracted line against the project's planned-material candidate set.
        List<PlannedMaterial> candidates = candidateProvider.candidatesFor(projectId);
        List<ProcurementLineEntity> freshLines = buildLines(document, result.lines(), candidates);

        // FOR-06-04 R5.1 — preserve the manually reconciled lines, replace only the automatic ones.
        // The pure ReprocessLinePartitioner splits the document's prior lines into preserve
        // (MANUAL_MATCHED/MANUAL_UNMATCHED, kept with matchedMaterialId/unplannedCategory intact) and
        // replace (AUTO/UNMATCHED, dropped). When no prior line is manual the preserve part is empty
        // and this is byte-for-byte the FOR-06-03 replace-all behaviour (R5.2). On a fresh upload the
        // prior-line list is empty, so the partition is empty/empty and all fresh lines are inserted.
        List<ProcurementLineEntity> priorLines = new ArrayList<>(document.getLines());
        ReprocessLinePartitioner.Partition partition = reprocessLinePartitioner.partition(priorLines);
        List<ProcurementLineEntity> preserved = partition.preserve();

        // R8.2 / R5.1 — replace ATOMICALLY: remove only the automatic lines (orphan-delete), then
        // flush so their rows are gone before any renumber can reuse a freed (document_id, line_no).
        if (!partition.replace().isEmpty()) {
            document.getLines().removeAll(partition.replace());
            entityManager.flush();
        }

        // Renumber so preserved manual lines come first (1..k) then the fresh lines (k+1..), keeping
        // the (document_id, line_no) unique constraint. Preserved lines currently hold their old
        // line_no, which may collide with the target sequence, so first shift every surviving line to
        // a disjoint temporary band and flush before assigning the final contiguous numbering.
        if (!preserved.isEmpty()) {
            int temp = LINE_NO_RENUMBER_OFFSET;
            for (ProcurementLineEntity line : preserved) {
                line.setLineNo(temp++);
            }
            entityManager.flush();
            int lineNo = 1;
            for (ProcurementLineEntity line : preserved) {
                line.setLineNo(lineNo++);
            }
            for (ProcurementLineEntity line : freshLines) {
                line.setLineNo(lineNo++);
            }
            document.getLines().addAll(freshLines);
        } else {
            document.getLines().addAll(freshLines);
        }

        document.setProcessedObjectKey(processedObjectKey);
        document.setSupplierName(result.supplierName());
        document.setDocumentTotal(result.documentTotal());
        document.setParsedAt(OffsetDateTime.now());
        document.setFailureReason(null);
        document.setStatus(ProcessingStatus.PARSED);
        persistLifecycle(document, "PROCESS");

        // R8.1 — a re-process that produced a new processed rendition supersedes the old one.
        if (previousProcessedKey != null && !previousProcessedKey.equals(processedObjectKey)) {
            imageStorage.deleteIfOrphan(previousProcessedKey);
        }
    }

    /**
     * Builds the fresh {@link ProcurementLineEntity} rows from the parsed lines, assigning sequential
     * {@code lineNo} from 1 (R3.3) and the {@link MatchEngine} proposal per line. The R1.5 invariant
     * is asserted for each line's match result before it is attached.
     */
    private List<ProcurementLineEntity> buildLines(ProcurementDocumentEntity document,
                                                   List<ParsedLine> parsed,
                                                   List<PlannedMaterial> candidates) {
        List<ProcurementLineEntity> lines = new ArrayList<>(parsed.size());
        int lineNo = 1;
        for (ParsedLine parsedLine : parsed) {
            MatchResult match = matchEngine.match(
                    new ExtractedLine(parsedLine.rawName(), parsedLine.manufacturerHint()),
                    candidates);
            // R1.5 — matchedMaterialId == null ⟺ tier == NONE, status consistent; fail fast if not.
            if (!MatchEngine.satisfiesInvariant(match)) {
                throw new IllegalStateException(
                        "MatchEngine returned a result violating the R1.5 invariant: " + match);
            }

            ProcurementLineEntity line = new ProcurementLineEntity();
            line.setDocument(document);
            line.setLineNo(lineNo++);
            line.setRawName(parsedLine.rawName());
            line.setQuantity(parsedLine.quantity());
            line.setUnitHint(parsedLine.unitHint());
            line.setUnitPrice(parsedLine.unitPrice());
            line.setLineTotal(parsedLine.lineTotal());
            line.setManufacturerHint(parsedLine.manufacturerHint());
            line.setMatchedMaterialId(match.matchedMaterialId());
            line.setMatchTier(match.tier());
            line.setMatchStatus(match.status());
            lines.add(line);
        }
        return lines;
    }

    /**
     * Stores the processed white-background rendition pages via {@link ImageStorage#storeObject},
     * returning the first page's object key (kept on the document; the per-page viewer enumerates the
     * rest). Returns {@code null} when there are no pages to store.
     */
    private String storeProcessedPages(List<ProcessedPage> pages, String keyNamespace) {
        if (pages == null || pages.isEmpty()) {
            return null;
        }
        String firstKey = null;
        int pageNo = 1;
        for (ProcessedPage page : pages) {
            if (page == null || page.pngBytes() == null || page.pngBytes().length == 0) {
                pageNo++;
                continue;
            }
            MultipartFile pageFile = new InMemoryMultipartFile(
                    "page", "p" + pageNo + ".png", "image/png", page.pngBytes());
            String key = imageStorage.storeObject(pageFile, keyNamespace);
            if (firstKey == null) {
                firstKey = key;
            }
            pageNo++;
        }
        return firstKey;
    }

    /**
     * A1 R18.1 — classify an {@link OcrFailedException} into a stage-specific failure code from its
     * message, so the UI can render a descriptive reason. Falls back to the generic code.
     */
    private static String classifyFailure(OcrFailedException failed) {
        String message = failed.getMessage() == null ? "" : failed.getMessage().toLowerCase();
        if (message.contains("rasteriz") || message.contains("no pages") || message.contains("unreadable")
                || message.contains("render")) {
            return FAILURE_OCR_RASTERIZE;
        }
        if (message.contains("vision")) {
            return FAILURE_OCR_VISION;
        }
        if (message.contains("no text") || message.contains("produced no text")) {
            return FAILURE_OCR_NO_TEXT;
        }
        if (message.contains("no lines")) {
            return FAILURE_OCR_NO_LINES;
        }
        if (message.contains("parse")) {
            return FAILURE_OCR_PARSE;
        }
        return FAILURE_OCR_FAILED;
    }

    /** Transitions the document to {@code FAILED} with a stable reason, retaining the raw object (R3.4). */
    private void markFailed(ProcurementDocumentEntity document, String failureReason) {
        document.setStatus(ProcessingStatus.FAILED);
        document.setFailureReason(failureReason);
        persistLifecycle(document, "PROCESS");
    }

    /** Saves a lifecycle transition and writes an audit row (R11.1). */
    private void persistLifecycle(ProcurementDocumentEntity document, String operation) {
        ProcurementDocumentEntity saved = documentDao.save(document);
        entityManager.flush();
        saveAudit(null, saved, operation);
    }

    // --- Read-field resolution -----------------------------------------------------------------

    private ProcurementDocumentServiceExtendedModel findReadModel(ProcurementDocumentEntity entity) {
        ProcurementDocumentServiceExtendedModel model = serviceMapper.toServiceExtendedModel(entity);
        resolveReadFields(model, entity);
        return model;
    }

    /**
     * Fills the read fields the mapper deliberately left {@code null}: the uploader display name, the
     * resolved raw + processed image URLs ({@link ImageStorage#toCdnUrl}, falling back to a signed
     * download URL for a private bucket), and each line's matched-material localized name from the
     * candidate projection ({@code ru → pl → code}).
     */
    private void resolveReadFields(ProcurementDocumentServiceExtendedModel model,
                                   ProcurementDocumentEntity entity) {
        if (entity.getUploadedBy() != null) {
            model.setUploaderName(entity.getUploadedBy().getName());
        }
        model.setRawImageUrl(resolveImageUrl(entity.getRawObjectKey()));
        model.setProcessedImageUrl(resolveImageUrl(entity.getProcessedObjectKey()));
        fillMatchedMaterialNames(model, entity);
    }

    /**
     * Resolves a procurement image (raw / processed rendition) to a short-lived, one-time V4 signed
     * download URL. The procurement media bucket is PRIVATE (it holds receipts/invoices, served the
     * same way as the FOR-05-08 signable documents, not public CDN assets) — the configured
     * {@code cdn-base} exists only to satisfy the ImageStorage "configured" gate, NOT to serve bytes.
     * Returning the public {@code toCdnUrl} here made the viewer load a public
     * {@code storage.googleapis.com/...} link that an anonymous caller cannot read (403 AccessDenied)
     * against the private bucket. We therefore ALWAYS sign the download (keyless IAM signBlob),
     * mirroring {@code SignableDocumentService}. A {@code null} key yields a {@code null} URL.
     */
    private String resolveImageUrl(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        return imageStorage.signedDownloadUrl(objectKey, IMAGE_URL_TTL_SECONDS);
    }

    /**
     * Replaces each read-line's {@code matchedMaterialName} (left {@code null} by the mapper) with the
     * matched material's localized display name, resolved from the project's candidate projection
     * (the same {@code ru → pl → code} source the engine matched over). An unmatched line keeps a
     * {@code null} name.
     */
    private void fillMatchedMaterialNames(ProcurementDocumentServiceExtendedModel model,
                                          ProcurementDocumentEntity entity) {
        if (model.getLines() == null || model.getLines().isEmpty()) {
            return;
        }
        Long projectId = entity.getProject() == null ? null : entity.getProject().getId();
        Map<Long, String> namesById = matchedNamesById(projectId);
        List<com.foremen.controller.model.ProcurementLineModel> resolved =
                new ArrayList<>(model.getLines().size());
        for (com.foremen.controller.model.ProcurementLineModel line : model.getLines()) {
            String name = line.matchedMaterialId() == null
                    ? null
                    : namesById.get(line.matchedMaterialId());
            resolved.add(new com.foremen.controller.model.ProcurementLineModel(
                    line.lineNo(), line.rawName(), line.quantity(), line.unitHint(),
                    line.unitPrice(), line.lineTotal(), line.manufacturerHint(),
                    line.matchedMaterialId(), name, line.matchTier(), line.matchPercent(),
                    line.matchStatus()));
        }
        model.setLines(resolved);
    }

    /** The candidate materials' display names keyed by material id (the engine's primary name). */
    private Map<Long, String> matchedNamesById(Long projectId) {
        Map<Long, String> byId = new HashMap<>();
        if (projectId == null) {
            return byId;
        }
        for (PlannedMaterial candidate : candidateProvider.candidatesFor(projectId)) {
            if (candidate == null || candidate.materialId() == null) {
                continue;
            }
            byId.put(candidate.materialId(), primaryName(candidate));
        }
        return byId;
    }

    /** The candidate's display name: first non-blank localized name, else {@code null}. */
    private static String primaryName(PlannedMaterial candidate) {
        if (candidate.names() == null) {
            return null;
        }
        for (String name : candidate.names()) {
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return null;
    }

    // --- Stage gate + caller helpers -----------------------------------------------------------

    /** Refuses a mutation (upload / re-process) unless the owning project is {@code ACTIVE} (R7.1). */
    private void assertProjectActive(Long projectId) {
        if (projectId == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, (Object) null);
        }
        ProjectEntity project = entityManager.find(ProjectEntity.class, projectId);
        if (project == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, projectId);
        }
        if (project.getStatus() != ProjectStatus.ACTIVE) {
            throw new ForemenApiException(HttpStatus.CONFLICT, STAGE_NOT_ACTIVE);
        }
    }

    /** The authenticated caller as a managed reference; 401 when unresolved. */
    private UserEntity requireCaller() {
        Long callerId = callerUserId();
        if (callerId == null) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, NOT_FOUND_MESSAGE);
        }
        return reference(UserEntity.class, callerId);
    }

    private static Long callerUserId() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        String name = auth.getName();
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(name.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A managed JPA reference ({@code getReference}, no SELECT) for a non-null id, else {@code null}. */
    private <T> T reference(Class<T> type, Long id) {
        return id == null ? null : entityManager.getReference(type, id);
    }

    private static byte[] bytesOf(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (java.io.IOException e) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, UPLOAD_UNSUPPORTED_TYPE,
                    file.getContentType());
        }
    }

    private static DataSize parseMaxUploadSize(String configured) {
        if (configured == null || configured.isBlank()) {
            return DEFAULT_MAX_UPLOAD_SIZE;
        }
        return DataSize.parse(configured.strip());
    }
}
