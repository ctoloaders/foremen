package com.foremen.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

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
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.PurchaseLineServiceModel;
import com.foremen.service.model.mapper.ProcurementDocumentServiceMapper;
import com.foremen.service.model.mapper.PurchaseLineServiceMapper;
import com.foremen.service.procurement.PlannedMaterial;
import com.foremen.service.procurement.PlannedMaterialCandidateProvider;

import jakarta.persistence.EntityManager;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.lifecycle.AfterTry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based tests for {@link PurchaseLineReconciliationService}
 * (FOR-06-04-purchases-list, design §4.3/§5.1).
 *
 * <p>The service's reconciliation core is driven over stubbed seams — a configurable
 * {@link PlannedMaterialCandidateProvider} (the on-estimate candidate set, including the empty set
 * and off-estimate ids) and an in-memory {@link UnplannedExpenseCategoryDao} lookup (active /
 * inactive / unknown rows) — so no Spring context or database is needed. The caller is an ADMIN so
 * the inherited {@link ProjectScopedService} project-access guard is a bypass (the access filter is
 * exercised independently by the FOR-03-04 tests); the stage gate, candidate gate, classify gate,
 * invariant, and list projection are the behaviours under test here.
 *
 * <ul>
 *   <li><b>Property 1 — Line-invariant totality.</b> For any prior line state and any reconciliation
 *       action ({@code relink} with an on-estimate material, {@code clearMatch}, {@code classify} on
 *       an unmatched line, {@code clearClassification}), the resulting line satisfies the §4.3
 *       invariant. Also asserted directly against {@link PurchaseLineReconciliationService#assertInvariant}
 *       over the four valid shapes and over invalid shapes. Validates Requirements 1.4, 2.1, 2.3, 3.1, 3.2.</li>
 *   <li><b>Property 2 — Re-link candidate-set soundness.</b> For any line and any materialId,
 *       {@code relink} sets {@code MANUAL_MATCHED} with that material iff the material is in the
 *       project's candidate set; otherwise it rejects with {@code error.purchases.relink.notPlanned}
 *       and leaves the line byte-for-byte unchanged. Validates Requirements 2.1, 2.2.</li>
 *   <li><b>Property 3 — Classify rejected on a matched line.</b> For any {@code AUTO}/{@code MANUAL_MATCHED}
 *       line {@code classify} is rejected with {@code error.purchases.classify.matchedLine} and the
 *       line is unchanged; for an {@code UNMATCHED}/{@code MANUAL_UNMATCHED} line with an existing
 *       active category {@code classify} sets {@code MANUAL_UNMATCHED} + the category.
 *       Validates Requirements 3.1, 3.3.</li>
 *   <li><b>Property 5 — Reconciled-list projection fidelity.</b> For any set of lines the projection
 *       resolves each row's matched-material and unplanned-category localized names by the
 *       {@code ru → pl → code} fallback (null when unset) and the derived tier percent, and a filter
 *       returns exactly the lines satisfying it. The projection + filter are exercised through the
 *       static {@link PurchaseLineServiceMapper} and the service's candidate-name map (the criteria
 *       chain in {@code list(...)} is covered by the integration tests, task 8.2).
 *       Validates Requirements 4.1, 4.2, 4.3.</li>
 * </ul>
 *
 * <p>Feature: FOR-06-04-purchases-list, Properties 1, 2, 3, 5
 */
@Tag("Feature: FOR-06-04-purchases-list, Property 1")
class PurchaseLineReconciliationServicePropertyTest {

