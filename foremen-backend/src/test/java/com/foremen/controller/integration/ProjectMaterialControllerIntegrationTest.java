package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.FinishingMaterialDao;
import com.foremen.dao.MaterialCategoryDao;
import com.foremen.dao.MaterialDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.OfferPackageDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.PurchasePositionVariantDao;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialCategoryEntity;
import com.foremen.dao.model.MaterialEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.PositionKind;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchasePositionVariantEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.dao.model.VariantReason;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.ProjectMaterialService;
import com.foremen.service.model.ProjectMaterialServiceModel;
import com.foremen.testsupport.MockMvcSecurityConfig;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-11-00 task 11.2 - CRUD on {@code /api/project-materials} plus project scoping.
 *
 * <p>CRUD runs through MockMvc as ADMIN (the matrix bypass; the PROJECT_MATERIALS seed/grants are
 * task 7.1). Project scoping for non-admin callers is asserted at the service level
 * ({@link ProjectMaterialService}) with a hand-built security context whose principal name is the
 * numeric user id, because the HTTP matrix grants for non-admin roles are not seeded yet.
 *
 * <p>Repeatability: every test runs in a rolled-back transaction and all codes are generated per run.
 *
 * <p>Validates Requirements 2.*, 3.*, 19.1, 18.1.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MockMvcSecurityConfig.class)
@Testcontainers
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
@Transactional
class ProjectMaterialControllerIntegrationTest {

    private static final String BASE = "/api/project-materials";
    private static final String PL_NOT_FOUND = "Nie znaleziono materiału projektu.";
    private static final String PL_NOT_ACTIVE = "Projekt nie jest w realizacji.";
    private static final String PL_IMMUTABLE = "Nie można zmienić projektu materiału.";
    private static final String PL_IN_USE = "Materiał jest używany w pozycjach zakupowych.";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test");

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
    private MockMvc mockMvc;
    @Autowired
    private MaterialCategoryDao categoryDao;
    @Autowired
    private MaterialDao materialDao;
    @Autowired
    private MeasurementUnitDao unitDao;
    @Autowired
    private OfferPackageDao offerPackageDao;
    @Autowired
    private FinishingMaterialDao finishingMaterialDao;
    @Autowired
    private PurchasePositionDao positionDao;
    @Autowired
    private PurchasePositionVariantDao variantDao;
    @Autowired
    private ProjectMaterialService projectMaterialService;
    @Autowired
    private ProjectAccessCache projectAccessCache;

    @PersistenceContext
    private EntityManager entityManager;

    private MaterialCategoryEntity category;
    private MaterialEntity material;
    private MeasurementUnitEntity unit;
    private OfferPackageEntity offerPackage;
    private SecurityContext originalContext;

    private static String unique() {
        return System.nanoTime() + "_" + COUNTER.incrementAndGet();
    }

    @BeforeEach
    void seedReferences() {
        originalContext = SecurityContextHolder.getContext();
        String u = unique();

        category = new MaterialCategoryEntity();
        category.setCode("pm_cat_" + u);
        category.setNameRU("Категория");
        category.setNamePL("Kategoria");
        category.setActive(true);
        category = categoryDao.save(category);

        material = new MaterialEntity();
        material.setCode("pm_mat_" + u);
        material.setNameRU("Пол");
        material.setNamePL("Podłoga");
        material.setActive(true);
        material = materialDao.save(material);

        unit = new MeasurementUnitEntity();
        unit.setCode("pm_unit_" + u);
        unit.setNameRU("м2");
        unit.setNamePL("m2");
        unit.setActive(true);
        unit = unitDao.save(unit);

        offerPackage = new OfferPackageEntity();
        offerPackage.setCode("pm_pkg_" + u);
        offerPackage.setOrderNo(1);
        offerPackage.setNameRU("Пакет");
        offerPackage.setNamePL("Pakiet");
        offerPackage.setActive(true);
        offerPackage = offerPackageDao.save(offerPackage);

        entityManager.flush();
    }

    @AfterEach
    void restoreSecurityContext() {
        SecurityContextHolder.setContext(originalContext);
    }

    // --- fixtures ---

