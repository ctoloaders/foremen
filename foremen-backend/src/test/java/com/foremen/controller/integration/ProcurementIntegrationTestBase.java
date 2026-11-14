package com.foremen.controller.integration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

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
import com.foremen.dao.model.PurchaseRequestEntity;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.permission.PermissionCache;
import com.foremen.testsupport.MockMvcSecurityConfig;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Shared fixture for the FOR-11-00 task 11.3 integration tests of {@code /api/purchase-positions} and
 * {@code /api/purchase-requests}. All subclasses share one Spring context and one PostgreSQL container.
 *
 * <p>Liquibase is off under the {@code integration-test} profile, so {@link #seedMatrix()} recreates the
 * Task 7.1 grants (and the 23 % / 8 % VAT rates) per test through JPA, inside the rolled-back
 * transaction. Default caller is ADMIN ({@code @WithMockUser}); non-admin callers are produced with
 * {@link #as(UserEntity, String)}, whose principal name is the numeric user id and whose authority is
 * {@code ROLE_<code>} (the shape the JWT filter produces).
 *
 * <p>Repeatability: every entity is created with a per-run unique name and rolled back after the test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
abstract class ProcurementIntegrationTestBase {

    static final String POSITIONS = "/api/purchase-positions";
    static final String REQUESTS = "/api/purchase-requests";

    /**
     * Singleton container started once per JVM (not a per-class {@code @Container}): all subclasses share
     * one cached Spring context, which would otherwise keep pointing at a container stopped after the first class.
     */
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
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
    protected ProjectAccessCache projectAccessCache;
    @Autowired
    protected PermissionCache permissionCache;

    @PersistenceContext
    protected EntityManager em;

    protected MaterialCategoryEntity category;
    protected MaterialEntity material;
    protected MaterialTypeEntity type;
    protected MeasurementUnitEntity unit;
    protected OfferPackageEntity offerPackage;
    protected VatRateEntity vat23;
    protected VatRateEntity vat8;

    protected static String unique() {
        return System.nanoTime() + "_" + COUNTER.incrementAndGet();
    }

    @BeforeEach
    void seedMatrix() {
        String u = unique();
        category = new MaterialCategoryEntity();
        category.setCode("pc_cat_" + u);
        category.setNameRU("Категория");
        category.setNamePL("Kategoria");
        category.setActive(true);
        em.persist(category);

        material = new MaterialEntity();
        material.setCode("pc_mat_" + u);
        material.setNameRU("Пол");
        material.setNamePL("Podłoga");
        material.setActive(true);
        em.persist(material);

        type = new MaterialTypeEntity();
        type.setCode("pc_type_" + u);
        type.setNameRU("Тип");
        type.setNamePL("Typ");
        type.setActive(true);
        em.persist(type);

        unit = new MeasurementUnitEntity();
        unit.setCode("pc_unit_" + u);
        unit.setNameRU("м2");
        unit.setNamePL("m2");
        unit.setActive(true);
        em.persist(unit);

        offerPackage = new OfferPackageEntity();
        offerPackage.setCode("pc_pkg_" + u);
        offerPackage.setOrderNo(1);
        offerPackage.setNameRU("Пакет");
        offerPackage.setNamePL("Pakiet");
        offerPackage.setActive(true);
        em.persist(offerPackage);

        vat23 = vat("23", u);
        vat8 = vat("8", u);

        OperationEntity create = operation("CREATE");
        OperationEntity read = operation("READ");
        OperationEntity update = operation("UPDATE");
        OperationEntity delete = operation("DELETE");
        ResourceEntity positions = resource("PURCHASE_POSITIONS");
        ResourceEntity requests = resource("PURCHASE_REQUESTS");

        role("ADMIN");
        role("WORKER");
        // Task 7.1 matrix.
        grant("MANAGER", positions, create, read, update, delete);
        grant("MANAGER", requests, create, read, update, delete);
        grant("BUYER", positions, create, read, update);
        grant("BUYER", requests, read, update);
        grant("FOREMAN", positions, read);
        grant("FOREMAN", requests, read);
        // Test-only: CLIENT has no grant in the seed; READ is added here solely to exercise the
        // CLIENT price-visibility audience through HTTP.
        grant("CLIENT", positions, read);
        em.flush();
        for (String code : List.of("ADMIN", "MANAGER", "BUYER", "FOREMAN", "CLIENT", "WORKER")) {
            permissionCache.invalidate(code);
        }
    }

    // --- request post-processors ---

    /** Authenticates as the given user with {@code ROLE_<roleCode>}; principal name is the numeric user id. */
    protected static RequestPostProcessor as(UserEntity user, String roleCode) {
        return SecurityMockMvcRequestPostProcessors.user(String.valueOf(user.getId()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + roleCode));
    }

    // --- entity builders ---

    protected VatRateEntity vat(String rate, String u) {
        VatRateEntity v = new VatRateEntity();
        v.setCode("pc_vat_" + rate + "_" + u);
        v.setRate(new BigDecimal(rate));
        v.setNameRU(rate + "%");
        v.setNamePL(rate + "%");
        v.setActive(true);
        em.persist(v);
        return v;
    }

    protected OperationEntity operation(String code) {
        List<OperationEntity> existing = em.createQuery(
                "select o from OperationEntity o where o.code = :c", OperationEntity.class)
                .setParameter("c", code).getResultList();
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

    protected ResourceEntity resource(String code) {
        List<ResourceEntity> existing = em.createQuery(
                "select r from ResourceEntity r where r.code = :c", ResourceEntity.class)
                .setParameter("c", code).getResultList();
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

    protected RoleEntity role(String code) {
        List<RoleEntity> existing = em.createQuery(
                "select r from RoleEntity r where r.code = :c", RoleEntity.class)
                .setParameter("c", code).getResultList();
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

    protected void grant(String roleCode, ResourceEntity resource, OperationEntity... ops) {
        RoleEntity role = role(roleCode);
        RoleResourceEntity rr = new RoleResourceEntity();
        rr.setRole(role);
        rr.setResource(resource);
        for (OperationEntity op : ops) {
            rr.getOperations().add(op);
        }
        role.getRoleResources().add(rr);
        em.persist(rr);
    }

    protected UserEntity user(String roleCode) {
        UserEntity u = new UserEntity();
        u.setName("PC user " + unique());
        u.setEmail("pc+" + unique() + "@example.com");
        u.setRole(role(roleCode));
        u.setActive(true);
        u.setStatus(UserStatus.ACTIVE);
        u.setLocale(Locale.forLanguageTag("ru"));
        em.persist(u);
        em.flush();
        return u;
    }

    protected ProjectEntity project(ProjectStatus status) {
        ProjectEntity p = new ProjectEntity();
        p.setName("PC project " + unique());
        p.setStatus(status);
        em.persist(p);
        em.flush();
        return p;
    }

    protected void member(UserEntity user, ProjectEntity project) {
        ProjectMemberEntity pm = new ProjectMemberEntity();
        pm.setUser(user);
        pm.setProjectId(project.getId());
        pm.setProjectRole(role("WORKER"));
        em.persist(pm);
        em.flush();
        projectAccessCache.invalidate(user.getId());
    }

    /** A user with the given role who is an ACTIVE member of {@code project}. */
    protected UserEntity memberUser(String roleCode, ProjectEntity project) {
        UserEntity u = user(roleCode);
        member(u, project);
        return u;
    }

    /** Material: global when {@code project} is null, otherwise a Project_Material. */
    protected FinishingMaterialEntity material(ProjectEntity project) {
        FinishingMaterialEntity m = new FinishingMaterialEntity();
        m.setCategory(category);
        m.setMaterial(material);
        m.setType(type);
        m.setUnit(unit);
        m.setPackages(new HashSet<>(Set.of(offerPackage)));
        m.setProject(project);
        m.setModel("Model " + unique());
        m.setRetailNet(new BigDecimal("10.00"));
        m.setCostNet(BigDecimal.ZERO);
        m.setActive(true);
        em.persist(m);
        em.flush();
        return m;
    }

    protected PurchasePositionEntity position(ProjectEntity project, SourcingPath path, PositionStatus status) {
        return position(project, material(project), path, status);
    }

    protected PurchasePositionEntity position(ProjectEntity project, FinishingMaterialEntity planned,
                                              SourcingPath path, PositionStatus status) {
        PurchasePositionEntity pos = new PurchasePositionEntity();
        pos.setProject(project);
        pos.setKind(PositionKind.FINISHING);
        pos.setSourcingPath(path);
        pos.setStatus(status);
        pos.setPlannedMaterial(planned);
        pos.setQuantity(new BigDecimal("5.000"));
        pos.setUnit(unit);
        pos.setPlannedPurchasePrice(new BigDecimal("80.00"));
        pos.setSalePrice(new BigDecimal("100.00"));
        pos.setActualPurchasePrice(new BigDecimal("85.00"));
        pos.setPurchaseVatRate(vat23);
        pos.setSaleVatRate(vat8);
        pos.setDeliveryCostShare(new BigDecimal("7.00"));
        em.persist(pos);
        em.flush();
        return pos;
    }

    protected PurchaseRequestEntity request(ProjectEntity project) {
        PurchaseRequestEntity r = new PurchaseRequestEntity();
        r.setProject(project);
        r.setReferenceNumber("REQ-" + unique());
        em.persist(r);
        em.flush();
        return r;
    }

    protected void attach(PurchasePositionEntity position, PurchaseRequestEntity request) {
        position.setPurchaseRequest(request);
        em.flush();
        em.refresh(request);
    }

    // --- JSON ---

    protected String createPositionJson(Long projectId, Long materialId, String extra) {
        return """
                {"projectId": %d, "kind": "FINISHING", "sourcingPath": "AGREED",
                 "plannedMaterialId": %d, "quantity": 3%s}
                """.formatted(projectId, materialId, extra == null || extra.isEmpty() ? "" : ", " + extra);
    }

    protected static LocalDate today() {
        return LocalDate.now();
    }
}