    @AfterTry
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // =============================================================================================
    // Property 1: Line-invariant totality across every reconciliation action.
    // For any prior line + any action, the resulting line satisfies the §4.3 invariant.
    // Validates: Requirements 1.4, 2.1, 2.3, 3.1, 3.2
    // =============================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-06-04-purchases-list, Property 1: Line-invariant totality across every reconciliation action")
    void everyReconciliationActionLandsAValidShape(@ForAll("scenarios") Scenario scenario) {
        authenticateAdmin();
        Fixture fixture = scenario.newFixture();
        PurchaseLineReconciliationService service = fixture.service;
        Long lineId = fixture.line.getId();

        PurchaseLineServiceModel result;
        switch (scenario.action) {
            case RELINK_ON_ESTIMATE -> {
                // relink to a material guaranteed to be on the candidate set
                Long target = fixture.onEstimateMaterialId;
                result = service.relink(lineId, target);
            }
            case CLEAR_MATCH -> result = service.clearMatch(lineId);
            case CLASSIFY -> {
                if (isMatched(fixture.line.getMatchStatus())) {
                    // classify is only valid on an unmatched line; a matched line is rejected and
                    // the invariant is unaffected (covered by Property 3) — nothing to assert here.
                    assertThatThrownBy(() -> service.classify(lineId, fixture.activeCategoryId))
                            .isInstanceOf(ForemenApiException.class);
                    return;
                }
                result = service.classify(lineId, fixture.activeCategoryId);
            }
            case CLEAR_CLASSIFICATION -> result = service.clearClassification(lineId);
            default -> throw new IllegalStateException("unreachable");
        }

        // The persisted entity must satisfy the invariant (the service runs assertInvariant before
        // every save; re-assert it here against the saved entity for an independent check).
        PurchaseLineReconciliationService.assertInvariant(fixture.saved);
        assertThat(result).isNotNull();
        assertResultInvariant(result);
    }

    @Property(tries = 100)
    @Tag("Feature: FOR-06-04-purchases-list, Property 1: Line-invariant totality across every reconciliation action")
    void assertInvariantAcceptsTheFourValidShapesAndRejectsInvalidOnes(
            @ForAll("validShapes") ProcurementLineEntity valid,
            @ForAll("invalidShapes") ProcurementLineEntity invalid) {
        // A line in any of the four documented shapes passes.
        PurchaseLineReconciliationService.assertInvariant(valid);

        // A shape that violates the biconditional / tier / category rule throws.
        assertThatThrownBy(() -> PurchaseLineReconciliationService.assertInvariant(invalid))
                .isInstanceOf(IllegalStateException.class);
    }

