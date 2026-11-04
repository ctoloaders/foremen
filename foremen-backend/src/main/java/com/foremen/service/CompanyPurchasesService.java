package com.foremen.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.controller.model.CompanyDocumentDetailDto;
import com.foremen.controller.model.CompanyDocumentRowDto;
import com.foremen.controller.model.CompanyLineRowDto;
import com.foremen.controller.model.CompanyRelinkResponse;
import com.foremen.dao.model.BankTransactionEntity;
import com.foremen.dao.model.BankTransactionLinkEntity;
import com.foremen.dao.model.DocumentPaymentStatus;
import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProcurementLineEntity;
import com.foremen.dao.model.UnplannedExpenseCategoryEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.model.ProcurementDocumentServiceExtendedModel;
import com.foremen.service.model.mapper.ProcurementDocumentServiceMapper;
import com.foremen.service.procurement.LinkStatus;
import com.foremen.service.procurement.LinkStatusResolver;
import com.foremen.service.procurement.PlannedMaterialCandidateProvider;
import com.foremen.service.procurement.ProcurementLedgerEntryDto;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * FOR-07-03 (Requirements 4.1-4.5, 4.7, 4.8, 6.1-6.3, 10.3, 10.5, 14.1) - the company-wide read /
 * reprocess / delete layer over {@link ProcurementDocumentEntity}.
 *
 * <p>Deliberately <b>not</b> a {@code ProjectScopedService}: access is granted by the ABAC role grant
 * {@code COMPANY_PURCHASES} on the controller (ADMIN / FINANCIER / MANAGER), so the caller sees the
 * documents of every project and every no-project document. The project-scope guard is untouched; the
 * write paths call the {@code *Unchecked} variants of {@link ProcurementDocumentService}.
 *
 * <p>N+1 control (R14.1): list pages are loaded with fetch joins for project / uploader / category, and
 * the per-document line counts and ledger links come from one grouped / one {@code IN} query for the
 * whole page. Signed image URLs are produced only by the single-document read (R14.2).
 */
@Service
@Transactional
public class CompanyPurchasesService {

    static final String FILTER_CONFLICT = "error.companyProcurement.filter.conflict";
    static final String NOT_FOUND = "error.entity.not.found";

    /** Default and maximum page size (R4.8). */
    public static final int DEFAULT_PAGE_SIZE = 25;
    public static final int MAX_PAGE_SIZE = 100;

    private static final String PROJECT = "project";
    private static final String DOCUMENT = "document";
    private static final String ID = "id";
    private static final String MATCH_STATUS = "matchStatus";
    private static final String UNPLANNED_CATEGORY = "unplannedCategory";
    private static final String UPLOADED_AT = "uploadedAt";

    /** List filters (R4.3). All optional; {@code projectId} and {@code noProject} are exclusive. */
    public record Filter(Long projectId, boolean noProject, LinkStatus linkStatus,
                         LocalDate uploadedFrom, LocalDate uploadedTo, Long uploadedById,
                         Long expenseCategoryId, String q) {

        /** No filter: all projects and no-project documents. */
        public static Filter none() {
            return new Filter(null, false, null, null, null, null, null, null);
        }
    }

    private final EntityManager entityManager;
    private final ProcurementDocumentService documentService;
    private final ProcurementReadFields readFields;
    private final DocumentRelinkService relinkService;

    public CompanyPurchasesService(EntityManager entityManager,
                                   ProcurementDocumentService documentService,
                                   ProcurementDocumentServiceMapper serviceMapper,
                                   ImageStorage imageStorage,
                                   PlannedMaterialCandidateProvider candidateProvider,
                                   DocumentRelinkService relinkService) {
        this.entityManager = entityManager;
        this.documentService = documentService;
        this.relinkService = relinkService;
        this.readFields = new ProcurementReadFields(serviceMapper, imageStorage, candidateProvider);
    }

    // --- reads ---------------------------------------------------------------------------------

