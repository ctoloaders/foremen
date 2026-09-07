package com.foremen.service;

import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.model.MeasureSource;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.RoomServiceExtendedModel;
import com.foremen.service.model.mapper.RoomServiceMapper;
import jakarta.persistence.EntityManager;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property 6 for FOR-04-14: manual source stamping.
 *
 * <p><b>Property 6:</b> when the geometry is absent, each directly supplied metric
 * ({@code floorArea}, {@code wallArea}, {@code perimeter}, {@code doorArea}, {@code windowArea}) is
 * kept and stamped with source {@code MANUAL}; each absent metric keeps a {@code null} value and a
 * {@code null} source. The normalization runs through the public {@link RoomService#validateCreate}
 * hook, which first resolves the {@code project}/{@code roomType} references — both mocked to be
 * present — then, with no geometry, stamps the supplied metrics {@code MANUAL} (Requirement 4.1).
 *
 * <p><b>Validates: Requirements 4.1</b>
 */
// Feature: FOR-04-14-room, Property 6
@Tag("Feature: FOR-04-14-room, Property 6")
class RoomManualSourcePropertyTest {

    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private record Fixture(RoomService service) {}

    /**
     * Instantiates {@link RoomService} with Mockito mocks for the CRUD collaborators and a real
     * {@link RoomCalculationService}. {@link ProjectDao#findById(Object)} and
     * {@link RoomTypeDao#findById(Object)} return present entities so
     * {@code resolveReferences} passes and the manual-stamping branch is exercised in isolation.
     */
    private static Fixture newFixture() {
        RoomDao roomDao = mock(RoomDao.class);
        RoomServiceMapper roomServiceMapper = mock(RoomServiceMapper.class);
        ProjectAccessCache projectAccessCache = mock(ProjectAccessCache.class);
        AuditLogDao auditLogDao = mock(AuditLogDao.class);
        EntityManager entityManager = mock(EntityManager.class);
        ProjectDao projectDao = mock(ProjectDao.class);
        RoomTypeDao roomTypeDao = mock(RoomTypeDao.class);
        RoomCalculationService roomCalculationService = new RoomCalculationService();

        when(projectDao.findById(anyLong())).thenReturn(Optional.of(new ProjectEntity()));
        when(roomTypeDao.findById(anyLong())).thenReturn(Optional.of(new RoomTypeEntity()));

        RoomService service = new RoomService(
                roomDao, roomServiceMapper, projectAccessCache, auditLogDao, entityManager,
                projectDao, roomTypeDao, roomCalculationService);
        return new Fixture(service);
    }

    /**
     * For a model with {@code null} geometry and an arbitrary subset of supplied metrics, each
     * supplied metric survives normalization with source {@code MANUAL}, and each absent metric
     * keeps a {@code null} value and {@code null} source.
     */
    @Property(tries = 100)
    @Tag("Feature: FOR-04-14-room, Property 6")
    void suppliedMetricsAreStampedManualAndAbsentOnesStayNull(
            @ForAll("optionalMetric") BigDecimal floorArea,
            @ForAll("optionalMetric") BigDecimal wallArea,
            @ForAll("optionalMetric") BigDecimal perimeter,
            @ForAll("optionalMetric") BigDecimal doorArea,
            @ForAll("optionalMetric") BigDecimal windowArea) {

        Fixture f = newFixture();

        RoomServiceExtendedModel model = new RoomServiceExtendedModel();
        model.setProjectId(1L);
        model.setRoomTypeId(2L);
        model.setGeometry(null);
        model.setFloorArea(floorArea);
        model.setWallArea(wallArea);
        model.setPerimeter(perimeter);
        model.setDoorArea(doorArea);
        model.setWindowArea(windowArea);

        f.service().validateCreate(model);

        assertMetric("floorArea", model.getFloorArea(), model.getFloorAreaSource(), floorArea);
        assertMetric("wallArea", model.getWallArea(), model.getWallAreaSource(), wallArea);
        assertMetric("perimeter", model.getPerimeter(), model.getPerimeterSource(), perimeter);
        assertMetric("doorArea", model.getDoorArea(), model.getDoorAreaSource(), doorArea);
        assertMetric("windowArea", model.getWindowArea(), model.getWindowAreaSource(), windowArea);
    }

    /**
     * A supplied metric keeps its value and is stamped {@code MANUAL}; an absent one keeps a
     * {@code null} value and a {@code null} source.
     */
    private void assertMetric(String field, BigDecimal actualValue, MeasureSource actualSource,
                              BigDecimal supplied) {
        if (supplied == null) {
            assertThat(actualValue).as("%s value stays null when absent", field).isNull();
            assertThat(actualSource).as("%s source stays null when absent", field).isNull();
        } else {
            assertThat(actualValue).as("%s value is kept unchanged", field)
                    .isEqualByComparingTo(supplied);
            assertThat(actualSource).as("%s source is stamped MANUAL", field)
                    .isEqualTo(MeasureSource.MANUAL);
        }
    }

    // ---- Generators -----------------------------------------------------------------------------

    /**
     * A metric that is either absent ({@code null}) or a value within the valid range
     * {@code [0, 9999999999.99]}, rounded to 2 decimals so it survives the service range check.
     */
    @Provide
    Arbitrary<BigDecimal> optionalMetric() {
        Arbitrary<BigDecimal> present = Arbitraries.doubles()
                .between(0.0d, 9_999_999_999.99d)
                .map(d -> BigDecimal.valueOf(d).setScale(SCALE, ROUNDING));
        return Combinators.combine(Arbitraries.of(true, false), present)
                .as((absent, value) -> absent ? null : value);
    }
}
