package com.foremen.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.MatchTier;
import com.foremen.dao.model.ProcessingStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProcurementLineEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ocr.OcrService;
import com.foremen.service.procurement.ExpenseCategoryApplier;
import com.foremen.service.procurement.ExtractedLine;
import com.foremen.service.procurement.MatchEngine;
import com.foremen.service.procurement.MatchResult;
import com.foremen.service.procurement.PlannedMaterial;
import com.foremen.service.procurement.PlannedMaterialCandidateProvider;
import com.foremen.service.procurement.ProcurementRelinkedEvent;

import jakarta.persistence.EntityManager;

/**
 * FOR-07-03 (Requirements 5.1-5.9, 11.1) - assigns or changes a procurement document's project after
 * upload and re-runs plan matching, in one transaction.
 *
 * <p>Not project-scoped: authorization is the {@code COMPANY_PURCHASES/UPDATE} grant on the controller.
 *
 * <ul>
 *   <li>Validations: project required (400), project exists (404) and is {@code ACTIVE} (409), not the
 *       current project (409), document not still processing (409).</li>
 *   <li>{@code AUTO} / {@code UNMATCHED} lines are re-run through {@link MatchEngine} against the new
 *       project's {@link PlannedMaterialCandidateProvider} candidates.</li>
 *   <li>{@code MANUAL_UNMATCHED} lines are always preserved; {@code MANUAL_MATCHED} lines are preserved
 *       unless the document moves from another project and the material is not a candidate of the new
 *       project - those are reset to {@code UNMATCHED}/{@code NONE} and counted.</li>
 *   <li>Category: dropped on lines that are now matched, the document category re-applied to lines that
 *       stay unmatched and uncategorised ({@link ExpenseCategoryApplier}).</li>
 *   <li>Writes an UPDATE audit row and publishes {@link ProcurementRelinkedEvent} (the notification is
 *       best-effort, after commit).</li>
 * </ul>
 * Ledger links and storage keys are untouched (R5.7, R11.3).
 */
@Service
@Transactional
public class DocumentRelinkService {

    static final String PROJECT_REQUIRED = "error.companyProcurement.relink.projectRequired";
    static final String SAME_PROJECT = "error.companyProcurement.relink.sameProject";
    static final String PROCESSING = "error.companyProcurement.relink.processing";
    static final String STAGE_NOT_ACTIVE = "error.purchases.stage.notActive";
    static final String NOT_FOUND = "error.entity.not.found";

    /** Outcome of a relink (the document row itself is read back by the caller). */
    public record Result(Long documentId, int matchedLines, int resetLines) {
    }

    private final EntityManager entityManager;
    private final MatchEngine matchEngine;
    private final PlannedMaterialCandidateProvider candidateProvider;
    private final ProcurementDocumentService documentService;
    private final OcrService ocrService;
    private final ApplicationEventPublisher eventPublisher;

    public DocumentRelinkService(EntityManager entityManager,
                                 MatchEngine matchEngine,
                                 PlannedMaterialCandidateProvider candidateProvider,
                                 ProcurementDocumentService documentService,
                                 OcrService ocrService,
                                 ApplicationEventPublisher eventPublisher) {
        this.entityManager = entityManager;
        this.matchEngine = matchEngine;
        this.candidateProvider = candidateProvider;
        this.documentService = documentService;
        this.ocrService = ocrService;
        this.eventPublisher = eventPublisher;
    }

    public Result relink(Long documentId, Long projectId) {
        ProcurementDocumentEntity document = documentId == null ? null
                : entityManager.find(ProcurementDocumentEntity.class, documentId);
        if (document == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, documentId);
        }
        if (projectId == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, PROJECT_REQUIRED);
        }
        ProjectEntity project = entityManager.find(ProjectEntity.class, projectId);
        if (project == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, projectId);
        }
        if (project.getStatus() != ProjectStatus.ACTIVE) {
            throw new ForemenApiException(HttpStatus.CONFLICT, STAGE_NOT_ACTIVE);
        }
        ProjectEntity previous = document.getProject();
        if (previous != null && projectId.equals(previous.getId())) {
            throw new ForemenApiException(HttpStatus.CONFLICT, SAME_PROJECT);
        }
        if (isStillProcessing(document)) {
            throw new ForemenApiException(HttpStatus.CONFLICT, PROCESSING);
        }

        String before = documentService.auditSnapshot(document);

        List<PlannedMaterial> candidates = candidateProvider.candidatesFor(projectId);
        Set<Long> candidateIds = candidates.stream().map(PlannedMaterial::materialId)
                .collect(Collectors.toCollection(HashSet::new));
        boolean projectChanged = previous != null;

        int reset = 0;
        for (ProcurementLineEntity line : document.getLines()) {
            MatchStatus status = line.getMatchStatus();
            if (status == MatchStatus.AUTO || status == MatchStatus.UNMATCHED) {
                MatchResult match = matchEngine.match(
                        new ExtractedLine(line.getRawName(), line.getManufacturerHint()), candidates);
                if (!MatchEngine.satisfiesInvariant(match)) {
                    throw new IllegalStateException(
                            "MatchEngine returned a result violating the R1.5 invariant: " + match);
                }
                line.setMatchedMaterialId(match.matchedMaterialId());
                line.setMatchTier(match.tier());
                line.setMatchStatus(match.status());
            } else if (status == MatchStatus.MANUAL_MATCHED && projectChanged
                    && !candidateIds.contains(line.getMatchedMaterialId())) {
                line.setMatchedMaterialId(null);
                line.setMatchTier(MatchTier.NONE);
                line.setMatchStatus(MatchStatus.UNMATCHED);
                reset++;
            }
        }
        ExpenseCategoryApplier.clearOnMatched(document.getLines());
        ExpenseCategoryApplier.apply(document.getExpenseCategory(), document.getLines());

        document.setProject(project);
        entityManager.flush();
        documentService.auditUpdate(before, document);

        int matched = (int) document.getLines().stream()
                .filter(l -> l.getMatchStatus() == MatchStatus.AUTO
                        || l.getMatchStatus() == MatchStatus.MANUAL_MATCHED)
                .count();

        eventPublisher.publishEvent(new ProcurementRelinkedEvent(
                document.getId(),
                document.getUploadedBy() == null ? null : document.getUploadedBy().getId(),
                callerUserId(),
                project.getId(),
                project.getName(),
                document.getSupplierName(),
                document.getInvoiceNumber()));
        return new Result(document.getId(), matched, reset);
    }

    /** R5.4 - an in-flight pipeline stage, or {@code UPLOADED} while OCR is enabled. */
    private boolean isStillProcessing(ProcurementDocumentEntity document) {
        ProcessingStatus status = document.getStatus();
        return status != null
                && (status.isProcessing() || (status == ProcessingStatus.UPLOADED && ocrService.isEnabled()));
    }

    private static Long callerUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            return null;
        }
        try {
            return Long.valueOf(auth.getName().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