    private ProjectEntity persistProject(ProjectStatus status) {
        ProjectEntity p = new ProjectEntity();
        p.setName("PM project " + unique());
        p.setStatus(status);
        entityManager.persist(p);
        entityManager.flush();
        return p;
    }

    private FinishingMaterialEntity persistMaterial(ProjectEntity project, String model) {
        FinishingMaterialEntity m = new FinishingMaterialEntity();
        m.setCategory(category);
        m.setMaterial(material);
        m.setUnit(unit);
        m.setPackages(new HashSet<>(Set.of(offerPackage)));
        m.setProject(project);
        m.setModel(model);
        m.setRetailNet(new BigDecimal("10.00"));
        m.setCostNet(BigDecimal.ZERO);
        m.setActive(true);
        FinishingMaterialEntity saved = finishingMaterialDao.save(m);
        entityManager.flush();
        return saved;
    }

    private PurchasePositionEntity persistPosition(ProjectEntity project, FinishingMaterialEntity planned) {
        PurchasePositionEntity pos = new PurchasePositionEntity();
        pos.setProject(project);
        pos.setKind(PositionKind.FINISHING);
        pos.setSourcingPath(SourcingPath.PACKAGE);
        pos.setPlannedMaterial(planned);
        pos.setQuantity(new BigDecimal("5.000"));
        pos.setUnit(unit);
        PurchasePositionEntity saved = positionDao.save(pos);
        entityManager.flush();
        return saved;
    }

    private String createJson(Long projectId, String model) {
        String projectPart = projectId == null ? "" : "\"projectId\": " + projectId + ",";
        return """
                {
                    %s
                    "categoryId": %d,
                    "materialId": %d,
                    "offerPackageIds": [%d],
                    "unitId": %d,
                    "model": "%s",
                    "retailNet": 10.00,
                    "costNet": 8.00,
                    "active": true
                }
                """.formatted(projectPart, category.getId(), material.getId(), offerPackage.getId(),
                unit.getId(), model);
    }

    private String updateJson(Long projectId, String model) {
        return createJson(projectId, model);
    }

    // --- CREATE ---

    @Test
    @DisplayName("POST - creates a Project_Material for an ACTIVE project and returns projectId")
    void create_activeProject_succeeds() throws Exception {
        ProjectEntity project = persistProject(ProjectStatus.ACTIVE);

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(project.getId(), "Project Model")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.projectId").value(project.getId()))
                .andExpect(jsonPath("$.model").value("Project Model"));
    }

