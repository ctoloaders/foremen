package com.foremen.service.dashboard.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.DashboardWidgetEntity;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialCategoryEntity;
import com.foremen.dao.model.MaterialEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.PositionKind;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserDashboardWidgetEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.dashboard.DashboardDataService;
import com.foremen.service.dashboard.query.WidgetResult;
import com.foremen.service.permission.PermissionCache;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FOR-11-00 task 15.3: the seeded procurement widgets (changeset 231, parsed from the file) executed through
 * {@link DashboardDataService#fetch} against a PostgreSQL Testcontainer with positions in several stages
 * across two projects. Covers project scoping (ADMIN and BUYER see everything, FOREMAN only member
 * projects, BUYER through the dataset's {@code nullProjectPredicate}), Has_Access on
 * {@code PURCHASE_POSITIONS}, and the absence of money in results.
 *
 * <p>Repeatability: every entity carries a per-run unique key and the test runs in a rolled-back
 * transaction (the permission / project caches are invalidated afterwards).
 *
 * <p><b>Validates: Requirements 24.2, 24.5, 24.6, 14.7</b>
 */
class ProcurementWidgetExecutionIntegrationTest extends AbstractDatasetIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger COUNTER = new AtomicInteger();

    @PersistenceContext
    private EntityManager em;
    @Autowired
    private DashboardDataService dataService;
    @Autowired
    private PermissionCache permissionCache;
    @Autowired
    private ProjectAccessCache projectAccessCache;

    private String run;
    private ProjectEntity projectA;
    private ProjectEntity projectB;
    private UserEntity admin;
    private UserEntity buyer;
    private UserEntity foremanA;
    private UserEntity manager;
    private UserEntity workerNoAccess;
    private final Set<String> touchedRoles = new HashSet<>();
    private final Set<Long> touchedUsers = new HashSet<>();

    private MeasurementUnitEntity unit;
    private FinishingMaterialEntity materialItem;

    @BeforeEach
    void setUp() {
        run = System.nanoTime() + "_" + COUNTER.incrementAndGet();
        OperationEntity read = operation("READ");
        ResourceEntity positions = resource("PURCHASE_POSITIONS");
        grant("BUYER", positions, read);
        grant("FOREMAN", positions, read);
        grant("MANAGER", positions, read);
        role("ADMIN");
        role("WORKER"); // no PURCHASE_POSITIONS grant at all
        em.flush();

        projectA = project("A");
        projectB = project("B");
        admin = user("ADMIN", "admin");
        buyer = user("BUYER", "buyer");
        foremanA = user("FOREMAN", "foreman");
        manager = user("MANAGER", "manager");
        workerNoAccess = user("WORKER", "worker");
        member(foremanA, projectA);
        member(manager, projectA);
        member(manager, projectB);
        member(workerNoAccess, projectA);
        invalidate();

        materialItem = material();
        // Project A: 2 WITH_BUYER (dated), 1 ORDERED (dated), 1 CANCELLED (dated), 1 RECEIVED
        position(projectA, PositionStatus.WITH_BUYER, 3);
        position(projectA, PositionStatus.WITH_BUYER, 5);
        position(projectA, PositionStatus.ORDERED, 7);
        position(projectA, PositionStatus.CANCELLED, 2);
        position(projectA, PositionStatus.RECEIVED, 1);
        // Project B: 1 WITH_BUYER (dated), 1 IN_DELIVERY (dated), 1 PLANNED (no date), 1 ORDERED (no date)
        position(projectB, PositionStatus.WITH_BUYER, 4);
        position(projectB, PositionStatus.IN_DELIVERY, 6);
        position(projectB, PositionStatus.PLANNED, null);
        position(projectB, PositionStatus.ORDERED, null);
        em.flush();
    }

    @AfterEach
    void tearDown() {
        invalidate();
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("POSITIONS_WITH_BUYER KPI: ADMIN and BUYER count every project, FOREMAN only member projects")
    void kpiScoping() throws Exception {
        long id = placement(admin, "POSITIONS_WITH_BUYER");
        assertThat(kpi(dataService.fetch(admin.getId(), id, null, null))).isEqualTo(3);
        assertThat(kpi(dataService.fetch(buyer.getId(), placement(buyer, "POSITIONS_WITH_BUYER"), null, null)))
                .as("BUYER is a member of no project yet sees both").isEqualTo(3);
        assertThat(kpi(dataService.fetch(foremanA.getId(), placement(foremanA, "POSITIONS_WITH_BUYER"), null, null)))
                .as("FOREMAN member of project A only").isEqualTo(2);
        assertThat(kpi(dataService.fetch(manager.getId(), placement(manager, "POSITIONS_WITH_BUYER"), null, null)))
                .as("MANAGER member of both").isEqualTo(3);
    }

    @Test
    @DisplayName("PROCUREMENT_BY_STAGE: counts per stage without CANCELLED, scoped per role")
    void byStageScoping() throws Exception {
        Map<String, Long> all = new TreeMap<>(Map.of("WITH_BUYER", 3L, "ORDERED", 2L, "RECEIVED", 1L,
                "IN_DELIVERY", 1L, "PLANNED", 1L));
        assertThat(stages(dataService.fetch(admin.getId(), placement(admin, "PROCUREMENT_BY_STAGE"), null, null)))
                .isEqualTo(all);
        assertThat(stages(dataService.fetch(buyer.getId(), placement(buyer, "PROCUREMENT_BY_STAGE"), null, null)))
                .isEqualTo(all);
        assertThat(stages(dataService.fetch(foremanA.getId(), placement(foremanA, "PROCUREMENT_BY_STAGE"), null, null)))
                .isEqualTo(new TreeMap<>(Map.of("WITH_BUYER", 2L, "ORDERED", 1L, "RECEIVED", 1L)));
    }

    @Test
    @DisplayName("UPCOMING_DELIVERIES: active stages with a planned date, nearest first, scoped, no money")
    void upcomingDeliveries() throws Exception {
        WidgetResult adminResult = dataService.fetch(admin.getId(), placement(admin, "UPCOMING_DELIVERIES"), null, null);
        // dated rows in WITH_BUYER/ORDERED/SUPPLIER_PAID/IN_DELIVERY/PARTIALLY_RECEIVED: A+3, A+5, A+7, B+4, B+6
        assertThat(projectIds(adminResult)).containsExactly(
                projectA.getId(), projectB.getId(), projectA.getId(), projectB.getId(), projectA.getId());
        assertThat(adminResult.rows()).extracting(r -> r.get("status")).doesNotContain("CANCELLED", "RECEIVED", "PLANNED");
        assertThat(dates(adminResult)).isSorted();

        WidgetResult buyerResult = dataService.fetch(buyer.getId(), placement(buyer, "UPCOMING_DELIVERIES"), null, null);
        assertThat(buyerResult.rows()).as("BUYER sees every project").hasSize(5);
        assertThat(projectIds(buyerResult)).contains(projectA.getId(), projectB.getId());

        WidgetResult foremanResult =
                dataService.fetch(foremanA.getId(), placement(foremanA, "UPCOMING_DELIVERIES"), null, null);
        assertThat(projectIds(foremanResult)).containsOnly(projectA.getId()).hasSize(3);
        assertThat(foremanResult.rows()).allSatisfy(r -> assertThat(r.get("plannedMaterial.model"))
                .isEqualTo(materialItem.getModel()));
    }

    @Test
    @DisplayName("results of every procurement widget contain no money field or value")
    void noMoneyInResults() throws Exception {
        for (String code : List.of("UPCOMING_DELIVERIES", "PROCUREMENT_BY_STAGE", "POSITIONS_WITH_BUYER")) {
            for (UserEntity u : List.of(admin, buyer, foremanA, manager)) {
                WidgetResult r = dataService.fetch(u.getId(), placement(u, code), null, null);
                assertThat(r.columns()).as("%s columns", code)
                        .allSatisfy(c -> assertThat(c.type().name()).isNotEqualTo("MONEY"));
                String json = JSON.writeValueAsString(r.rows());
                assertThat(json).as("%s rows", code).doesNotContain("amount", "currency", "85.00", "80.00", "100.00");
            }
        }
    }

    @Test
    @DisplayName("a role without any PURCHASE_POSITIONS operation gets the widgets disabled (NO_ACCESS)")
    void roleWithoutAccessIsDenied() throws Exception {
        for (String code : List.of("UPCOMING_DELIVERIES", "PROCUREMENT_BY_STAGE", "POSITIONS_WITH_BUYER")) {
            long id = placement(workerNoAccess, code);
            assertThatThrownBy(() -> dataService.fetch(workerNoAccess.getId(), id, null, null))
                    .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                        assertThat(e.getMessageCode()).isEqualTo("error.dashboard.widget.disabled");
                        assertThat(e.getParams()).containsEntry("reason", "NO_ACCESS");
                    });
        }
    }

    // ------------------------------------------------------------------ helpers

    private static long kpi(WidgetResult r) {
        assertThat(r.rows()).hasSize(1);
        return ((Number) r.rows().get(0).get("cnt")).longValue();
    }

    private static Map<String, Long> stages(WidgetResult r) {
        return r.rows().stream().collect(Collectors.toMap(row -> String.valueOf(row.get("status")),
                row -> ((Number) row.get("cnt")).longValue(), Long::sum, TreeMap::new));
    }

    private static List<Long> projectIds(WidgetResult r) {
        return r.rows().stream().map(row -> ((Number) row.get("project.id")).longValue()).toList();
    }

    private static List<String> dates(WidgetResult r) {
        return r.rows().stream().map(row -> String.valueOf(row.get("plannedDeliveryDate"))).toList();
    }

    /** Persists the library widget from the 231 changeset (once per code) and a placement for the user. */
    private long placement(UserEntity owner, String code) throws Exception {
        ProcurementWidgetSeedTest.Seed seed = ProcurementWidgetSeedTest.readSeeds().stream()
                .filter(s -> s.code().equals(code)).findFirst().orElseThrow();
        List<DashboardWidgetEntity> existing = em.createQuery(
                "select w from DashboardWidgetEntity w where w.code = :c", DashboardWidgetEntity.class)
                .setParameter("c", code).getResultList();
        DashboardWidgetEntity widget;
        if (existing.isEmpty()) {
            widget = new DashboardWidgetEntity();
            widget.setCode(code);
            widget.setNameRu(seed.nameRu());
            widget.setNamePl(seed.namePl());
            widget.setDescriptionRu(seed.descRu());
            widget.setDescriptionPl(seed.descPl());
            @SuppressWarnings("unchecked")
            Map<String, Object> cfg = JSON.readValue(seed.config(), Map.class);
            widget.setConfig(cfg);
            widget.setConfigVersion(seed.configVersion());
            widget.setColSpan((short) seed.colSpan());
            widget.setRowSpan((short) seed.rowSpan());
            widget.setProjectScope(seed.scope());
            widget.setActive(true);
            widget.setSystemSeed(true);
            em.persist(widget);
        } else {
            widget = existing.get(0);
        }
        UserDashboardWidgetEntity p = new UserDashboardWidgetEntity();
        p.setUser(owner);
        p.setWidget(widget);
        p.setCellIndex((short) (COUNTER.incrementAndGet() % 36));
        em.persist(p);
        em.flush();
        return p.getId();
    }

    private void invalidate() {
        touchedRoles.forEach(permissionCache::invalidate);
        touchedUsers.forEach(projectAccessCache::invalidate);
    }

    private OperationEntity operation(String code) {
        List<OperationEntity> existing = em.createQuery("select o from OperationEntity o where o.code = :c",
                OperationEntity.class).setParameter("c", code).getResultList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        OperationEntity o = new OperationEntity();
        o.setCode(code);
        o.setNameRU(code);
        o.setNamePL(code);
        em.persist(o);
        return o;
    }

    private ResourceEntity resource(String code) {
        List<ResourceEntity> existing = em.createQuery("select r from ResourceEntity r where r.code = :c",
                ResourceEntity.class).setParameter("c", code).getResultList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        ResourceEntity r = new ResourceEntity();
        r.setCode(code);
        r.setNameRU(code);
        r.setNamePL(code);
        r.setDescriptionRU(code);
        r.setDescriptionPL(code);
        em.persist(r);
        return r;
    }

    private RoleEntity role(String code) {
        touchedRoles.add(code);
        List<RoleEntity> existing = em.createQuery("select r from RoleEntity r where r.code = :c",
                RoleEntity.class).setParameter("c", code).getResultList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        RoleEntity r = new RoleEntity();
        r.setCode(code);
        r.setNameRU(code);
        r.setNamePL(code);
        r.setSystem(true);
        em.persist(r);
        return r;
    }

    private void grant(String roleCode, ResourceEntity resource, OperationEntity... ops) {
        RoleEntity role = role(roleCode);
        boolean already = role.getRoleResources().stream()
                .anyMatch(rr -> rr.getResource().getCode().equals(resource.getCode()));
        if (already) {
            return;
        }
        RoleResourceEntity rr = new RoleResourceEntity();
        rr.setRole(role);
        rr.setResource(resource);
        for (OperationEntity op : ops) {
            rr.getOperations().add(op);
        }
        role.getRoleResources().add(rr);
        em.persist(rr);
    }

    private UserEntity user(String roleCode, String key) {
        UserEntity u = new UserEntity();
        u.setName(key + " " + run);
        u.setEmail(key + "+" + run + "@example.com");
        u.setRole(role(roleCode));
        u.setActive(true);
        u.setStatus(UserStatus.ACTIVE);
        u.setLocale(Locale.forLanguageTag("ru"));
        em.persist(u);
        em.flush();
        touchedUsers.add(u.getId());
        return u;
    }

    private ProjectEntity project(String key) {
        ProjectEntity p = new ProjectEntity();
        p.setName("PW " + key + " " + run);
        p.setStatus(ProjectStatus.ACTIVE);
        em.persist(p);
        em.flush();
        return p;
    }

    private void member(UserEntity user, ProjectEntity project) {
        ProjectMemberEntity pm = new ProjectMemberEntity();
        pm.setUser(user);
        pm.setProjectId(project.getId());
        pm.setProjectRole(role("WORKER"));
        em.persist(pm);
        em.flush();
    }

    private FinishingMaterialEntity material() {
        MaterialCategoryEntity category = new MaterialCategoryEntity();
        category.setCode("pw_cat_" + run);
        category.setNameRU("Категория");
        category.setNamePL("Kategoria");
        category.setActive(true);
        em.persist(category);
        MaterialEntity mat = new MaterialEntity();
        mat.setCode("pw_mat_" + run);
        mat.setNameRU("Пол");
        mat.setNamePL("Podłoga");
        mat.setActive(true);
        em.persist(mat);
        MaterialTypeEntity type = new MaterialTypeEntity();
        type.setCode("pw_type_" + run);
        type.setNameRU("Тип");
        type.setNamePL("Typ");
        type.setActive(true);
        em.persist(type);
        unit = new MeasurementUnitEntity();
        unit.setCode("pw_unit_" + run);
        unit.setNameRU("м2");
        unit.setNamePL("m2");
        unit.setActive(true);
        em.persist(unit);
        OfferPackageEntity pkg = new OfferPackageEntity();
        pkg.setCode("pw_pkg_" + run);
        pkg.setOrderNo(1);
        pkg.setNameRU("Пакет");
        pkg.setNamePL("Pakiet");
        pkg.setActive(true);
        em.persist(pkg);
        FinishingMaterialEntity m = new FinishingMaterialEntity();
        m.setCategory(category);
        m.setMaterial(mat);
        m.setType(type);
        m.setUnit(unit);
        m.setPackages(new HashSet<>(Set.of(pkg)));
        m.setModel("Model " + run);
        m.setRetailNet(new BigDecimal("10.00"));
        m.setCostNet(BigDecimal.ZERO);
        m.setActive(true);
        em.persist(m);
        em.flush();
        return m;
    }

    private void position(ProjectEntity project, PositionStatus status, Integer deliveryInDays) {
        PurchasePositionEntity pos = new PurchasePositionEntity();
        pos.setProject(project);
        pos.setKind(PositionKind.FINISHING);
        pos.setSourcingPath(SourcingPath.AGREED);
        pos.setStatus(status);
        pos.setPlannedMaterial(materialItem);
        pos.setQuantity(new BigDecimal("5.000"));
        pos.setUnit(unit);
        pos.setPlannedPurchasePrice(new BigDecimal("80.00"));
        pos.setSalePrice(new BigDecimal("100.00"));
        pos.setActualPurchasePrice(new BigDecimal("85.00"));
        pos.setDeliveryCostShare(new BigDecimal("7.00"));
        if (deliveryInDays != null) {
            pos.setPlannedDeliveryDate(LocalDate.now().plusDays(deliveryInDays));
        }
        em.persist(pos);
    }
}
