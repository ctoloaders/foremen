package com.foremen.service;

import com.foremen.dao.AdminDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.model.MeasureSource;
import com.foremen.dao.model.OpeningType;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomGeometry;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.RoomMetrics;
import com.foremen.service.model.RoomServiceExtendedModel;
import com.foremen.service.model.RoomServiceModel;
import com.foremen.service.model.mapper.RoomServiceMapper;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Project-scoped CRUD service for {@link RoomEntity} (FOR-04-14).
 *
 * <p>Following the FOR-03-04a single-contract shape, it implements exactly one CRUD contract —
 * {@link ProjectScopedService} — and never a plain {@link AdminService} and never both. Because
 * {@link ProjectScopedService} extends {@link AdminService}, this service supplies the standard
 * CRUD plumbing ({@link #getDao()}, {@link #getMapper()}, {@link #getEntityManager()},
 * {@link #getDaoModelClass()}, {@link #getAuditLogDao()}), the single mandatory per-entity override
 * {@link #getProjectIdPath()}, and wires {@link #allowedProjectIds(Long)} to
 * {@link ProjectAccessCache#get(Long)}.
 *
 * <p><b>Project-scoped child.</b> A {@code Room} belongs to exactly one {@code Project} through its
 * {@code @ManyToOne project} association, so {@link #getProjectIdPath()} returns the dotted
 * association path {@code "project.id"} (Requirements 5.2, 5.4). The inherited list filter therefore
 * restricts non-ADMIN LIST reads to rooms whose owning project the caller is a member of, and by-id
 * reads/writes assert that membership; ADMIN bypasses both (Requirement 5.3).
 *
 * <p>This service overrides no CRUD method and does not override {@code addRequiredQuery()} or
 * {@code getProjectId()} — the list filter and the default project-id resolver are inherited from
 * {@link ProjectScopedService}.
 *
 * <p><b>Pre-persist normalization (task 4.4).</b> Both create and update run
 * {@link #normalize(RoomServiceExtendedModel)} through the {@link #validateCreate} /
 * {@link #validateUpdate} hooks, which fire in the generic {@link AdminService} create/update
 * transactions <em>before</em> the mapper turns the model into (or onto) the entity. Normalization:
 * <ol>
 *   <li>resolves the {@code project}/{@code roomType} references, rejecting a missing one with a
 *       field-identifying {@code 404 error.entity.not.found} (Requirements 2.6 refs, 5.6);</li>
 *   <li>validates the geometry when present (≥ 3 vertices; every opening has
 *       {@code type ∈ {DOOR, WINDOW}}, {@code count > 0}, {@code height > 0}, {@code width > 0}),
 *       rejecting with a {@code 400} geometry/opening code otherwise (Requirements 2.3, 2.4);</li>
 *   <li>when geometry is present, treats it as authoritative — runs the
 *       {@link RoomCalculationService}, overwrites the five metrics + counts + gaps with the
 *       computed values and stamps the five sources {@code CALCULATED} (Requirement 4.2);</li>
 *   <li>when geometry is absent, keeps the supplied manual metrics and stamps each one's source
 *       {@code MANUAL} (Requirement 4.1);</li>
 *   <li>validates numerics within {@code [0, 9999999999.99]} and counts non-negative, and rejects
 *       an invalid {@link MeasureSource} (Requirements 4.4, 4.5).</li>
 * </ol>
 * All rejections throw before persistence, so the enclosing transaction rolls back and nothing is
 * written.
 */
@Service
public class RoomService
        implements ProjectScopedService<RoomServiceModel, RoomServiceExtendedModel, RoomEntity, Long> {

    /** Message code for a missing {@code projectId}/{@code roomTypeId} reference (404). */
    static final String ENTITY_NOT_FOUND_MESSAGE = "error.entity.not.found";

    /** Message code for a polygon with fewer than 3 vertices (400). */
    static final String GEOMETRY_INVALID_MESSAGE = "error.room.geometry.invalid";

    /** Message code for an opening with a bad type/count/height/width (400). */
    static final String OPENING_INVALID_MESSAGE = "error.room.opening.invalid";

    /** Message code for a numeric metric outside {@code [0, 9999999999.99]} (400). */
    static final String METRIC_RANGE_MESSAGE = "error.room.metric.range";

    /** Message code for a negative count (400). */
    static final String COUNT_NEGATIVE_MESSAGE = "error.room.count.negative";

    /** Message code for an invalid {@link MeasureSource} value (400). */
    static final String SOURCE_INVALID_MESSAGE = "error.room.source.invalid";

    /** A valid polygon needs at least this many vertices (Requirement 2.3). */
    private static final int MIN_VERTICES = 3;

    /** Inclusive lower bound of a valid numeric metric (Requirement 4.5). */
    private static final BigDecimal METRIC_MIN = BigDecimal.ZERO;

    /** Inclusive upper bound of a valid numeric metric (Requirement 4.5). */
    private static final BigDecimal METRIC_MAX = new BigDecimal("9999999999.99");

    private final RoomDao roomDao;
    private final RoomServiceMapper roomServiceMapper;
    private final ProjectAccessCache projectAccessCache;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final ProjectDao projectDao;
    private final RoomTypeDao roomTypeDao;
    private final RoomCalculationService roomCalculationService;

    public RoomService(RoomDao roomDao,
                       RoomServiceMapper roomServiceMapper,
                       ProjectAccessCache projectAccessCache,
                       AuditLogDao auditLogDao,
                       EntityManager entityManager,
                       ProjectDao projectDao,
                       RoomTypeDao roomTypeDao,
                       RoomCalculationService roomCalculationService) {
        this.roomDao = roomDao;
        this.roomServiceMapper = roomServiceMapper;
        this.projectAccessCache = projectAccessCache;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.projectDao = projectDao;
        this.roomTypeDao = roomTypeDao;
        this.roomCalculationService = roomCalculationService;
    }

    // --- CRUD plumbing (inherited from AdminService via ProjectScopedService) ---

    @Override
    public AdminDao<RoomEntity, Long> getDao() {
        return roomDao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<RoomEntity, RoomServiceModel, RoomServiceExtendedModel> getMapper() {
        return roomServiceMapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<RoomEntity> getDaoModelClass() {
        return RoomEntity.class;
    }

    // --- ProjectScopedService overrides ---

    /**
     * The single mandatory per-entity override. A room resolves its project boundary through its
     * {@code @ManyToOne project} association, so the project-id path is the dotted association path
     * {@code "project.id"} (Requirements 5.2, 5.4).
     */
    @Override
    public String getProjectIdPath() {
        return "project.id";
    }

    /** Wires the allowed-project-ids lookup to the production cache. */
    @Override
    public Set<Long> allowedProjectIds(Long userId) {
        return projectAccessCache.get(userId);
    }

    // --- Pre-persist normalization (task 4.4) ---

    /**
     * Create-path hook: runs {@link #normalize(RoomServiceExtendedModel)} inside the generic
     * {@link AdminService#create(Object)} transaction, before the model is mapped to a new
     * {@link RoomEntity}. A thrown {@link ForemenApiException} rolls the create back with nothing
     * persisted.
     */
    @Override
    public void validateCreate(RoomServiceExtendedModel model) {
        normalize(model);
    }

    /**
     * Update-path hook: runs {@link #normalize(RoomServiceExtendedModel)} inside the generic
     * {@link AdminService#update(Object, Object)} transaction, before the (now normalized) model is
     * copied onto the existing {@link RoomEntity}. A thrown {@link ForemenApiException} rolls the
     * update back with nothing persisted.
     */
    @Override
    public void validateUpdate(RoomEntity existing, RoomServiceExtendedModel model) {
        normalize(model);
    }

    /**
     * The shared create/update normalization. Mutates {@code model} in place so the mapper writes
     * geometry-authoritative (or manual-stamped) metrics onto the entity. Every rejection path
     * throws before any write, so the enclosing transaction rolls back.
     *
     * @param model the write-path model to resolve, validate, and stamp
     * @throws ForemenApiException 404 when a reference is missing; 400 for invalid geometry/opening,
     *                             out-of-range numeric, negative count, or invalid source
     */
    private void normalize(RoomServiceExtendedModel model) {
        resolveReferences(model);
        validateCounts(model);

        RoomGeometry geometry = model.getGeometry();
        if (hasGeometry(geometry)) {
            validateGeometry(geometry);
            applyCalculatedMetrics(model, geometry);
        } else {
            applyManualMetrics(model);
        }
    }

    /**
     * Resolves the mandatory {@code projectId} and {@code roomTypeId} references, rejecting a
     * missing (or null) one with a field-identifying {@code 404 error.entity.not.found}
     * (Requirement 5.6). Existence is asserted with a real load rather than a lazy reference so a
     * dangling id is caught here rather than at flush time.
     */
    private void resolveReferences(RoomServiceExtendedModel model) {
        Long projectId = model.getProjectId();
        if (projectId == null || projectDao.findById(projectId).isEmpty()) {
            throw notFound("projectId", projectId);
        }
        Long roomTypeId = model.getRoomTypeId();
        if (roomTypeId == null || roomTypeDao.findById(roomTypeId).isEmpty()) {
            throw notFound("roomTypeId", roomTypeId);
        }
    }

    /** True when a geometry with a non-null, non-empty vertex list is supplied. */
    private boolean hasGeometry(RoomGeometry geometry) {
        return geometry != null
                && geometry.getVertices() != null
                && !geometry.getVertices().isEmpty();
    }

    /**
     * Validates a supplied geometry (Requirements 2.3, 2.4): the polygon has at least
     * {@value #MIN_VERTICES} vertices, and every opening on every wall has a recognised
     * {@link OpeningType} and strictly-positive {@code count}, {@code height}, and {@code width}.
     */
    private void validateGeometry(RoomGeometry geometry) {
        List<RoomGeometry.Vertex> vertices = geometry.getVertices();
        if (vertices == null || vertices.size() < MIN_VERTICES) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, GEOMETRY_INVALID_MESSAGE);
        }
        List<RoomGeometry.Wall> walls = geometry.getWalls();
        if (walls == null) {
            return;
        }
        for (RoomGeometry.Wall wall : walls) {
            if (wall == null || wall.getOpenings() == null) {
                continue;
            }
            for (RoomGeometry.Opening opening : wall.getOpenings()) {
                validateOpening(opening);
            }
        }
    }

    /** Rejects a null opening or one whose type/count/height/width is out of range (Req 2.4). */
    private void validateOpening(RoomGeometry.Opening opening) {
        if (opening == null
                || (opening.getType() != OpeningType.DOOR && opening.getType() != OpeningType.WINDOW)
                || opening.getCount() <= 0
                || opening.getHeight() <= 0
                || opening.getWidth() <= 0) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, OPENING_INVALID_MESSAGE);
        }
    }

    /**
     * Geometry is authoritative (Requirement 4.2): runs the calculation engine and overwrites the
     * five metrics + door/window counts + wall/finish gaps with the computed values, stamping each
     * of the five sources {@code CALCULATED}.
     */
    private void applyCalculatedMetrics(RoomServiceExtendedModel model, RoomGeometry geometry) {
        RoomMetrics metrics = roomCalculationService.calculate(geometry, model.getCeilingHeight());

        model.setFloorArea(metrics.floorArea());
        model.setFloorAreaSource(MeasureSource.CALCULATED);
        model.setPerimeter(metrics.perimeter());
        model.setPerimeterSource(MeasureSource.CALCULATED);
        model.setWallArea(metrics.wallArea());
        model.setWallAreaSource(MeasureSource.CALCULATED);
        model.setDoorArea(metrics.doorArea());
        model.setDoorAreaSource(MeasureSource.CALCULATED);
        model.setWindowArea(metrics.windowArea());
        model.setWindowAreaSource(MeasureSource.CALCULATED);

        model.setDoorCount(metrics.doorCount());
        model.setWindowCount(metrics.windowCount());
        model.setWallGap(metrics.wallGap());
        model.setFinishGap(metrics.finishGap());
    }

    /**
     * Manual path (Requirement 4.1): with no geometry drawn, each of the five directly supplied
     * metrics is kept and stamped {@code MANUAL}; each supplied numeric is range-checked
     * {@code [0, 9999999999.99]} (Requirement 4.5) and any caller-supplied source flag is validated
     * against {@link MeasureSource} (Requirement 4.4). A metric left absent keeps a {@code null}
     * value and a {@code null} source.
     */
    private void applyManualMetrics(RoomServiceExtendedModel model) {
        model.setFloorAreaSource(stampManual("floorArea", model.getFloorArea(), model.getFloorAreaSource()));
        model.setWallAreaSource(stampManual("wallArea", model.getWallArea(), model.getWallAreaSource()));
        model.setPerimeterSource(stampManual("perimeter", model.getPerimeter(), model.getPerimeterSource()));
        model.setDoorAreaSource(stampManual("doorArea", model.getDoorArea(), model.getDoorAreaSource()));
        model.setWindowAreaSource(stampManual("windowArea", model.getWindowArea(), model.getWindowAreaSource()));
    }

    /**
     * Range-checks a manual metric and returns the source flag it should carry: {@code MANUAL} when
     * a value is supplied, {@code null} when the metric is absent. A caller-supplied source that is
     * not a defined {@link MeasureSource} is rejected via {@link #validateSource(String, MeasureSource)}.
     */
    private MeasureSource stampManual(String field, BigDecimal value, MeasureSource suppliedSource) {
        validateSource(field, suppliedSource);
        if (value == null) {
            return null;
        }
        validateMetricRange(field, value);
        return MeasureSource.MANUAL;
    }

    /**
     * Guards a caller-supplied {@link MeasureSource}: it must be one of the enum's defined values
     * (Requirement 4.4). A {@code null} source is allowed — the manual path (re)stamps it. Because
     * the enum binding rejects an unknown string before the service is reached, this guard defends
     * the invariant defensively rather than as the sole gate.
     */
    private void validateSource(String field, MeasureSource source) {
        if (source != null && source != MeasureSource.CALCULATED && source != MeasureSource.MANUAL) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, SOURCE_INVALID_MESSAGE, field);
        }
    }

    /**
     * Validates every supplied numeric metric is within {@code [0, 9999999999.99]} (Requirement
     * 4.5). Called on the manual path; on the geometry path the values come from the calculation
     * engine and are inherently non-negative.
     */
    private void validateMetricRange(String field, BigDecimal value) {
        if (value.compareTo(METRIC_MIN) < 0 || value.compareTo(METRIC_MAX) > 0) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, METRIC_RANGE_MESSAGE, field);
        }
    }

    /**
     * Validates the integer counts {@code doorCount}/{@code windowCount}/{@code internalCorners} are
     * non-negative when supplied (Requirement 4.5). Runs on both the geometry and manual paths for
     * {@code internalCorners}; the geometry-derived {@code doorCount}/{@code windowCount} are
     * re-stamped afterwards on the geometry path, so this guards a caller's own values.
     */
    private void validateCounts(RoomServiceExtendedModel model) {
        validateCount("doorCount", model.getDoorCount());
        validateCount("windowCount", model.getWindowCount());
        validateCount("internalCorners", model.getInternalCorners());
    }

    private void validateCount(String field, Integer count) {
        if (count != null && count < 0) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, COUNT_NEGATIVE_MESSAGE, field);
        }
    }

    private ForemenApiException notFound(String field, Long id) {
        return new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, field, id);
    }
}