    @Test
    @DisplayName("POST - missing projectId is rejected with a client error")
    void create_missingProject_isRejected() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(null, "No project")))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("POST - non-ACTIVE project -> 409 error.projectMaterial.project.notActive")
    void create_nonActiveProject_returns409() throws Exception {
        ProjectEntity draft = persistProject(ProjectStatus.DRAFT);

        mockMvc.perform(post(BASE)
                        .header("Accept-Language", "pl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson(draft.getId(), "Draft project")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(PL_NOT_ACTIVE));
    }

    // --- READ ---

    @Test
    @DisplayName("GET by id - returns a Project_Material with its projectId")
    void getById_projectRow_returnsIt() throws Exception {
        ProjectEntity project = persistProject(ProjectStatus.ACTIVE);
        FinishingMaterialEntity m = persistMaterial(project, "Readable");

        mockMvc.perform(get(BASE + "/" + m.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(m.getId()))
                .andExpect(jsonPath("$.projectId").value(project.getId()));
    }

    @Test
    @DisplayName("GET by id / PUT / DELETE of a global row -> 404 error.projectMaterial.notFound")
    void globalRow_byId_returns404() throws Exception {
        FinishingMaterialEntity global = persistMaterial(null, "Global");
        ProjectEntity project = persistProject(ProjectStatus.ACTIVE);

        mockMvc.perform(get(BASE + "/" + global.getId()).header("Accept-Language", "pl"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(PL_NOT_FOUND));

        mockMvc.perform(put(BASE + "/" + global.getId())
                        .header("Accept-Language", "pl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson(project.getId(), "Changed")))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete(BASE + "/" + global.getId()).header("Accept-Language", "pl"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(PL_NOT_FOUND));

        assertThat(finishingMaterialDao.existsById(global.getId())).isTrue();
    }

    @Test
    @DisplayName("GET list as ADMIN - contains project rows only, never global rows")
    void list_admin_showsOnlyProjectRows() throws Exception {
        ProjectEntity project = persistProject(ProjectStatus.ACTIVE);
        FinishingMaterialEntity projectRow = persistMaterial(project, "Project row");
        FinishingMaterialEntity globalRow = persistMaterial(null, "Global row");

        mockMvc.perform(get(BASE).param("page", "0").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(hasItem(projectRow.getId().intValue())))
                .andExpect(jsonPath("$.content[*].id").value(not(hasItem(globalRow.getId().intValue()))))
                .andExpect(jsonPath("$.content[?(@.projectId == null)]").isEmpty());
    }

    // --- UPDATE ---

    @Test
    @DisplayName("PUT - updates fields; same projectId is accepted")
    void update_sameProject_succeeds() throws Exception {
        ProjectEntity project = persistProject(ProjectStatus.ACTIVE);
        FinishingMaterialEntity m = persistMaterial(project, "Before");

        mockMvc.perform(put(BASE + "/" + m.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson(project.getId(), "After")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("After"))
                .andExpect(jsonPath("$.projectId").value(project.getId()));
    }

    @Test
    @DisplayName("PUT - a different projectId -> 409 error.projectMaterial.project.immutable")
    void update_changeProject_returns409() throws Exception {
        ProjectEntity project = persistProject(ProjectStatus.ACTIVE);
        ProjectEntity other = persistProject(ProjectStatus.ACTIVE);
        FinishingMaterialEntity m = persistMaterial(project, "Pinned");

        mockMvc.perform(put(BASE + "/" + m.getId())
                        .header("Accept-Language", "pl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson(other.getId(), "Pinned")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(PL_IMMUTABLE));
    }

    // --- DELETE ---

    @Test
    @DisplayName("DELETE - unused Project_Material is removed")
    void delete_unused_succeeds() throws Exception {
        ProjectEntity project = persistProject(ProjectStatus.ACTIVE);
        FinishingMaterialEntity m = persistMaterial(project, "Disposable");

        mockMvc.perform(delete(BASE + "/" + m.getId()))
                .andExpect(status().is2xxSuccessful());
        entityManager.flush();
        entityManager.clear();

        assertThat(finishingMaterialDao.existsById(m.getId())).isFalse();
    }

    @Test
    @DisplayName("DELETE - material referenced by a purchase position -> 409 error.projectMaterial.inUse")
    void delete_usedByPosition_returns409() throws Exception {
        ProjectEntity project = persistProject(ProjectStatus.ACTIVE);
        FinishingMaterialEntity m = persistMaterial(project, "In position");
        persistPosition(project, m);

        mockMvc.perform(delete(BASE + "/" + m.getId()).header("Accept-Language", "pl"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(PL_IN_USE));

        assertThat(finishingMaterialDao.existsById(m.getId())).isTrue();
    }

    @Test
    @DisplayName("DELETE - material referenced only by a position variant -> 409 error.projectMaterial.inUse")
    void delete_usedByVariant_returns409() throws Exception {
        ProjectEntity project = persistProject(ProjectStatus.ACTIVE);
        FinishingMaterialEntity planned = persistMaterial(project, "Planned");
        FinishingMaterialEntity retired = persistMaterial(project, "Retired option");
        PurchasePositionEntity position = persistPosition(project, planned);

        PurchasePositionVariantEntity variant = new PurchasePositionVariantEntity();
        variant.setPosition(position);
        variant.setMaterial(retired);
        variant.setReason(VariantReason.ANALOG);
        variantDao.save(variant);
        entityManager.flush();

        mockMvc.perform(delete(BASE + "/" + retired.getId()).header("Accept-Language", "pl"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(PL_IN_USE));

        assertThat(finishingMaterialDao.existsById(retired.getId())).isTrue();
    }

    // --- project scoping (service level, non-admin callers) ---

    @Test
    @DisplayName("scoping - a non-member sees no rows of another project; a member sees only own project")
    void scoping_nonMemberCannotSeeOtherProjectMaterials() {
        ProjectEntity projectA = persistProject(ProjectStatus.ACTIVE);
        ProjectEntity projectB = persistProject(ProjectStatus.ACTIVE);
        FinishingMaterialEntity inA = persistMaterial(projectA, "In A");
        FinishingMaterialEntity inB = persistMaterial(projectB, "In B");

        RoleEntity foremanRole = persistRole("PMFOREMAN_" + unique());
        UserEntity member = persistUser(foremanRole);
        UserEntity outsider = persistUser(foremanRole);
        assignMember(member, projectA.getId(), foremanRole);
        projectAccessCache.invalidate(member.getId());
        projectAccessCache.invalidate(outsider.getId());

        // member of A: sees A only, and cannot read B by id
        authenticateAs(member.getId(), "ROLE_FOREMAN");
        assertThat(listIds()).contains(inA.getId()).doesNotContain(inB.getId());
        assertThat(projectMaterialService.findById(inA.getId()).getId()).isEqualTo(inA.getId());
        assertThatThrownBy(() -> projectMaterialService.findById(inB.getId()))
                .isInstanceOf(ForemenApiException.class);

        // outsider: no memberships, sees nothing
        authenticateAs(outsider.getId(), "ROLE_FOREMAN");
        assertThat(listIds()).doesNotContain(inA.getId(), inB.getId());
        assertThatThrownBy(() -> projectMaterialService.findById(inA.getId()))
                .isInstanceOf(ForemenApiException.class);
    }

    @Test
    @DisplayName("scoping - a BUYER sees Project_Materials of all projects, and never global rows")
    void scoping_buyerSeesAllProjects() {
        ProjectEntity projectA = persistProject(ProjectStatus.ACTIVE);
        ProjectEntity projectB = persistProject(ProjectStatus.ACTIVE);
        FinishingMaterialEntity inA = persistMaterial(projectA, "In A");
        FinishingMaterialEntity inB = persistMaterial(projectB, "In B");
        FinishingMaterialEntity global = persistMaterial(null, "Global");

        RoleEntity buyerRole = findOrCreateRole("BUYER");
        UserEntity buyer = persistUser(buyerRole);
        projectAccessCache.invalidate(buyer.getId());

        authenticateAs(buyer.getId(), "ROLE_BUYER");
        assertThat(listIds()).contains(inA.getId(), inB.getId()).doesNotContain(global.getId());
    }

    // --- helpers: users, roles, security ---

    private List<Long> listIds() {
        Page<ProjectMaterialServiceModel> page = projectMaterialService.find(PageRequest.of(0, 100));
        return page.getContent().stream().map(ProjectMaterialServiceModel::getId).toList();
    }

    private RoleEntity persistRole(String code) {
        RoleEntity r = new RoleEntity();
        r.setCode(code);
        r.setNameRU(code);
        r.setNamePL(code);
        r.setSystem(false);
        entityManager.persist(r);
        entityManager.flush();
        return r;
    }

    private RoleEntity findOrCreateRole(String code) {
        List<RoleEntity> existing = entityManager
                .createQuery("select r from RoleEntity r where r.code = :c", RoleEntity.class)
                .setParameter("c", code)
                .getResultList();
        return existing.isEmpty() ? persistRole(code) : existing.get(0);
    }

    private UserEntity persistUser(RoleEntity role) {
        UserEntity u = new UserEntity();
        u.setName("PM user " + unique());
        u.setEmail("pm+" + unique() + "@example.com");
        u.setRole(role);
        u.setActive(true);
        u.setStatus(UserStatus.INVITED);
        u.setLocale(Locale.forLanguageTag("ru"));
        entityManager.persist(u);
        entityManager.flush();
        return u;
    }

    private void assignMember(UserEntity user, Long projectId, RoleEntity projectRole) {
        ProjectMemberEntity pm = new ProjectMemberEntity();
        pm.setUser(user);
        pm.setProjectId(projectId);
        pm.setProjectRole(projectRole);
        entityManager.persist(pm);
        entityManager.flush();
    }

    private void authenticateAs(Long userId, String authority) {
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(userId), "n/a", List.of(new SimpleGrantedAuthority(authority))));
        SecurityContextHolder.setContext(ctx);
    }
}