    /** R4.1 / R4.3 / R4.4 / R4.8 - paged document rows, newest upload first. */
    @Transactional(readOnly = true)
    public Page<CompanyDocumentRowDto> listDocuments(Filter filter, int page, int size) {
        Filter f = filter == null ? Filter.none() : filter;
        validate(f);
        Pageable pageable = pageable(page, size);
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<ProcurementDocumentEntity> countRoot = countQuery.from(ProcurementDocumentEntity.class);
        countQuery.select(cb.count(countRoot))
                .where(documentPredicates(cb, countQuery, countRoot, f).toArray(new Predicate[0]));
        long total = entityManager.createQuery(countQuery).getSingleResult();
        if (total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        CriteriaQuery<ProcurementDocumentEntity> dataQuery = cb.createQuery(ProcurementDocumentEntity.class);
        Root<ProcurementDocumentEntity> root = dataQuery.from(ProcurementDocumentEntity.class);
        root.fetch(PROJECT, JoinType.LEFT);
        root.fetch("uploadedBy", JoinType.LEFT);
        root.fetch("expenseCategory", JoinType.LEFT);
        dataQuery.select(root)
                .where(documentPredicates(cb, dataQuery, root, f).toArray(new Predicate[0]))
                .orderBy(cb.desc(root.get(UPLOADED_AT)), cb.desc(root.get(ID)));
        List<ProcurementDocumentEntity> documents = entityManager.createQuery(dataQuery)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        List<Long> ids = documents.stream().map(ProcurementDocumentEntity::getId).toList();
        Set<Long> withProject = new LinkedHashSet<>();
        documents.stream().filter(d -> d.getProject() != null).forEach(d -> withProject.add(d.getId()));
        Map<Long, Map<LinkStatus, Integer>> counts = linkCountsByDocument(ids, withProject);
        Map<Long, List<ProcurementLedgerEntryDto>> ledger = ledgerByDocument(ids);

        List<CompanyDocumentRowDto> rows = new ArrayList<>(documents.size());
        for (ProcurementDocumentEntity d : documents) {
            List<ProcurementLedgerEntryDto> entries = ledger.getOrDefault(d.getId(), List.of());
            rows.add(toRow(d, counts.getOrDefault(d.getId(), emptyCounts()), entries,
                    ProcurementDocumentServiceMapper.paymentStatus(d.getDocumentTotal(), entries),
                    d.getUploadedBy() == null ? null : d.getUploadedBy().getName(), null, null));
        }
        return new PageImpl<>(rows, pageable, total);
    }

    /** R4.1 / R4.5 / R4.3 - paged flat line rows (document order, then line number). */
    // Not readOnly: the planned-material candidate lookup may lazily create the project estimate (same as the project procurement read path).
    @Transactional
    public Page<CompanyLineRowDto> listLines(Filter filter, int page, int size) {
        Filter f = filter == null ? Filter.none() : filter;
        validate(f);
        Pageable pageable = pageable(page, size);
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<ProcurementLineEntity> countRoot = countQuery.from(ProcurementLineEntity.class);
        Join<ProcurementLineEntity, ProcurementDocumentEntity> countDoc = countRoot.join(DOCUMENT);
        countQuery.select(cb.count(countRoot))
                .where(linePredicates(cb, countRoot, countDoc, f).toArray(new Predicate[0]));
        long total = entityManager.createQuery(countQuery).getSingleResult();
        if (total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        CriteriaQuery<ProcurementLineEntity> dataQuery = cb.createQuery(ProcurementLineEntity.class);
        Root<ProcurementLineEntity> root = dataQuery.from(ProcurementLineEntity.class);
        @SuppressWarnings("unchecked")
        Join<ProcurementLineEntity, ProcurementDocumentEntity> doc =
                (Join<ProcurementLineEntity, ProcurementDocumentEntity>) (Join<?, ?>) root.fetch(DOCUMENT, JoinType.INNER);
        doc.fetch(PROJECT, JoinType.LEFT);
        root.fetch(UNPLANNED_CATEGORY, JoinType.LEFT);
        dataQuery.select(root)
                .where(linePredicates(cb, root, doc, f).toArray(new Predicate[0]))
                .orderBy(cb.desc(doc.get(UPLOADED_AT)), cb.desc(doc.get(ID)), cb.asc(root.get("lineNo")));
        List<ProcurementLineEntity> lines = entityManager.createQuery(dataQuery)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        // One candidate lookup per distinct project on the page (not per line).
        Map<Long, Map<Long, String>> namesByProject = new HashMap<>();
        for (ProcurementLineEntity line : lines) {
            Long projectId = projectIdOf(line.getDocument());
            if (projectId != null) {
                namesByProject.computeIfAbsent(projectId, readFields::matchedNamesById);
            }
        }
        List<CompanyLineRowDto> rows = new ArrayList<>(lines.size());
        for (ProcurementLineEntity line : lines) {
            Long projectId = projectIdOf(line.getDocument());
            Map<Long, String> names = projectId == null ? Map.of() : namesByProject.get(projectId);
            String matchedName = line.getMatchedMaterialId() == null ? null : names.get(line.getMatchedMaterialId());
            rows.add(toLineRow(line, matchedName));
        }
        return new PageImpl<>(rows, pageable, total);
    }

    /**
     * R4.1 / R4.7 - one document with its lines and signed raw / processed image URLs. Gated by the
     * role grant only (no project membership), so a no-project document is readable.
     */
    // Not readOnly: the planned-material candidate lookup may lazily create the project estimate (same as the project procurement read path).
    @Transactional
    public CompanyDocumentDetailDto getDocument(Long id) {
        ProcurementDocumentEntity entity = load(id);
        ProcurementDocumentServiceExtendedModel model = readFields.toReadModel(entity);
        Map<Integer, String> namesByLineNo = new HashMap<>();
        if (model.getLines() != null) {
            model.getLines().forEach(l -> namesByLineNo.put(l.lineNo(), l.matchedMaterialName()));
        }
        List<CompanyLineRowDto> lines = new ArrayList<>();
        Map<LinkStatus, Integer> counts = emptyCounts();
        for (ProcurementLineEntity line : entity.getLines()) {
            CompanyLineRowDto row = toLineRow(line, namesByLineNo.get(line.getLineNo()));
            lines.add(row);
            counts.merge(row.linkStatus(), 1, Integer::sum);
        }
        CompanyDocumentRowDto document = toRow(entity, counts,
                model.getLedgerEntries() == null ? List.of() : model.getLedgerEntries(),
                model.getPaymentStatus(), model.getUploaderName(),
                model.getRawImageUrl(), model.getProcessedImageUrl());
        return new CompanyDocumentDetailDto(document, lines);
    }

    // --- writes --------------------------------------------------------------------------------

    /** R5 - (re)links a document to an {@code ACTIVE} project and returns the updated row with counts. */
    public CompanyRelinkResponse relink(Long id, Long projectId) {
        DocumentRelinkService.Result result = relinkService.relink(id, projectId);
        return new CompanyRelinkResponse(getDocument(id).document(),
                result.matchedLines(), result.resetLines());
    }

    /** R6.1 / R6.3 - re-run OCR for a {@code FAILED} / {@code PARSED} document; no membership check. */
    public CompanyDocumentRowDto reprocess(Long id) {
        documentService.reprocessUnchecked(id);
        return getDocument(id).document();
    }

    /** R6.2 / R6.3 - delete a document (cascade, image cleanup, audit); no membership check. */
    public void delete(Long id) {
        documentService.deleteUnchecked(id);
    }

    // --- predicates ----------------------------------------------------------------------------

    /** R4.3 - {@code projectId} and {@code noProject=true} together are a 400. */
    static void validate(Filter f) {
        if (f.projectId() != null && f.noProject()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, FILTER_CONFLICT);
        }
    }

    /** R4.8 - page index clamped at 0, size defaulted to 25 and capped at 100. */
    static Pageable pageable(int page, int size) {
        int effectiveSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(Math.max(page, 0), effectiveSize);
    }

    private List<Predicate> documentPredicates(CriteriaBuilder cb, CommonAbstractCriteria query,
                                               Path<ProcurementDocumentEntity> doc, Filter f) {
        List<Predicate> predicates = commonPredicates(cb, doc, f);
        if (f.linkStatus() == LinkStatus.NO_PROJECT) {
            predicates.add(doc.get(PROJECT).isNull());
        } else if (f.linkStatus() != null) {
            Subquery<Long> sub = query.subquery(Long.class);
            Root<ProcurementLineEntity> line = sub.from(ProcurementLineEntity.class);
            sub.select(line.get(ID)).where(
                    cb.equal(line.get(DOCUMENT).get(ID), doc.get(ID)),
                    linkPredicate(cb, doc, line.get(MATCH_STATUS),
                            line.get(UNPLANNED_CATEGORY).get(ID), f.linkStatus()));
            predicates.add(cb.exists(sub));
        }
        return predicates;
    }

    private List<Predicate> linePredicates(CriteriaBuilder cb, Root<ProcurementLineEntity> line,
                                           Path<ProcurementDocumentEntity> doc, Filter f) {
        List<Predicate> predicates = commonPredicates(cb, doc, f);
        if (f.linkStatus() != null) {
            predicates.add(linkPredicate(cb, doc, line.get(MATCH_STATUS),
                    line.get(UNPLANNED_CATEGORY).get(ID), f.linkStatus()));
        }
        return predicates;
    }

    private List<Predicate> commonPredicates(CriteriaBuilder cb, Path<ProcurementDocumentEntity> doc, Filter f) {
        List<Predicate> predicates = new ArrayList<>();
        if (f.projectId() != null) {
            predicates.add(cb.equal(doc.get(PROJECT).get(ID), f.projectId()));
        } else if (f.noProject()) {
            predicates.add(doc.get(PROJECT).isNull());
        }
        if (f.uploadedFrom() != null) {
            predicates.add(cb.greaterThanOrEqualTo(doc.<OffsetDateTime>get(UPLOADED_AT), startOfDay(f.uploadedFrom())));
        }
        if (f.uploadedTo() != null) {
            predicates.add(cb.lessThan(doc.<OffsetDateTime>get(UPLOADED_AT), startOfDay(f.uploadedTo().plusDays(1))));
        }
        if (f.uploadedById() != null) {
            predicates.add(cb.equal(doc.get("uploadedBy").get(ID), f.uploadedById()));
        }
        if (f.expenseCategoryId() != null) {
            predicates.add(cb.equal(doc.get("expenseCategory").get(ID), f.expenseCategoryId()));
        }
        if (f.q() != null && !f.q().isBlank()) {
            String pattern = "%" + escapeLike(f.q().trim().toLowerCase(Locale.ROOT)) + "%";
            predicates.add(cb.or(
                    cb.like(cb.lower(doc.get("supplierName")), pattern, '\\'),
                    cb.like(cb.lower(doc.get("invoiceNumber")), pattern, '\\')));
        }
        return predicates;
    }

    /**
     * R4.6 as a SQL predicate: {@code NO_PROJECT} - no project; {@code LINKED} - matched;
     * {@code CLASSIFIED} - unmatched with a category; {@code UNLINKED} - unmatched without one. The
     * last three require the document to have a project, mirroring {@link LinkStatusResolver}.
     */
    private Predicate linkPredicate(CriteriaBuilder cb, Path<ProcurementDocumentEntity> doc,
                                    Path<MatchStatus> matchStatus, Path<?> categoryId, LinkStatus status) {
        Predicate hasProject = doc.get(PROJECT).isNotNull();
        return switch (status) {
            case NO_PROJECT -> doc.get(PROJECT).isNull();
            case LINKED -> cb.and(hasProject,
                    matchStatus.in(MatchStatus.AUTO, MatchStatus.MANUAL_MATCHED));
            case CLASSIFIED -> cb.and(hasProject,
                    matchStatus.in(MatchStatus.UNMATCHED, MatchStatus.MANUAL_UNMATCHED),
                    categoryId.isNotNull());
            case UNLINKED -> cb.and(hasProject,
                    matchStatus.in(MatchStatus.UNMATCHED, MatchStatus.MANUAL_UNMATCHED),
                    categoryId.isNull());
        };
    }

    private static OffsetDateTime startOfDay(LocalDate date) {
        ZoneId zone = ZoneId.systemDefault();
        return date.atStartOfDay(zone).toOffsetDateTime();
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // --- bulk aggregates (R14.1) ---------------------------------------------------------------

    /** One grouped query for the page's per-document, per-{@link LinkStatus} line counts. */
    private Map<Long, Map<LinkStatus, Integer>> linkCountsByDocument(Collection<Long> ids, Set<Long> withProject) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = entityManager.createQuery(
                        "select l.document.id, l.matchStatus, count(l.unplannedCategory.id), count(l) "
                                + "from ProcurementLineEntity l where l.document.id in :ids "
                                + "group by l.document.id, l.matchStatus", Object[].class)
                .setParameter("ids", ids)
                .getResultList();
        return aggregateLinkCounts(rows, withProject);
    }

    /**
     * Folds grouped rows {@code [documentId, matchStatus, classifiedCount, totalCount]} into
     * per-document {@link LinkStatus} counts via {@link LinkStatusResolver}; the counts of a document
     * sum to its line count (property 4).
     */
    static Map<Long, Map<LinkStatus, Integer>> aggregateLinkCounts(List<Object[]> rows, Set<Long> withProject) {
        Map<Long, Map<LinkStatus, Integer>> result = new HashMap<>();
        for (Object[] row : rows) {
            Long documentId = (Long) row[0];
            MatchStatus status = (MatchStatus) row[1];
            int withCategory = ((Number) row[2]).intValue();
            int total = ((Number) row[3]).intValue();
            boolean hasProject = withProject.contains(documentId);
            Map<LinkStatus, Integer> counts = result.computeIfAbsent(documentId, k -> emptyCounts());
            counts.merge(LinkStatusResolver.resolve(hasProject, status, true), withCategory, Integer::sum);
            counts.merge(LinkStatusResolver.resolve(hasProject, status, false), total - withCategory, Integer::sum);
        }
        return result;
    }

    static Map<LinkStatus, Integer> emptyCounts() {
        Map<LinkStatus, Integer> counts = new EnumMap<>(LinkStatus.class);
        for (LinkStatus s : LinkStatus.values()) {
            counts.put(s, 0);
        }
        return counts;
    }

    /** One {@code IN} query for the ledger links of the page's documents. */
    private Map<Long, List<ProcurementLedgerEntryDto>> ledgerByDocument(Collection<Long> ids) {
        Map<Long, List<ProcurementLedgerEntryDto>> byDocument = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return byDocument;
        }
        List<BankTransactionLinkEntity> links = entityManager.createQuery(
                        "select l from BankTransactionLinkEntity l join fetch l.transaction t "
                                + "join fetch t.account where l.procurementDocument.id in :ids "
                                + "order by t.bookingDate, t.id", BankTransactionLinkEntity.class)
                .setParameter("ids", ids)
                .getResultList();
        for (BankTransactionLinkEntity link : links) {
            BankTransactionEntity txn = link.getTransaction();
            byDocument.computeIfAbsent(link.getProcurementDocument().getId(), k -> new ArrayList<>())
                    .add(new ProcurementLedgerEntryDto(
                            txn.getId(),
                            txn.getChannel() == null ? null : txn.getChannel().name(),
                            txn.getBookingDate(),
                            txn.getAccount() == null ? null : txn.getAccount().getName(),
                            link.getAmount()));
        }
        return byDocument;
    }

