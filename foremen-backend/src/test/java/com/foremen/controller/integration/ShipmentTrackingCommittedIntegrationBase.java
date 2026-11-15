package com.foremen.controller.integration;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import com.foremen.dao.ShipmentDao;
import com.foremen.dao.ShipmentEventDao;
import com.foremen.dao.ShipmentLineDao;
import com.foremen.dao.model.DeliveryMethod;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialCategoryEntity;
import com.foremen.dao.model.MaterialEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.PositionKind;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.ShipmentEntity;
import com.foremen.dao.model.ShipmentLineEntity;
import com.foremen.dao.model.ShipmentStatus;
import com.foremen.dao.model.SourcingPath;
import com.foremen.service.procurement.shipment.tracking.CarrierThrottle;
import com.foremen.testsupport.MockMvcSecurityConfig;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-11-04 task 5.2: shared fixture for the integration tests that need <b>committed</b> data.
 *
 * <p>{@code ShipmentTrackingService.refresh} runs its read and write steps in {@code REQUIRES_NEW}
 * transactions (the carrier call sits between them), so rows created in a rolled-back test transaction would
 * be invisible to it. These tests are therefore not transactional: fixtures are committed through a
 * {@link TransactionTemplate}, and shipments are wiped in {@link #cleanShipments()} before every test.
 *
 * <p>The context enables the master switch and the {@code STUB} carrier, with a tiny call spacing so
 * sequential refreshes do not wait. The class owns its PostgreSQL container, so committed rows never leak into
 * the rolled-back suites. Liquibase is off under the {@code integration-test} profile (Hibernate creates the
 * schema).
 *
 * <p>Repeatability: every project, material and tracking number carries a per-run unique suffix; shipment
 * tables are emptied before each test, the throttle state is reset.
 */
@SpringBootTest(properties = {
        "foremen.procurement.tracking.enabled=true",
        "foremen.procurement.tracking.stub.enabled=true",
        "foremen.procurement.tracking.min-call-spacing=20ms"
})
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
abstract class ShipmentTrackingCommittedIntegrationBase {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_tracking_it")
            .withUsername("test")
            .withPassword("test");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected PlatformTransactionManager txManager;
    @Autowired
    protected ShipmentDao shipmentDao;
    @Autowired
    protected ShipmentLineDao lineDao;
    @Autowired
    protected ShipmentEventDao eventDao;
    @Autowired
    protected CarrierThrottle throttle;
    @PersistenceContext
    protected EntityManager em;

    private TransactionTemplate tx;
    private Long categoryId;
    private Long materialId;
    private Long typeId;
    private Long unitId;
    private Long packageId;

    protected static String unique() {
        return System.nanoTime() + "_" + COUNTER.incrementAndGet();
    }

    protected TransactionTemplate tx() {
        if (tx == null) {
            tx = new TransactionTemplate(txManager);
        }
        return tx;
    }

    @BeforeEach
    void cleanShipments() {
        tx().executeWithoutResult(s -> {
            em.createQuery("delete from ShipmentEventEntity").executeUpdate();
            em.createQuery("delete from ShipmentLineEntity").executeUpdate();
            em.createQuery("delete from ShipmentEntity").executeUpdate();
        });
        ReflectionTestUtils.invokeMethod(throttleMap("pausedUntil"), "clear");
        ReflectionTestUtils.invokeMethod(throttleMap("lastCall"), "clear");
    }

    private Object throttleMap(String field) {
        return ReflectionTestUtils.getField(throttle, field);
    }

    // --- committed fixtures ---

    private void ensureCatalog() {
        if (categoryId != null) {
            return;
        }
        tx().executeWithoutResult(s -> {
            String u = unique();
            MaterialCategoryEntity category = new MaterialCategoryEntity();
            category.setCode("st_cat_" + u);
            category.setNameRU("Категория");
            category.setNamePL("Kategoria");
            category.setActive(true);
            em.persist(category);
            MaterialEntity material = new MaterialEntity();
            material.setCode("st_mat_" + u);
            material.setNameRU("Пол");
            material.setNamePL("Podłoga");
            material.setActive(true);
            em.persist(material);
            MaterialTypeEntity type = new MaterialTypeEntity();
            type.setCode("st_type_" + u);
            type.setNameRU("Тип");
            type.setNamePL("Typ");
            type.setActive(true);
            em.persist(type);
            MeasurementUnitEntity unit = new MeasurementUnitEntity();
            unit.setCode("st_unit_" + u);
            unit.setNameRU("м2");
            unit.setNamePL("m2");
            unit.setActive(true);
            em.persist(unit);
            OfferPackageEntity pkg = new OfferPackageEntity();
            pkg.setCode("st_pkg_" + u);
            pkg.setOrderNo(1);
            pkg.setNameRU("Пакет");
            pkg.setNamePL("Pakiet");
            pkg.setActive(true);
            em.persist(pkg);
            em.flush();
            categoryId = category.getId();
            materialId = material.getId();
            typeId = type.getId();
            unitId = unit.getId();
            packageId = pkg.getId();
        });
    }

    protected Long newProject() {
        return tx().execute(s -> {
            ProjectEntity p = new ProjectEntity();
            p.setName("ST project " + unique());
            p.setStatus(ProjectStatus.ACTIVE);
            em.persist(p);
            em.flush();
            return p.getId();
        });
    }

    /** A committed AGREED position with quantity and ordered quantity 5. */
    protected Long newPosition(Long projectId, PositionStatus status) {
        ensureCatalog();
        return tx().execute(s -> {
            FinishingMaterialEntity m = new FinishingMaterialEntity();
            m.setCategory(em.getReference(MaterialCategoryEntity.class, categoryId));
            m.setMaterial(em.getReference(MaterialEntity.class, materialId));
            m.setType(em.getReference(MaterialTypeEntity.class, typeId));
            m.setUnit(em.getReference(MeasurementUnitEntity.class, unitId));
            m.setPackages(new HashSet<>(Set.of(em.getReference(OfferPackageEntity.class, packageId))));
            m.setProject(em.getReference(ProjectEntity.class, projectId));
            m.setModel("Model " + unique());
            m.setRetailNet(new BigDecimal("10.00"));
            m.setCostNet(BigDecimal.ZERO);
            m.setActive(true);
            em.persist(m);
            PurchasePositionEntity pos = new PurchasePositionEntity();
            pos.setProject(em.getReference(ProjectEntity.class, projectId));
            pos.setKind(PositionKind.FINISHING);
            pos.setSourcingPath(SourcingPath.AGREED);
            pos.setStatus(status);
            pos.setPlannedMaterial(m);
            pos.setQuantity(new BigDecimal("5.000"));
            pos.setOrderedQuantity(new BigDecimal("5.000"));
            pos.setUnit(em.getReference(MeasurementUnitEntity.class, unitId));
            em.persist(pos);
            em.flush();
            return pos.getId();
        });
    }

    /** A committed CARRIER / STUB shipment with one line of the position, due for tracking now. */
    protected Long newStubShipment(Long projectId, Long positionId, String trackingNumber,
                                   ShipmentStatus status, OffsetDateTime nextCheckAt) {
        return tx().execute(s -> {
            ShipmentEntity sh = new ShipmentEntity();
            sh.setProject(em.getReference(ProjectEntity.class, projectId));
            sh.setDeliveryMethod(DeliveryMethod.CARRIER);
            sh.setCarrierCode("STUB");
            sh.setTrackingNumber(trackingNumber);
            sh.setStatus(status);
            sh.setShippedAt(OffsetDateTime.now(ZoneOffset.UTC));
            sh.setNextCheckAt(nextCheckAt);
            shipmentDao.save(sh);
            if (positionId != null) {
                ShipmentLineEntity line = new ShipmentLineEntity();
                line.setShipment(sh);
                line.setPosition(em.getReference(PurchasePositionEntity.class, positionId));
                line.setQuantity(new BigDecimal("2.000"));
                lineDao.save(line);
            }
            em.flush();
            return sh.getId();
        });
    }

    protected ShipmentEntity reload(Long shipmentId) {
        return tx().execute(s -> {
            ShipmentEntity sh = shipmentDao.findById(shipmentId).orElseThrow();
            sh.getStatus();
            return sh;
        });
    }

    /** Pretends the last check happened {@code seconds} ago (steps past the manual refresh cooldown). */
    protected void ageLastCheck(Long shipmentId, long seconds) {
        tx().executeWithoutResult(s -> {
            ShipmentEntity sh = shipmentDao.findById(shipmentId).orElseThrow();
            sh.setLastCheckedAt(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(seconds));
            shipmentDao.save(sh);
        });
    }

    /** Today in the tracking zone (the {@code STUB} adapter derives its ETAs from it). */
    protected static java.time.LocalDate warsawToday() {
        return java.time.LocalDate.now(java.time.ZoneId.of("Europe/Warsaw"));
    }

    protected PurchasePositionEntity position(Long id) {
        return tx().execute(s -> em.find(PurchasePositionEntity.class, id));
    }
}
