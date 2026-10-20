package com.foremen.service.execution.amendment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.controller.model.AddLineRequest;
import com.foremen.dao.EstimateAmendmentDao;
import com.foremen.dao.EstimateDao;
import com.foremen.dao.EstimateLineDao;
import com.foremen.dao.model.AmendmentLineEntity;
import com.foremen.dao.model.AmendmentStatus;
import com.foremen.dao.model.ChangeType;
import com.foremen.dao.model.EstimateAmendmentEntity;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.model.AmendmentServiceExtendedModel;
import com.foremen.service.model.mapper.AmendmentServiceMapper;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-08 task 5.2 — plain Mockito unit tests for {@link AmendmentService}, the project-scoped
 * amendment (aneks) authoring service. The DAOs, the {@link AmendmentServiceMapper}, the
 * {@link ProjectAccessCache}, the {@link com.foremen.service.audit.AuditLogDao} and the
 * {@link EntityManager} are mocked; the pure, persistence-free {@link AmendmentMergeResolver} is wired
 * as a REAL instance (it does I/O-free arithmetic, so stubbing it would only hide the merge behavior
 * under test). Conventions mirror {@code WorkReportServiceTest}: {@code save()} echoes its argument so
 * the committed aggregate is the one asserted on, {@code getReference(type, id)} returns a
 * lightweight id-only stub, and {@link ForemenApiException} is asserted via
 * {@link ForemenApiException#getStatus()} / {@link ForemenApiException#getMessageCode()}.
 *
 * <p>The by-id delete paths run the inherited {@code ProjectScopedService.assertProjectAccess}; the
 * caller is authenticated as ADMIN through {@link SecurityContextHolder} so the project-scope gate is
 * bypassed (mirrors {@code WorkReportServiceTest}) and the service's own OPEN / not-found guards are
 * what the assertions exercise.
 *
 * <p>Covers:
 * <ul>
 *   <li>the single-open-amendment invariant — a second create is rejected 409
 *       {@code error.amendment.openExists} (R2.1/R2.3);</li>
 *   <li>the eligibility guard — a create against a non-ACTIVE project or a non-SIGNED estimate is
 *       rejected 400 {@code error.amendment.notEligible} (R2.5);</li>
 *   <li>{@code addLine} per {@link ChangeType} happy path (SCHEDULE_CHANGE / ADDITIONAL_WORK /
 *       VOLUME_CHANGE-with-baseline) appends a line (R3);</li>
 *   <li>a same-subject add with no resolution → 409 {@code error.amendment.subjectConflict} (R5.6);</li>
 *   <li>a same-subject add WITH a resolution merges in place (one surviving line, R5.4);</li>
 *   <li>delete line / delete amendment (R6.1/R6.2);</li>
 *   <li>the OPEN guard — a line mutation on a non-OPEN amendment → 409
 *       {@code error.amendment.notOpen} (R2.4/R6.4).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AmendmentServiceTest {

    private static final Long PROJECT_ID = 42L;
    private static final Long AMENDMENT_ID = 900L;
    private static final Long WORK_ITEM_ID = 7L;
    private static final Long ROOM_ID = 100L;
    private static final Long CATEGORY_ID = 3L;
    private static final Long ADMIN_ID = 1L;

    @Mock
    private EstimateAmendmentDao amendmentDao;
    @Mock
    private EstimateDao estimateDao;
    @Mock
    private EstimateLineDao estimateLineDao;
    @Mock
    private ProjectAccessCache projectAccessCache;
    @Mock
    private AmendmentServiceMapper serviceMapper;
    @Mock
    private com.foremen.service.audit.AuditLogDao auditLogDao;
    @Mock
    private EntityManager entityManager;

    // The merge core is persistence-free — exercise it for real.
    private final AmendmentMergeResolver mergeResolver = new AmendmentMergeResolver();

    private AmendmentService service;

    @BeforeEach
    void setUp() {
        service = new AmendmentService(
                amendmentDao, estimateDao, estimateLineDao, projectAccessCache, mergeResolver,
                serviceMapper, auditLogDao, entityManager);
        // save() echoes its argument so the committed aggregate is the one under test.
        lenient().when(amendmentDao.save(any(EstimateAmendmentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        // getReference(type, id) returns a lightweight stub carrying only its id.
        lenient().when(entityManager.getReference(any(), any()))
                .thenAnswer(inv -> referenceStub(inv.getArgument(0), (Long) inv.getArgument(1)));
        // The read mapping is not under test; echo a trivial model so the write paths return non-null.
        lenient().when(serviceMapper.toServiceExtendedModel(any()))
                .thenReturn(new AmendmentServiceExtendedModel());
        // No prior amendment by default; seqNo starts at 1.
        lenient().when(amendmentDao.findByProjectIdAndStatus(PROJECT_ID, AmendmentStatus.OPEN))
                .thenReturn(Optional.empty());
        lenient().when(amendmentDao.findMaxSeqNoByProjectId(PROJECT_ID)).thenReturn(null);
        // The VOLUME_CHANGE baseline resolution reads the signed estimate; default to no estimate line.
        lenient().when(estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(PROJECT_ID))
                .thenReturn(List.of());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // =======================================================================
    // Create: eligibility guard (R2.5) + single-open invariant (R2.1/R2.3)
    // =======================================================================

    @Nested
    @DisplayName("create — eligibility (R2.5) + single-open invariant (R2.1/R2.3)")
    class CreateGuards {

        @Test
        @DisplayName("create on an ACTIVE project with a SIGNED estimate inserts an OPEN amendment with seqNo 1")
        void createHappyPath() {
            stubEligible();

            service.create(PROJECT_ID, "first aneks");

            EstimateAmendmentEntity saved = capturedSave();
            assertThat(saved.getStatus()).isEqualTo(AmendmentStatus.OPEN);
            assertThat(saved.getSeqNo()).isEqualTo(1);
            assertThat(saved.getNote()).isEqualTo("first aneks");
            assertThat(saved.getProject()).isNotNull();
            assertThat(saved.getProject().getId()).isEqualTo(PROJECT_ID);
        }

        @Test
        @DisplayName("a second create while an OPEN amendment exists is rejected 409 error.amendment.openExists")
        void secondCreateRejected() {
            stubEligible();
            when(amendmentDao.findByProjectIdAndStatus(PROJECT_ID, AmendmentStatus.OPEN))
                    .thenReturn(Optional.of(openAmendment()));

            assertApiError(() -> service.create(PROJECT_ID, "second"),
                    HttpStatus.CONFLICT, "error.amendment.openExists");
            verify(amendmentDao, never()).save(any());
        }

        @Test
        @DisplayName("create on a non-ACTIVE project is rejected 400 error.amendment.notEligible")
        void createRejectedWhenProjectNotActive() {
            ProjectEntity project = new ProjectEntity();
            project.setId(PROJECT_ID);
            project.setStatus(ProjectStatus.DRAFT);
            when(entityManager.find(ProjectEntity.class, PROJECT_ID)).thenReturn(project);
            // The estimate state is irrelevant once the project fails the ACTIVE check.

            assertApiError(() -> service.create(PROJECT_ID, "x"),
                    HttpStatus.BAD_REQUEST, "error.amendment.notEligible");
            verify(amendmentDao, never()).save(any());
        }

        @Test
        @DisplayName("create when the estimate is not SIGNED is rejected 400 error.amendment.notEligible")
        void createRejectedWhenEstimateNotSigned() {
            ProjectEntity project = new ProjectEntity();
            project.setId(PROJECT_ID);
            project.setStatus(ProjectStatus.ACTIVE);
            when(entityManager.find(ProjectEntity.class, PROJECT_ID)).thenReturn(project);
            EstimateEntity estimate = new EstimateEntity();
            estimate.setStatus(EstimateStatus.APPROVED); // not SIGNED
            when(estimateDao.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(estimate));

            assertApiError(() -> service.create(PROJECT_ID, "x"),
                    HttpStatus.BAD_REQUEST, "error.amendment.notEligible");
            verify(amendmentDao, never()).save(any());
        }
    }

    // =======================================================================
    // addLine per ChangeType happy path (R3)
    // =======================================================================

    @Nested
    @DisplayName("addLine — per-type happy path appends a line (R3)")
    class AddLinePerType {

        @Test
        @DisplayName("SCHEDULE_CHANGE appends a signed half-day line")
        void addScheduleChange() {
            stubEligible();
            EstimateAmendmentEntity open = openAmendment();
            when(amendmentDao.findByProjectIdAndStatus(PROJECT_ID, AmendmentStatus.OPEN))
                    .thenReturn(Optional.of(open));

            service.addLine(PROJECT_ID, scheduleChange(4, CATEGORY_ID));

            AmendmentLineEntity line = singleLine(capturedSave());
            assertThat(line.getChangeType()).isEqualTo(ChangeType.SCHEDULE_CHANGE);
            assertThat(line.getScheduleDeltaHalfDays()).isEqualTo(4);
            assertThat(line.getWorkCategory()).isNotNull();
            assertThat(line.getWorkCategory().getId()).isEqualTo(CATEGORY_ID);
        }

        @Test
        @DisplayName("ADDITIONAL_WORK appends a work-item/room/qty/price line")
        void addAdditionalWork() {
            stubEligible();
            EstimateAmendmentEntity open = openAmendment();
            when(amendmentDao.findByProjectIdAndStatus(PROJECT_ID, AmendmentStatus.OPEN))
                    .thenReturn(Optional.of(open));

            service.addLine(PROJECT_ID,
                    additionalWork(WORK_ITEM_ID, ROOM_ID, "5.000", "20.00"));

            AmendmentLineEntity line = singleLine(capturedSave());
            assertThat(line.getChangeType()).isEqualTo(ChangeType.ADDITIONAL_WORK);
            assertThat(line.getWorkItem().getId()).isEqualTo(WORK_ITEM_ID);
            assertThat(line.getRoom().getId()).isEqualTo(ROOM_ID);
            assertThat(line.getDeltaQuantity()).isEqualByComparingTo("5.000");
            assertThat(line.getExtraUnitPrice()).isEqualByComparingTo("20.00");
        }

        @Test
        @DisplayName("VOLUME_CHANGE with a signed-estimate baseline appends a signed-delta line")
        void addVolumeChangeWithBaseline() {
            stubEligible();
            EstimateAmendmentEntity open = openAmendment();
            when(amendmentDao.findByProjectIdAndStatus(PROJECT_ID, AmendmentStatus.OPEN))
                    .thenReturn(Optional.of(open));
            // Baseline quantity 10 for (workItem, room); a -3 delta ⇒ resulting volume 7 (≥ 0).
            when(estimateLineDao.findByProjectIdWithWorkItemCategoryAndUnit(PROJECT_ID))
                    .thenReturn(List.of(estimateLineWithRoomQty(WORK_ITEM_ID, ROOM_ID, "10")));

            service.addLine(PROJECT_ID,
                    volumeChange(WORK_ITEM_ID, ROOM_ID, "-3.000", "15.00"));

            AmendmentLineEntity line = singleLine(capturedSave());
            assertThat(line.getChangeType()).isEqualTo(ChangeType.VOLUME_CHANGE);
            assertThat(line.getWorkItem().getId()).isEqualTo(WORK_ITEM_ID);
            assertThat(line.getRoom().getId()).isEqualTo(ROOM_ID);
            assertThat(line.getDeltaQuantity()).isEqualByComparingTo("-3.000");
            assertThat(line.getExtraUnitPrice()).isEqualByComparingTo("15.00");
        }
    }

    // =======================================================================
    // Merge: subject conflict (R5.6) + apply-in-place (R5.4)
    // =======================================================================

    @Nested
    @DisplayName("addLine — same-subject merge (R5)")
    class Merge {

        @Test
        @DisplayName("a same-subject add with no resolution is rejected 409 error.amendment.subjectConflict")
        void subjectConflictWithoutResolution() {
            stubEligible();
            // An amendment that already carries an ADDITIONAL_WORK line for (workItem, room).
            EstimateAmendmentEntity open = openAmendment();
            open.getLines().add(costLine(555L, ChangeType.ADDITIONAL_WORK,
                    WORK_ITEM_ID, ROOM_ID, "5.000", "20.00"));
            when(amendmentDao.findByProjectIdAndStatus(PROJECT_ID, AmendmentStatus.OPEN))
                    .thenReturn(Optional.of(open));

            // Same (workItem, room) subject, no mergeResolution.
            assertApiError(() -> service.addLine(PROJECT_ID,
                            additionalWork(WORK_ITEM_ID, ROOM_ID, "2.000", "30.00")),
                    HttpStatus.CONFLICT, "error.amendment.subjectConflict");
            // No surviving-line change persisted.
            assertThat(open.getLines()).hasSize(1);
        }

        @Test
        @DisplayName("a same-subject add WITH ADD_TO merges in place — one surviving line with summed delta + new price")
        void mergeAppliesInPlace() {
            stubEligible();
            EstimateAmendmentEntity open = openAmendment();
            open.getLines().add(costLine(555L, ChangeType.ADDITIONAL_WORK,
                    WORK_ITEM_ID, ROOM_ID, "5.000", "20.00"));
            when(amendmentDao.findByProjectIdAndStatus(PROJECT_ID, AmendmentStatus.OPEN))
                    .thenReturn(Optional.of(open));

            service.addLine(PROJECT_ID,
                    additionalWorkWithResolution(WORK_ITEM_ID, ROOM_ID, "2.000", "30.00",
                            MergeResolution.ADD_TO));

            EstimateAmendmentEntity saved = capturedSave();
            // Exactly one surviving line (merged in place, R5.4).
            assertThat(saved.getLines()).hasSize(1);
            AmendmentLineEntity survivor = saved.getLines().get(0);
            // ADD_TO sums the quantity (5 + 2 = 7) and takes the new price (30.00), R5.3.
            assertThat(survivor.getDeltaQuantity()).isEqualByComparingTo("7.000");
            assertThat(survivor.getExtraUnitPrice()).isEqualByComparingTo("30.00");
        }
    }

    // =======================================================================
    // Delete line / amendment (R6.1/R6.2) + OPEN guard (R2.4/R6.4)
    // =======================================================================

    @Nested
    @DisplayName("delete + OPEN guard (R6.1/R6.2/R6.4)")
    class DeleteAndOpenGuard {

        @Test
        @DisplayName("deleteLine removes the line from an OPEN amendment and leaves the amendment OPEN (R6.1)")
        void deleteLineRemovesIt() {
            authenticateAdmin();
            EstimateAmendmentEntity open = openAmendment();
            AmendmentLineEntity line = costLine(555L, ChangeType.ADDITIONAL_WORK,
                    WORK_ITEM_ID, ROOM_ID, "5.000", "20.00");
            open.getLines().add(line);
            when(amendmentDao.findById(AMENDMENT_ID)).thenReturn(Optional.of(open));

            service.deleteLine(AMENDMENT_ID, 555L);

            EstimateAmendmentEntity saved = capturedSave();
            assertThat(saved.getLines()).isEmpty();
            assertThat(saved.getStatus()).isEqualTo(AmendmentStatus.OPEN);
        }

        @Test
        @DisplayName("deleteAmendment hard-deletes an OPEN amendment by id (R6.2)")
        void deleteAmendmentRemovesIt() {
            authenticateAdmin();
            EstimateAmendmentEntity open = openAmendment();
            when(amendmentDao.findById(AMENDMENT_ID)).thenReturn(Optional.of(open));

            service.deleteAmendment(AMENDMENT_ID);

            verify(amendmentDao).deleteById(AMENDMENT_ID);
        }

        @Test
        @DisplayName("deleteLine on a non-OPEN amendment is rejected 409 error.amendment.notOpen (R6.4)")
        void deleteLineOnNonOpenRejected() {
            authenticateAdmin();
            EstimateAmendmentEntity signed = openAmendment();
            signed.setStatus(AmendmentStatus.SIGNED);
            signed.getLines().add(costLine(555L, ChangeType.ADDITIONAL_WORK,
                    WORK_ITEM_ID, ROOM_ID, "5.000", "20.00"));
            when(amendmentDao.findById(AMENDMENT_ID)).thenReturn(Optional.of(signed));

            assertApiError(() -> service.deleteLine(AMENDMENT_ID, 555L),
                    HttpStatus.CONFLICT, "error.amendment.notOpen");
            verify(amendmentDao, never()).save(any());
        }

        @Test
        @DisplayName("deleteAmendment on a non-OPEN amendment is rejected 409 error.amendment.notOpen (R6.4)")
        void deleteAmendmentOnNonOpenRejected() {
            authenticateAdmin();
            EstimateAmendmentEntity signed = openAmendment();
            signed.setStatus(AmendmentStatus.SIGNED);
            when(amendmentDao.findById(AMENDMENT_ID)).thenReturn(Optional.of(signed));

            assertApiError(() -> service.deleteAmendment(AMENDMENT_ID),
                    HttpStatus.CONFLICT, "error.amendment.notOpen");
            verify(amendmentDao, never()).deleteById(any());
        }
    }

    // =======================================================================
    // Helpers
    // =======================================================================

    /** Authenticates an ADMIN caller so the inherited project-scope gate is bypassed on by-id paths. */
    private void authenticateAdmin() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                String.valueOf(ADMIN_ID), "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** Stubs the eligibility guard to pass: ACTIVE project with a SIGNED estimate. */
    private void stubEligible() {
        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT_ID);
        project.setStatus(ProjectStatus.ACTIVE);
        lenient().when(entityManager.find(ProjectEntity.class, PROJECT_ID)).thenReturn(project);
        EstimateEntity estimate = new EstimateEntity();
        estimate.setStatus(EstimateStatus.SIGNED);
        lenient().when(estimateDao.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(estimate));
    }

    private EstimateAmendmentEntity openAmendment() {
        EstimateAmendmentEntity amendment = new EstimateAmendmentEntity();
        amendment.setId(AMENDMENT_ID);
        amendment.setStatus(AmendmentStatus.OPEN);
        amendment.setSeqNo(1);
        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT_ID);
        amendment.setProject(project);
        return amendment;
    }

    private static AddLineRequest scheduleChange(Integer halfDays, Long categoryId) {
        return new AddLineRequest(ChangeType.SCHEDULE_CHANGE, null, null, categoryId,
                null, null, halfDays, null, null);
    }

    private static AddLineRequest additionalWork(Long workItemId, Long roomId,
                                                 String qty, String price) {
        return new AddLineRequest(ChangeType.ADDITIONAL_WORK, workItemId, roomId, null,
                new BigDecimal(qty), new BigDecimal(price), null, null, null);
    }

    private static AddLineRequest additionalWorkWithResolution(Long workItemId, Long roomId,
                                                               String qty, String price,
                                                               MergeResolution resolution) {
        return new AddLineRequest(ChangeType.ADDITIONAL_WORK, workItemId, roomId, null,
                new BigDecimal(qty), new BigDecimal(price), null, null, resolution);
    }

    private static AddLineRequest volumeChange(Long workItemId, Long roomId,
                                               String qty, String price) {
        return new AddLineRequest(ChangeType.VOLUME_CHANGE, workItemId, roomId, null,
                new BigDecimal(qty), new BigDecimal(price), null, null, null);
    }

    /** A persisted cost line ({@code ADDITIONAL_WORK}/{@code VOLUME_CHANGE}) already on an amendment. */
    private static AmendmentLineEntity costLine(Long id, ChangeType type, Long workItemId,
                                                Long roomId, String qty, String price) {
        AmendmentLineEntity line = new AmendmentLineEntity();
        line.setId(id);
        line.setChangeType(type);
        line.setWorkItem(referenceStub(WorkItemEntity.class, workItemId));
        line.setRoom(referenceStub(RoomEntity.class, roomId));
        line.setDeltaQuantity(new BigDecimal(qty));
        line.setExtraUnitPrice(new BigDecimal(price));
        return line;
    }

    /** A signed-estimate line carrying a per-room quantity for the VOLUME_CHANGE baseline resolution. */
    private static com.foremen.dao.model.EstimateLineEntity estimateLineWithRoomQty(
            Long workItemId, Long roomId, String qty) {
        com.foremen.dao.model.EstimateLineEntity line = new com.foremen.dao.model.EstimateLineEntity();
        line.setWorkItem(referenceStub(WorkItemEntity.class, workItemId));
        com.foremen.dao.model.EstimateLineRoomQtyEntity rq =
                new com.foremen.dao.model.EstimateLineRoomQtyEntity();
        rq.setRoom(referenceStub(RoomEntity.class, roomId));
        rq.setQuantity(new BigDecimal(qty));
        line.setRoomQtys(new java.util.ArrayList<>(List.of(rq)));
        return line;
    }

    /** A lightweight id-only entity stub for {@code getReference(type, id)}. */
    @SuppressWarnings("unchecked")
    private static <T> T referenceStub(Class<T> type, Long id) {
        if (id == null) {
            return null;
        }
        if (type == ProjectEntity.class) {
            ProjectEntity e = new ProjectEntity();
            e.setId(id);
            return (T) e;
        }
        if (type == WorkItemEntity.class) {
            WorkItemEntity e = new WorkItemEntity();
            e.setId(id);
            return (T) e;
        }
        if (type == RoomEntity.class) {
            RoomEntity e = new RoomEntity();
            e.setId(id);
            return (T) e;
        }
        if (type == WorkCategoryEntity.class) {
            WorkCategoryEntity e = new WorkCategoryEntity();
            e.setId(id);
            return (T) e;
        }
        throw new IllegalArgumentException("unexpected reference type: " + type);
    }

    private EstimateAmendmentEntity capturedSave() {
        org.mockito.ArgumentCaptor<EstimateAmendmentEntity> captor =
                org.mockito.ArgumentCaptor.forClass(EstimateAmendmentEntity.class);
        verify(amendmentDao, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private static AmendmentLineEntity singleLine(EstimateAmendmentEntity amendment) {
        assertThat(amendment.getLines()).hasSize(1);
        return amendment.getLines().get(0);
    }

    private static void assertApiError(ThrowingCallable callable,
                                       HttpStatusCode expectedStatus, String expectedCode) {
        assertThatThrownBy(callable)
                .isInstanceOf(ForemenApiException.class)
                .satisfies(t -> {
                    ForemenApiException ex = (ForemenApiException) t;
                    assertThat(ex.getStatus()).isEqualTo(expectedStatus);
                    assertThat(ex.getMessageCode()).isEqualTo(expectedCode);
                });
    }
}