    // --- mapping -------------------------------------------------------------------------------

    private ProcurementDocumentEntity load(Long id) {
        ProcurementDocumentEntity entity = id == null ? null
                : entityManager.find(ProcurementDocumentEntity.class, id);
        if (entity == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, NOT_FOUND, id);
        }
        return entity;
    }

    private static Long projectIdOf(ProcurementDocumentEntity document) {
        return document.getProject() == null ? null : document.getProject().getId();
    }

    private CompanyDocumentRowDto toRow(ProcurementDocumentEntity d, Map<LinkStatus, Integer> counts,
                                        List<ProcurementLedgerEntryDto> ledgerEntries,
                                        DocumentPaymentStatus paymentStatus, String uploaderName,
                                        String rawImageUrl, String processedImageUrl) {
        int lineCount = counts.values().stream().mapToInt(Integer::intValue).sum();
        UnplannedExpenseCategoryEntity category = d.getExpenseCategory();
        boolean hasImages = notBlank(d.getRawObjectKey()) || notBlank(d.getProcessedObjectKey());
        return new CompanyDocumentRowDto(
                d.getId(),
                projectIdOf(d),
                d.getProject() == null ? null : d.getProject().getName(),
                d.getSupplierName(),
                d.getDocumentTotal(),
                d.getStatus(),
                d.getFailureReason(),
                d.getUploadedAt(),
                uploaderName,
                category == null ? null : category.getId(),
                category == null ? null : localizedName(category),
                paymentStatus,
                ledgerEntries,
                lineCount,
                counts,
                hasImages,
                rawImageUrl,
                processedImageUrl);
    }

    private CompanyLineRowDto toLineRow(ProcurementLineEntity line, String matchedName) {
        ProcurementDocumentEntity d = line.getDocument();
        UnplannedExpenseCategoryEntity category = line.getUnplannedCategory();
        return new CompanyLineRowDto(
                line.getId(),
                d.getId(),
                d.getSupplierName(),
                d.getUploadedAt(),
                projectIdOf(d),
                d.getProject() == null ? null : d.getProject().getName(),
                line.getLineNo(),
                line.getRawName(),
                line.getQuantity(),
                line.getUnitHint(),
                line.getUnitPrice(),
                line.getLineTotal(),
                line.getManufacturerHint(),
                line.getMatchedMaterialId(),
                matchedName,
                line.getMatchTier(),
                line.getMatchStatus(),
                category == null ? null : category.getId(),
                category == null ? null : localizedName(category),
                LinkStatusResolver.resolve(d.getProject() != null, line.getMatchStatus(), category != null));
    }

    private static String localizedName(UnplannedExpenseCategoryEntity category) {
        boolean pl = "pl".equalsIgnoreCase(LocaleContextHolder.getLocale().getLanguage());
        String primary = pl ? category.getNamePL() : category.getNameRU();
        String fallback = pl ? category.getNameRU() : category.getNamePL();
        if (notBlank(primary)) {
            return primary;
        }
        return notBlank(fallback) ? fallback : category.getCode();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
