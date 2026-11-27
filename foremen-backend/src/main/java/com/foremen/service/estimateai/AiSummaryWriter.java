package com.foremen.service.estimateai;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.EstimateDao;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.service.OfferService;
import com.foremen.service.offer.OfferSnapshot;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-05-04 rework (task 9.6; Requirements 25.1, 25.2, 25.5): writes the AI report into
 * {@code estimates.ai_summary} in {@code FINALIZING}. The summary is computed from the live estimate (the
 * value snapshot the offer would get) and the validated plan, so it describes what was really committed.
 * One shared value per estimate: a new run replaces it. A cancelled or failed run writes the partial
 * summary with {@code incomplete = true}. Manual edits that clear cell marks never touch it (25.5).
 */
@Slf4j
@Service
public class AiSummaryWriter {

    /**
     * @param jobId       the job that produced the summary
     * @param projectId   the owning project
     * @param plan        the validated plan (may be {@code null} when planning never finished)
     * @param roomsArea   total area of the project's rooms, or {@code null}
     * @param extra       extra warnings of the pipeline (rejected assignments, unsaved rooms)
     * @param completed   {@code true} only when the job ran to {@code COMPLETED}
     * @param done        committed assignments
     * @param rejected    rejected assignments
     * @param promptVersion version of the system prompt, or {@code null}
     */
    public record Request(Long jobId, Long projectId, EstimatePlan plan, BigDecimal roomsArea,
                          List<ReportWarning> extra, boolean completed, int done, int rejected,
                          String promptVersion) {
    }

    private final EstimateDao estimateDao;
    private final OfferService offerService;

    public AiSummaryWriter(EstimateDao estimateDao, OfferService offerService) {
        this.estimateDao = estimateDao;
        this.offerService = offerService;
    }

    /**
     * Builds and stores the summary.
     *
     * @return the stored document, or {@code null} when the project has no estimate
     */
    @Transactional
    public Map<String, Object> write(Request request) {
        EstimateEntity estimate = estimateDao.findByProjectId(request.projectId()).orElse(null);
        if (estimate == null) {
            log.warn("No estimate for project {}: AI summary of job {} not written", request.projectId(),
                    request.jobId());
            return null;
        }
        OfferSnapshot snapshot = offerService.buildScratchSnapshot(estimate);
        Map<String, Object> summary = build(request, snapshot);
        estimate.setAiSummary(summary);
        estimateDao.save(estimate);
        return summary;
    }

    /** Pure assembly of the document (package-visible for tests). */
    static Map<String, Object> build(Request request, OfferSnapshot snapshot) {
        Map<String, Object> report = AiReportBuilder.build(
                request.plan(), snapshot, request.roomsArea(), request.extra());
        report.remove("changes");
        boolean unpriced = request.plan() != null && !request.plan().unpriced().isEmpty();
        report.put("incomplete", unpriced || !request.completed());
        report.put("promptVersion", request.promptVersion());
        report.put("jobId", request.jobId());
        report.put("generatedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = new LinkedHashMap<>((Map<String, Object>) report.get("summary"));
        summary.put("cells", countCells(snapshot));
        Map<String, Object> assignments = new LinkedHashMap<>();
        assignments.put("done", request.done());
        assignments.put("rejected", request.rejected());
        summary.put("assignments", assignments);
        report.put("summary", summary);
        return report;
    }

    private static int countCells(OfferSnapshot snapshot) {
        int cells = 0;
        if (snapshot != null && snapshot.groups() != null) {
            for (OfferSnapshot.Group group : snapshot.groups()) {
                if (group == null || group.rows() == null) {
                    continue;
                }
                for (OfferSnapshot.Row row : group.rows()) {
                    if (row != null && row.cells() != null) {
                        cells += row.cells().size();
                    }
                }
            }
        }
        return cells;
    }
}