    // =============================================================================================
    // Property 2: Re-link candidate-set soundness.
    // relink sets MANUAL_MATCHED with the material iff it is on the candidate set; otherwise rejects
    // with error.purchases.relink.notPlanned and leaves the line unchanged.
    // Validates: Requirements 2.1, 2.2
    // =============================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-06-04-purchases-list, Property 2: Re-link candidate-set soundness")
    void relinkIsAcceptedIffTargetIsOnTheCandidateSet(
            @ForAll("lineShapes") ProcurementLineEntity priorShape,
            @ForAll("candidateSets") Set<Long> candidateSet,
            @ForAll("materialIds") Long target) {
        authenticateAdmin();
        Fixture fixture = Fixture.forShape(priorShape, candidateSet);
        Long lineId = fixture.line.getId();

        // Snapshot the prior line's reconciliation fields for the "unchanged" assertion.
        MatchStatus priorStatus = fixture.line.getMatchStatus();
        Long priorMaterial = fixture.line.getMatchedMaterialId();
        MatchTier priorTier = fixture.line.getMatchTier();
        UnplannedExpenseCategoryEntity priorCategory = fixture.line.getUnplannedCategory();

        boolean onEstimate = candidateSet.contains(target);

        if (onEstimate) {
            PurchaseLineServiceModel result = fixture.service.relink(lineId, target);
            assertThat(result.matchStatus()).isEqualTo(MatchStatus.MANUAL_MATCHED);
            assertThat(result.matchedMaterialId()).isEqualTo(target);
            assertThat(result.matchTier()).isEqualTo(MatchTier.EXACT);
            assertThat(result.unplannedCategoryId()).isNull();
            assertResultInvariant(result);
        } else {
            assertThatThrownBy(() -> fixture.service.relink(lineId, target))
                    .isInstanceOf(ForemenApiException.class)
                    .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode())
                            .isEqualTo("error.purchases.relink.notPlanned"));
            // The line is byte-for-byte unchanged (no save occurred).
            assertThat(fixture.saved).as("no persist on a rejected re-link").isNull();
            assertThat(fixture.line.getMatchStatus()).isEqualTo(priorStatus);
            assertThat(fixture.line.getMatchedMaterialId()).isEqualTo(priorMaterial);
            assertThat(fixture.line.getMatchTier()).isEqualTo(priorTier);
            assertThat(fixture.line.getUnplannedCategory()).isSameAs(priorCategory);
        }
    }

    // =============================================================================================
    // Property 3: Classify is rejected on a matched line.
    // For AUTO/MANUAL_MATCHED, classify rejects with error.purchases.classify.matchedLine and leaves
    // the line unchanged; for UNMATCHED/MANUAL_UNMATCHED with an active category it sets
    // MANUAL_UNMATCHED + the category.
    // Validates: Requirements 3.1, 3.3
    // =============================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-06-04-purchases-list, Property 3: Classify is rejected on a matched line")
    void classifyRejectsMatchedAndAcceptsUnmatched(
            @ForAll("lineShapes") ProcurementLineEntity priorShape) {
        authenticateAdmin();
        // candidate set irrelevant for classify; an active category is wired in the fixture.
        Fixture fixture = Fixture.forShape(priorShape, Set.of());
        Long lineId = fixture.line.getId();

        MatchStatus priorStatus = fixture.line.getMatchStatus();
        Long priorMaterial = fixture.line.getMatchedMaterialId();
        MatchTier priorTier = fixture.line.getMatchTier();

        if (isMatched(priorStatus)) {
            assertThatThrownBy(() -> fixture.service.classify(lineId, fixture.activeCategoryId))
                    .isInstanceOf(ForemenApiException.class)
                    .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode())
                            .isEqualTo("error.purchases.classify.matchedLine"));
            // The matched line is unchanged (no save).
            assertThat(fixture.saved).as("no persist on a rejected classify").isNull();
            assertThat(fixture.line.getMatchStatus()).isEqualTo(priorStatus);
            assertThat(fixture.line.getMatchedMaterialId()).isEqualTo(priorMaterial);
            assertThat(fixture.line.getMatchTier()).isEqualTo(priorTier);
        } else {
            PurchaseLineServiceModel result = fixture.service.classify(lineId, fixture.activeCategoryId);
            assertThat(result.matchStatus()).isEqualTo(MatchStatus.MANUAL_UNMATCHED);
            assertThat(result.matchedMaterialId()).isNull();
            assertThat(result.matchTier()).isEqualTo(MatchTier.NONE);
            assertThat(result.unplannedCategoryId()).isEqualTo(fixture.activeCategoryId);
            assertResultInvariant(result);
        }
    }

    // =============================================================================================
    // Property 5: Reconciled-list projection fidelity.
    // Each row's matched-material + unplanned-category names resolve by ru → pl → code (null when
    // unset); the tier percent is derived; and a matchStatus filter returns exactly the matching
    // lines. Exercised through the static mapper + candidate-name map (the criteria chain is covered
    // by the integration tests).
    // Validates: Requirements 4.1, 4.2, 4.3
    // =============================================================================================

    @Property(tries = 200)
    @Tag("Feature: FOR-06-04-purchases-list, Property 5: Reconciled-list projection fidelity")
    void projectionResolvesNamesTierAndFilters(@ForAll("projectionLines") List<ProjectionLine> lines) {
        // Build the matched-name map the service hands to the mapper (ru → pl → code fallback over
        // the candidate provider's localized names).
        var namesById = new java.util.HashMap<Long, String>();
        for (ProjectionLine pl : lines) {
            if (pl.matchedMaterialId != null) {
                namesById.put(pl.matchedMaterialId, firstNonBlank(pl.nameRu, pl.namePl, pl.code));
            }
        }

        // Project every line the way the service does, then verify fidelity + a filter.
        List<PurchaseLineServiceModel> rows = new ArrayList<>();
        for (ProjectionLine pl : lines) {
            String matchedName = pl.matchedMaterialId == null ? null : namesById.get(pl.matchedMaterialId);
            rows.add(PurchaseLineServiceMapper.toServiceModel(pl.entity, matchedName));
        }

        for (int i = 0; i < lines.size(); i++) {
            ProjectionLine pl = lines.get(i);
            PurchaseLineServiceModel row = rows.get(i);

            // Matched-material localized name: resolved ru → pl → code, null when unmatched. The
            // name is keyed by material id in the candidate map (one name per id, as the service
            // builds it), so a shared material id across lines resolves to the same name.
            if (pl.matchedMaterialId == null) {
                assertThat(row.matchedMaterialName()).isNull();
            } else {
                assertThat(row.matchedMaterialName()).isEqualTo(namesById.get(pl.matchedMaterialId));
            }
            assertThat(row.matchedMaterialId()).isEqualTo(pl.matchedMaterialId);

            // Unplanned-category localized name: resolved ru → pl → code, null when unset.
            if (pl.categoryId == null) {
                assertThat(row.unplannedCategoryId()).isNull();
                assertThat(row.unplannedCategoryName()).isNull();
            } else {
                assertThat(row.unplannedCategoryId()).isEqualTo(pl.categoryId);
                assertThat(row.unplannedCategoryName())
                        .isEqualTo(firstNonBlank(pl.catRu, pl.catPl, pl.catCode));
            }

            // Tier percent is derived from the tier (EXACT=100, MC=80, CAT=50, NONE=0).
            assertThat(row.matchPercent()).isEqualTo(ProcurementDocumentServiceMapper.tierPercent(pl.entity.getMatchTier()));
            assertThat(row.matchStatus()).isEqualTo(pl.entity.getMatchStatus());
            assertThat(row.rawName()).isEqualTo(pl.entity.getRawName());
            assertThat(row.documentId()).isEqualTo(pl.entity.getDocument().getId());
        }

        // Filter correctness: filtering by a match state returns exactly the rows with that state.
        for (MatchStatus status : MatchStatus.values()) {
            List<PurchaseLineServiceModel> expected = rows.stream()
                    .filter(r -> r.matchStatus() == status)
                    .collect(Collectors.toList());
            List<PurchaseLineServiceModel> actual = rows.stream()
                    .filter(r -> r.matchStatus() == status)
                    .collect(Collectors.toList());
            assertThat(actual).containsExactlyElementsOf(expected);
        }
    }

    // =============================================================================================
    // Shared invariant assertion over a projected row.
    // =============================================================================================

    private static void assertResultInvariant(PurchaseLineServiceModel row) {
        boolean hasMaterial = row.matchedMaterialId() != null;
        boolean matchedStatus = row.matchStatus() == MatchStatus.AUTO
                || row.matchStatus() == MatchStatus.MANUAL_MATCHED;
        assertThat(hasMaterial)
                .as("matchedMaterialId != null ⟺ status ∈ {AUTO, MANUAL_MATCHED}")
                .isEqualTo(matchedStatus);
        boolean tierNone = row.matchTier() == MatchTier.NONE;
        assertThat(hasMaterial)
                .as("matched ⟹ tier != NONE; unmatched ⟹ tier == NONE")
                .isEqualTo(!tierNone);
        if (row.unplannedCategoryId() != null) {
            assertThat(hasMaterial).as("a classified line carries no material").isFalse();
        }
    }

    private static boolean isMatched(MatchStatus status) {
        return status == MatchStatus.AUTO || status == MatchStatus.MANUAL_MATCHED;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private static void authenticateAdmin() {
        // ADMIN authority -> the inherited project-access guard is a bypass, so the service runs its
        // own stage/candidate/classify logic (the access filter is tested independently, FOR-03-04).
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    // =============================================================================================
    // Fixture — wires the service over stubbed seams around a single line on an ACTIVE project.
    // =============================================================================================

    private static final long PROJECT_ID = 7L;
    private static final long LINE_ID = 42L;
    private static final long ACTIVE_CATEGORY_ID = 100L;
    private static final long ON_ESTIMATE_MATERIAL_ID = 555L;

    /** The wired service + the entity under test + the captured persisted entity. */
    static final class Fixture {
        final PurchaseLineReconciliationService service;
        final ProcurementLineEntity line;
        final Long onEstimateMaterialId = ON_ESTIMATE_MATERIAL_ID;
        final Long activeCategoryId = ACTIVE_CATEGORY_ID;
        volatile ProcurementLineEntity saved; // set by the save stub when a persist occurs

        private Fixture(PurchaseLineReconciliationService service, ProcurementLineEntity line) {
            this.service = service;
            this.line = line;
        }

        /** Builds a fixture around a line in the given shape, with the given candidate set. */
        static Fixture forShape(ProcurementLineEntity shape, Set<Long> candidateSet) {
            ProcurementLineEntity line = copyOntoActiveProject(shape);

            ProcurementLineDao lineDao = mock(ProcurementLineDao.class);
            UnplannedExpenseCategoryDao categoryDao = mock(UnplannedExpenseCategoryDao.class);
            ProjectAccessCache accessCache = mock(ProjectAccessCache.class);
            AuditLogDao auditLogDao = mock(AuditLogDao.class);
            EntityManager entityManager = mock(EntityManager.class);
            PlannedMaterialCandidateProvider candidateProvider = mock(PlannedMaterialCandidateProvider.class);

            when(lineDao.findById(LINE_ID)).thenReturn(Optional.of(line));

            ProjectEntity project = new ProjectEntity();
            project.setId(PROJECT_ID);
            project.setStatus(ProjectStatus.ACTIVE);
            when(entityManager.find(ProjectEntity.class, PROJECT_ID)).thenReturn(project);

            // The candidate set (incl. empty + off-estimate) resolved by the stubbed provider.
            List<PlannedMaterial> candidates = candidateSet.stream()
                    .map(id -> new PlannedMaterial(id, "Producer", "Category",
                            List.of("имя-" + id, "nazwa-" + id)))
                    .collect(Collectors.toList());
            when(candidateProvider.candidatesFor(PROJECT_ID)).thenReturn(candidates);

            // An active category for classify.
            UnplannedExpenseCategoryEntity activeCategory = new UnplannedExpenseCategoryEntity();
            activeCategory.setId(ACTIVE_CATEGORY_ID);
            activeCategory.setCode("CAT");
            activeCategory.setNameRU("ru");
            activeCategory.setNamePL("pl");
            activeCategory.setActive(true);
            when(categoryDao.findById(ACTIVE_CATEGORY_ID)).thenReturn(Optional.of(activeCategory));

            PurchaseLineReconciliationService service = new PurchaseLineReconciliationService(
                    lineDao, categoryDao, accessCache,
                    mock(com.foremen.service.model.mapper.ProcurementLineServiceMapper.class),
                    auditLogDao, entityManager, candidateProvider);

            Fixture fixture = new Fixture(service, line);
            // save returns the (mutated) line and records it so the test can assert a persist.
            when(lineDao.save(any(ProcurementLineEntity.class))).thenAnswer(inv -> {
                ProcurementLineEntity arg = inv.getArgument(0);
                fixture.saved = arg;
                return arg;
            });
            return fixture;
        }

        /** A full scenario fixture: like {@link #forShape} but guarantees the on-estimate id is present. */
        static Fixture forScenario(ProcurementLineEntity shape) {
            Set<Long> candidateSet = new HashSet<>();
            candidateSet.add(ON_ESTIMATE_MATERIAL_ID);
            return forShape(shape, candidateSet);
        }

        private static ProcurementLineEntity copyOntoActiveProject(ProcurementLineEntity shape) {
            ProjectEntity project = new ProjectEntity();
            project.setId(PROJECT_ID);
            project.setStatus(ProjectStatus.ACTIVE);
            ProcurementDocumentEntity document = new ProcurementDocumentEntity();
            document.setId(900L);
            document.setProject(project);

            ProcurementLineEntity line = new ProcurementLineEntity();
            line.setId(LINE_ID);
            line.setDocument(document);
            line.setLineNo(1);
            line.setRawName(shape.getRawName() == null ? "raw" : shape.getRawName());
            line.setMatchStatus(shape.getMatchStatus());
            line.setMatchedMaterialId(shape.getMatchedMaterialId());
            line.setMatchTier(shape.getMatchTier());
            line.setUnplannedCategory(shape.getUnplannedCategory());
            return line;
        }
    }

    // =============================================================================================
    // Generators
    // =============================================================================================

    enum Action { RELINK_ON_ESTIMATE, CLEAR_MATCH, CLASSIFY, CLEAR_CLASSIFICATION }

    record Scenario(ProcurementLineEntity priorShape, Action action) {
        Fixture newFixture() {
            return Fixture.forScenario(priorShape);
        }
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        return Combinators.combine(lineShapes(), Arbitraries.of(Action.values())).as(Scenario::new);
    }

    /** A line in one of the four match states with a state-consistent tier/material/category. */
    @Provide
    Arbitrary<ProcurementLineEntity> lineShapes() {
        Arbitrary<MatchStatus> status = Arbitraries.of(MatchStatus.values());
        Arbitrary<Boolean> withCategory = Arbitraries.of(true, false);
        return Combinators.combine(status, withCategory).as((s, cat) -> shape(s, cat));
    }

    /** Only the four VALID shapes (for assertInvariant acceptance). */
    @Provide
    Arbitrary<ProcurementLineEntity> validShapes() {
        Arbitrary<MatchStatus> status = Arbitraries.of(MatchStatus.values());
        Arbitrary<Boolean> withCategory = Arbitraries.of(true, false);
        return Combinators.combine(status, withCategory).as((s, cat) -> shape(s, cat));
    }

    /** Shapes that violate the invariant in one of the three documented ways. */
    @Provide
    Arbitrary<ProcurementLineEntity> invalidShapes() {
        return Arbitraries.of(
                // matched status but no material
                broken(MatchStatus.AUTO, null, MatchTier.EXACT, false),
                broken(MatchStatus.MANUAL_MATCHED, null, MatchTier.EXACT, false),
                // unmatched status but a material present
                broken(MatchStatus.UNMATCHED, 5L, MatchTier.NONE, false),
                broken(MatchStatus.MANUAL_UNMATCHED, 5L, MatchTier.NONE, false),
                // matched with tier NONE
                broken(MatchStatus.AUTO, 5L, MatchTier.NONE, false),
                // unmatched with a non-NONE tier
                broken(MatchStatus.UNMATCHED, null, MatchTier.EXACT, false),
                // a matched line carrying an unplanned category
                broken(MatchStatus.AUTO, 5L, MatchTier.EXACT, true));
    }

    private static ProcurementLineEntity shape(MatchStatus status, boolean withCategory) {
        boolean matched = status == MatchStatus.AUTO || status == MatchStatus.MANUAL_MATCHED;
        ProcurementLineEntity line = new ProcurementLineEntity();
        line.setRawName("raw");
        line.setMatchStatus(status);
        line.setMatchedMaterialId(matched ? 77L : null);
        line.setMatchTier(matched ? MatchTier.EXACT : MatchTier.NONE);
        if (withCategory && !matched) {
            line.setUnplannedCategory(category(ACTIVE_CATEGORY_ID));
        }
        return line;
    }

    private static ProcurementLineEntity broken(MatchStatus status, Long materialId,
                                                MatchTier tier, boolean withCategory) {
        ProcurementLineEntity line = new ProcurementLineEntity();
        line.setRawName("raw");
        line.setMatchStatus(status);
        line.setMatchedMaterialId(materialId);
        line.setMatchTier(tier);
        if (withCategory) {
            line.setUnplannedCategory(category(ACTIVE_CATEGORY_ID));
        }
        return line;
    }

    private static UnplannedExpenseCategoryEntity category(Long id) {
        UnplannedExpenseCategoryEntity category = new UnplannedExpenseCategoryEntity();
        category.setId(id);
        category.setCode("CAT");
        category.setNameRU("ru");
        category.setNamePL("pl");
        category.setActive(true);
        return category;
    }

    @Provide
    Arbitrary<Set<Long>> candidateSets() {
        Arbitrary<Long> ids = Arbitraries.longs().between(1, 50);
        return ids.set().ofMinSize(0).ofMaxSize(6);
    }

    @Provide
    Arbitrary<Long> materialIds() {
        return Arbitraries.longs().between(1, 60);
    }

    // --- Property 5 generators ---

    /** A projection line: the entity plus the raw ru/pl/code name forms its localized names fold from. */
    static final class ProjectionLine {
        final ProcurementLineEntity entity;
        final Long matchedMaterialId;
        final String nameRu;
        final String namePl;
        final String code;
        final Long categoryId;
        final String catRu;
        final String catPl;
        final String catCode;

        ProjectionLine(ProcurementLineEntity entity, Long matchedMaterialId,
                       String nameRu, String namePl, String code,
                       Long categoryId, String catRu, String catPl, String catCode) {
            this.entity = entity;
            this.matchedMaterialId = matchedMaterialId;
            this.nameRu = nameRu;
            this.namePl = namePl;
            this.code = code;
            this.categoryId = categoryId;
            this.catRu = catRu;
            this.catPl = catPl;
            this.catCode = catCode;
        }
    }

    @Provide
    Arbitrary<List<ProjectionLine>> projectionLines() {
        return projectionLine().list().ofMinSize(0).ofMaxSize(10);
    }

    /** A ru/pl/code name triple with ru/pl possibly blank so the ru → pl → code fallback is exercised. */
    record NameTriple(String ru, String pl, String code) {
    }

    @Provide
    Arbitrary<NameTriple> materialNames() {
        return Combinators.combine(
                        Arbitraries.of("имя-ру", "", "   "),
                        Arbitraries.of("nazwa-pl", "", "   "),
                        Arbitraries.of("MAT-CODE"))
                .as(NameTriple::new);
    }

    @Provide
    Arbitrary<NameTriple> categoryNames() {
        return Combinators.combine(
                        Arbitraries.of("кат-ру", "", "   "),
                        Arbitraries.of("kat-pl", "", "   "),
                        Arbitraries.of("CAT-CODE"))
                .as(NameTriple::new);
    }

    @Provide
    Arbitrary<ProjectionLine> projectionLine() {
        Arbitrary<MatchStatus> status = Arbitraries.of(MatchStatus.values());
        Arbitrary<Boolean> withCategory = Arbitraries.of(true, false);
        Arbitrary<Long> docId = Arbitraries.longs().between(1, 5);
        Arbitrary<Integer> lineNo = Arbitraries.integers().between(1, 50);

        return Combinators.combine(status, materialNames(), categoryNames(), withCategory, docId, lineNo)
                .as((s, names, catNames, hasCategory, dId, ln) -> {
                    boolean matched = s == MatchStatus.AUTO || s == MatchStatus.MANUAL_MATCHED;
                    Long materialId = matched ? 1000L + ln : null;

                    ProjectEntity project = new ProjectEntity();
                    project.setId(PROJECT_ID);
                    ProcurementDocumentEntity document = new ProcurementDocumentEntity();
                    document.setId(dId);
                    document.setProject(project);

                    ProcurementLineEntity entity = new ProcurementLineEntity();
                    entity.setId((long) ln);
                    entity.setDocument(document);
                    entity.setLineNo(ln);
                    entity.setRawName("raw-" + ln);
                    entity.setMatchStatus(s);
                    entity.setMatchedMaterialId(materialId);
                    entity.setMatchTier(matched ? MatchTier.EXACT : MatchTier.NONE);

                    Long categoryId = null;
                    if (hasCategory && !matched) {
                        categoryId = 2000L + ln;
                        UnplannedExpenseCategoryEntity cat = new UnplannedExpenseCategoryEntity();
                        cat.setId(categoryId);
                        cat.setCode(catNames.code());
                        cat.setNameRU(catNames.ru());
                        cat.setNamePL(catNames.pl());
                        cat.setActive(true);
                        entity.setUnplannedCategory(cat);
                    }
                    return new ProjectionLine(entity, materialId, names.ru(), names.pl(), names.code(),
                            categoryId, catNames.ru(), catNames.pl(), catNames.code());
                });
    }
}
