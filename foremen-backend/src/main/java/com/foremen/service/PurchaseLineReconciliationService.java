package com.foremen.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.AdminDao;
import com.foremen.dao.ProcurementLineDao;
import com.foremen.dao.UnplannedExpenseCategoryDao;
import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.MatchTier;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProcurementLineEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.UnplannedExpenseCategoryEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.PurchaseLineServiceModel;
import com.foremen.service.model.mapper.ProcurementLineServiceMapper;
import com.foremen.service.model.mapper.PurchaseLineServiceMapper;
import com.foremen.service.procurement.PlannedMaterial;
import com.foremen.service.procurement.PlannedMaterialCandidateProvider;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * FOR-06-04 (Requirements 1.4, 2, 3, 4, 7.3, 7.4, 8.1, 10.1; design §5.1) — the project-scoped
 * service behind procurement <strong>reconciliation</strong>. It turns FOR-06-03's read-only
 * proposed-match review into actionable manual overrides on an {@code ACTIVE} project:
 *
 * <ul>
 *   <li>{@link #relink(Long, Long) re-link} a line to a chosen {@code Planned_Material} (R2.1, R2.2);</li>
 *   <li>{@link #clearMatch(Long) clear} a line's match (R2.3);</li>
 *   <li>{@link #classify(Long, Long) classify} a deliberately unmatched line with an
 *       {@code UnplannedExpenseCategory} (R3.1, R3.3, R3.4);</li>
 *   <li>{@link #clearClassification(Long) clear} a classification (R3.2);</li>
 *   <li>{@link #list(Long, Long, MatchStatus, Long) list} the project-wide reconciled purchases list
 *       (R4), filterable by document / match state / unplanned category.</li>
 * </ul>
 *
 * <p>Following the FOR-03-04a single-contract shape (mirrors {@code WorkReportService} /
 * {@link ProcurementDocumentService}), it implements exactly one CRUD contract —
 * {@link ProjectScopedService} <strong>over the line</strong> — supplies the standard CRUD plumbing
 * ({@link #getDao()}, {@link #getMapper()}, {@link #getEntityManager()}, {@link #getDaoModelClass()},
 * {@link #getAuditLogDao()}), the single mandatory per-entity override {@link #getProjectIdPath()}
 * &rarr; {@code "document.project.id"} (entity-creation-rules step 4, R7.3), and wires
 * {@link #allowedProjectIds(Long)} to {@link ProjectAccessCache}. Because the project-id path runs
 * through the owning document, by-id reconciliation and the LIST read are automatically
 * project-scoped (R7.3).
 *
 * <p><b>Stage gate (R2.5, R3.5, R7.4).</b> Every mutation is refused unless the owning project is
 * {@code ACTIVE} ({@link #STAGE_NOT_ACTIVE}); reads are allowed on {@code ACTIVE} and {@code COMPLETED}
 * (the tab itself is hidden on {@code DRAFT}). <b>Line invariant (R1.4).</b> The pure
 * {@link #assertInvariant(ProcurementLineEntity)} helper is run before every persist so a line always
 * lands in one of the four §4.3 shapes. <b>No notifications</b> are emitted (R8.1) — reconciliation
 * is a synchronous authoring action with no cross-role handoff.
 */
@Service
@Transactional
public class PurchaseLineReconciliationService
        implements ProjectScopedService<PurchaseLineServiceModel, PurchaseLineServiceModel,
        ProcurementLineEntity, Long> {

    // --- Message codes (bundle wiring is task 8.1) ---
    static final String NOT_FOUND_MESSAGE = "error.entity.not.found";
    static final String STAGE_NOT_ACTIVE = "error.purchases.stage.notActive";
    static final String RELINK_NOT_PLANNED = "error.purchases.relink.notPlanned";
    static final String CLASSIFY_MATCHED_LINE = "error.purchases.classify.matchedLine";
    static final String CLASSIFY_UNKNOWN_CATEGORY = "error.purchases.classify.unknownCategory";

    private final ProcurementLineDao lineDao;
    private final UnplannedExpenseCategoryDao categoryDao;
    private final ProjectAccessCache projectAccessCache;
    private final ProcurementLineServiceMapper serviceMapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final PlannedMaterialCandidateProvider candidateProvider;

    public PurchaseLineReconciliationService(ProcurementLineDao lineDao,
                                             UnplannedExpenseCategoryDao categoryDao,
                                             ProjectAccessCache projectAccessCache,
                                             ProcurementLineServiceMapper serviceMapper,
                                             AuditLogDao auditLogDao,
                                             EntityManager entityManager,
                                             PlannedMaterialCandidateProvider candidateProvider) {
        this.lineDao = lineDao;
        this.categoryDao = categoryDao;
        this.projectAccessCache = projectAccessCache;
        this.serviceMapper = serviceMapper;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.candidateProvider = candidateProvider;
    }

    // --- CRUD plumbing (inherited via ProjectScopedService) ------------------------------------

    @Override
    public AdminDao<ProcurementLineEntity, Long> getDao() {
        return lineDao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<ProcurementLineEntity, PurchaseLineServiceModel,
            PurchaseLineServiceModel> getMapper() {
        return serviceMapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<ProcurementLineEntity> getDaoModelClass() {
        return ProcurementLineEntity.class;
    }

    // --- ProjectScopedService overrides --------------------------------------------------------

    /** The single mandatory per-entity override (entity-creation-rules step 4, R7.3). */
    @Override
    public String getProjectIdPath() {
        return "document.project.id";
    }

    @Override
    public Set<Long> allowedProjectIds(Long userId) {
        return projectAccessCache.get(userId);
    }

    // --- Re-link (R2.1, R2.2) ------------------------------------------------------------------

    /**
     * Re-links a line to a chosen {@code Planned_Material} (R2.1): on an {@code ACTIVE} project (else
     * {@link #STAGE_NOT_ACTIVE}), verifies {@code materialId} is in the project's candidate set
     * resolved by the FOR-06-03 {@link PlannedMaterialCandidateProvider} (else
     * {@link #RELINK_NOT_PLANNED}, leaving the line unchanged, R2.2), then sets
     * {@code matchedMaterialId = materialId}, {@code matchStatus = MANUAL_MATCHED},
     * {@code matchTier = EXACT}, clears any {@code unplannedCategory}, asserts the invariant, and
     * writes an UPDATE audit row (R10.1).
     *
     * @param lineId     the line to re-link
     * @param materialId the chosen planned-material id (must be on the project's estimate)
     * @return the reconciled line as a list row (R4.4 — reflects the new state immediately)
     */
    @Transactional
    public PurchaseLineServiceModel relink(Long lineId, Long materialId) {
        ProcurementLineEntity line = loadForMutation(lineId);

        Long projectId = projectIdOf(line);
        if (!candidateIds(projectId).contains(materialId)) {
            // R2.2 — off-estimate target: reject, line byte-for-byte unchanged.
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, RELINK_NOT_PLANNED, materialId);
        }

        line.setMatchedMaterialId(materialId);
        line.setMatchStatus(MatchStatus.MANUAL_MATCHED);
        line.setMatchTier(MatchTier.EXACT);
        line.setUnplannedCategory(null);
        return persistReconciled(line);
    }

    // --- Clear match (R2.3) --------------------------------------------------------------------

    /**
     * Clears a line's match (R2.3): on an {@code ACTIVE} project, sets {@code matchedMaterialId = null},
     * {@code matchStatus = MANUAL_UNMATCHED}, {@code matchTier = NONE} (keeping any existing
     * {@code unplannedCategory}), asserts the invariant, and writes an UPDATE audit row (R10.1).
     *
     * @param lineId the line whose match to clear
     * @return the reconciled line as a list row
     */
    @Transactional
    public PurchaseLineServiceModel clearMatch(Long lineId) {
        ProcurementLineEntity line = loadForMutation(lineId);
        line.setMatchedMaterialId(null);
        line.setMatchStatus(MatchStatus.MANUAL_UNMATCHED);
        line.setMatchTier(MatchTier.NONE);
        return persistReconciled(line);
    }

    // --- Classify (R3.1, R3.3, R3.4) -----------------------------------------------------------

    /**
     * Classifies a deliberately unmatched line with an {@code UnplannedExpenseCategory} (R3.1): on an
     * {@code ACTIVE} project, rejects a matched line ({@code AUTO}/{@code MANUAL_MATCHED} →
     * {@link #CLASSIFY_MATCHED_LINE}, R3.3), verifies the category exists and is {@code active} (else
     * {@link #CLASSIFY_UNKNOWN_CATEGORY}, R3.4), then sets {@code unplannedCategory = category},
     * {@code matchStatus = MANUAL_UNMATCHED}, ensures {@code matchedMaterialId = null} /
     * {@code matchTier = NONE}, asserts the invariant, and writes an UPDATE audit row (R10.1).
     *
     * @param lineId     the line to classify
     * @param categoryId the active unplanned-expense category id
     * @return the reconciled line as a list row
     */
    @Transactional
    public PurchaseLineServiceModel classify(Long lineId, Long categoryId) {
        ProcurementLineEntity line = loadForMutation(lineId);

        // R3.3 — a matched line must be cleared first; classifying it is rejected.
        if (isMatched(line.getMatchStatus())) {
            throw new ForemenApiException(HttpStatus.CONFLICT, CLASSIFY_MATCHED_LINE, lineId);
        }

        // R3.4 — the category must exist and be active.
        UnplannedExpenseCategoryEntity category = categoryDao.findById(categoryId)
                .filter(UnplannedExpenseCategoryEntity::isActive)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.BAD_REQUEST, CLASSIFY_UNKNOWN_CATEGORY, categoryId));

        line.setMatchedMaterialId(null);
        line.setMatchTier(MatchTier.NONE);
        line.setMatchStatus(MatchStatus.MANUAL_UNMATCHED);
        line.setUnplannedCategory(category);
        return persistReconciled(line);
    }

    // --- Clear classification (R3.2) -----------------------------------------------------------

    /**
     * Clears a line's classification (R3.2): on an {@code ACTIVE} project, sets
     * {@code unplannedCategory = null} and keeps {@code matchStatus = MANUAL_UNMATCHED} (the line
     * stays a deliberate non-match), asserts the invariant, and writes an UPDATE audit row (R10.1).
     *
     * @param lineId the line whose classification to clear
     * @return the reconciled line as a list row
     */
    @Transactional
    public PurchaseLineServiceModel clearClassification(Long lineId) {
        ProcurementLineEntity line = loadForMutation(lineId);
        line.setUnplannedCategory(null);
        line.setMatchStatus(MatchStatus.MANUAL_UNMATCHED);
        line.setMatchedMaterialId(null);
        line.setMatchTier(MatchTier.NONE);
        return persistReconciled(line);
    }

    // --- Reconciled list (R4) ------------------------------------------------------------------

    /**
     * The project-wide reconciled purchases list (R4.1): every {@code ProcurementLine} across the
     * project's documents, each row carrying its source document, verbatim OCR fields, the resolved
     * matched-material localized name ({@code ru → pl → code}) or {@code null}, the tier + derived
     * percent + match state, and the unplanned-category localized name or {@code null}. The read is
     * project-filtered by the inherited {@code ProjectScopedService} machinery (never returns
     * off-project lines, R4.3) and narrowed by the optional filters (R4.2). Rows are ordered by
     * document then {@code lineNo} for a stable list.
     *
     * @param projectId           the project whose lines to list (required)
     * @param documentId          optional source-document filter
     * @param matchStatus         optional match-state filter
     * @param unplannedCategoryId optional unplanned-category filter
     * @return the reconciled rows; never {@code null}, possibly empty
     */
    @Transactional(readOnly = true)
    public List<PurchaseLineServiceModel> list(Long projectId,
                                               Long documentId,
                                               MatchStatus matchStatus,
                                               Long unplannedCategoryId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<ProcurementLineEntity> cq = cb.createQuery(ProcurementLineEntity.class);
        Root<ProcurementLineEntity> root = cq.from(ProcurementLineEntity.class);
        Join<ProcurementLineEntity, ProcurementDocumentEntity> document = root.join("document");

        List<Predicate> predicates = new ArrayList<>();
        // R4.3 — explicit project filter (the caller's access to the project is enforced separately;
        // the inherited LIST filter restricts to the caller's accessible projects — see below).
        predicates.add(cb.equal(document.get("project").get("id"), projectId));
        if (documentId != null) {
            predicates.add(cb.equal(document.get("id"), documentId));
        }
        if (matchStatus != null) {
            predicates.add(cb.equal(root.get("matchStatus"), matchStatus));
        }
        if (unplannedCategoryId != null) {
            predicates.add(cb.equal(root.get("unplannedCategory").get("id"), unplannedCategoryId));
        }

        // R4.3 — intersect with the inherited project-scope access filter so a caller never reads a
        // line from a project they cannot access, even when passing its id explicitly.
        var accessSpec = addRequiredQuery();
        if (accessSpec != null) {
            Predicate accessPredicate = accessSpec.toPredicate(root, cq, cb);
            if (accessPredicate != null) {
                predicates.add(accessPredicate);
            }
        }

        cq.where(predicates.toArray(new Predicate[0]));
        Order byDocument = cb.asc(document.get("id"));
        Order byLineNo = cb.asc(root.get("lineNo"));
        cq.orderBy(byDocument, byLineNo);

        List<ProcurementLineEntity> lines = entityManager.createQuery(cq).getResultList();
        if (lines.isEmpty()) {
            return List.of();
        }

        Map<Long, String> matchedNames = matchedNamesById(projectId);
        List<PurchaseLineServiceModel> rows = new ArrayList<>(lines.size());
        for (ProcurementLineEntity line : lines) {
            String matchedName = line.getMatchedMaterialId() == null
                    ? null
                    : matchedNames.get(line.getMatchedMaterialId());
            rows.add(PurchaseLineServiceMapper.toServiceModel(line, matchedName));
        }
        return rows;
    }

    // --- Line invariant (R1.4, design §4.3) ----------------------------------------------------

    /**
     * The pure line invariant (R1.4, design §4.3) asserted before every persist: a reconciled line
     * must be in exactly one of the four valid shapes.
     *
     * <ul>
     *   <li>{@code matchedMaterialId != null ⟺ matchStatus ∈ {AUTO, MANUAL_MATCHED}} and
     *       {@code matchTier != NONE};</li>
     *   <li>{@code matchedMaterialId == null ⟺ matchStatus ∈ {UNMATCHED, MANUAL_UNMATCHED}} and
     *       {@code matchTier == NONE};</li>
     *   <li>{@code unplannedCategory != null ⟹ matchedMaterialId == null} (a matched line carries no
     *       classification).</li>
     * </ul>
     *
     * <p>Pure and total — no I/O — so it is property-testable in isolation (task 4.1a).
     *
     * @param line the line to validate
     * @throws IllegalStateException when the line violates the invariant (a programming error)
     */
    static void assertInvariant(ProcurementLineEntity line) {
        if (line == null) {
            throw new IllegalStateException("Reconciled line must not be null");
        }
        MatchStatus status = line.getMatchStatus();
        boolean hasMaterial = line.getMatchedMaterialId() != null;
        boolean matchedStatus = status == MatchStatus.AUTO || status == MatchStatus.MANUAL_MATCHED;
        boolean tierNone = line.getMatchTier() == MatchTier.NONE;

        // matchedMaterialId != null ⟺ status ∈ {AUTO, MANUAL_MATCHED}
        if (hasMaterial != matchedStatus) {
            throw new IllegalStateException(
                    "Line invariant violated: matchedMaterialId/status mismatch (status=" + status
                            + ", matchedMaterialId=" + line.getMatchedMaterialId() + ")");
        }
        // matched ⟹ tier != NONE ; unmatched ⟹ tier == NONE
        if (hasMaterial == tierNone) {
            throw new IllegalStateException(
                    "Line invariant violated: tier/match mismatch (status=" + status
                            + ", tier=" + line.getMatchTier()
                            + ", matchedMaterialId=" + line.getMatchedMaterialId() + ")");
        }
        // unplannedCategory != null ⟹ matchedMaterialId == null
        if (line.getUnplannedCategory() != null && hasMaterial) {
            throw new IllegalStateException(
                    "Line invariant violated: a matched line cannot carry an unplanned category "
                            + "(status=" + status + ")");
        }
    }

    // --- Helpers -------------------------------------------------------------------------------

    /**
     * Loads a line by id for a mutation: the inherited project-scope guard runs first (an
     * out-of-scope / missing line is a uniform 404), then the owning project's {@code ACTIVE}-stage
     * gate (R2.5, R3.5, R7.4).
     */
    private ProcurementLineEntity loadForMutation(Long lineId) {
        assertProjectAccess(lineId);
        ProcurementLineEntity line = lineDao.findById(lineId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE, lineId));
        assertProjectActive(projectIdOf(line));
        return line;
    }

    /** Asserts the invariant, persists the mutated line, writes an UPDATE audit row (R10.1), and projects it. */
    private PurchaseLineServiceModel persistReconciled(ProcurementLineEntity line) {
        assertInvariant(line);
        ProcurementLineEntity saved = lineDao.save(line);
        entityManager.flush();
        saveAudit(null, saved, "UPDATE");

        String matchedName = null;
        if (saved.getMatchedMaterialId() != null) {
            matchedName = matchedNamesById(projectIdOf(saved)).get(saved.getMatchedMaterialId());
        }
        return PurchaseLineServiceMapper.toServiceModel(saved, matchedName);
    }

    /** The owning project's id for a line, traversing {@code document.project}; {@code null} when unresolved. */
    private static Long projectIdOf(ProcurementLineEntity line) {
        ProcurementDocumentEntity document = line.getDocument();
        if (document == null || document.getProject() == null) {
            return null;
        }
        return document.getProject().getId();
    }

    /** The project's candidate-material ids (R2.2), empty when the project has none. */
    private Set<Long> candidateIds(Long projectId) {
        Set<Long> ids = new java.util.HashSet<>();
        if (projectId == null) {
            return ids;
        }
        for (PlannedMaterial candidate : candidateProvider.candidatesFor(projectId)) {
            if (candidate != null && candidate.materialId() != null) {
                ids.add(candidate.materialId());
            }
        }
        return ids;
    }

    /** The project's candidate display names keyed by material id ({@code ru → pl → code}). */
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

    /** A matched line carries a material (R3.3): {@code AUTO} or {@code MANUAL_MATCHED}. */
    private static boolean isMatched(MatchStatus status) {
        return status == MatchStatus.AUTO || status == MatchStatus.MANUAL_MATCHED;
    }

    /** Refuses a reconciliation mutation unless the owning project is {@code ACTIVE} (R2.5, R3.5, R7.4). */
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
}
