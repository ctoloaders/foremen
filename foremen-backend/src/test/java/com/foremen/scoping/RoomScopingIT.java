package com.foremen.scoping;

import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.RoomTypeDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.RoomService;
import com.foremen.service.model.RoomServiceExtendedModel;
import com.foremen.service.model.RoomServiceModel;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * End-to-end integration test proving the FOR-04-14 {@code Room} entity behaves as a
 * <b>project-scoped child</b> ({@link RoomService#getProjectIdPath()} returns the dotted
 * association path {@code "project.id"}), exercising the real {@link RoomService} against a
 * Testcontainers PostgreSQL database.
 *
 * <p>Because a room resolves its project boundary through its owning {@code project}, the inherited
 * membership filter and by-id membership assertion resolve against {@code rooms.project_id}: a
 * non-ADMIN caller sees exactly the rooms whose owning project a {@code project_members} row joins
 * them to, ADMIN bypasses the filter and sees every room, and a non-member by-id
 * read/update/delete is denied with the {@code Access_Denied_Outcome} (404
 * {@code error.entity.not.found}, deliberately indistinguishable from a missing row).
 *
 * <p>Coverage:
 * <ul>
 *     <li>non-ADMIN LIST is membership-filtered — {@link RoomService#find(Pageable, String)} returns
 *         only rooms whose owning project the caller is a member of and excludes the rest
 *         (Requirements 7.5, 5.3);</li>
 *     <li>ADMIN LIST bypass — {@code find} returns every seeded room (Requirement 5.4);</li>
 *     <li>non-member by-id read/update/delete are denied with 404 {@code error.entity.not.found}
 *         (Requirement 5.5), and the update/delete denials persist no change.</li>
 * </ul>
 *
 * <p>Mirrors the container/profile setup of {@link ProjectScopingIT}: Hibernate {@code create-drop}
 * builds the schema under {@code @ActiveProfiles("integration-test")}, real {@code project_members}
 * rows are seeded so the {@link ProjectAccessCache} self-loads genuine memberships, and the
 * principal name is the seeded user's numeric id (mirroring how the JWT layer populates the
 * {@code SecurityContext}). Every row is seeded under a unique {@code run-id} suffix and the
 * {@link ProjectAccessCache} entry is invalidated per test, so the suite re-runs without manual
 * clean-up.
 *
 * <p>Validates: Requirements 7.5, 5.3, 5.4, 5.5
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("integration-test")
@Transactional
class RoomScopingIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test")
            // stringtype=unspecified lets PostgreSQL implicitly cast the JSON text values (e.g. the
            // rooms.geometry jsonb column and the UserEntity display_preferences) matching the app.
            .withUrlParam("stringtype", "unspecified");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    private static final Pageable PAGE = PageRequest.of(0, 100);

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomDao roomDao;

    @Autowired
    private RoomTypeDao roomTypeDao;

    @Autowired
    private ProjectDao projectDao;

    @Autowired
    private ProjectMemberDao projectMemberDao;

    @Autowired
    private ProjectAccessCache projectAccessCache;

    @Autowired
    private UserDao userDao;

    @Autowired
    private RoleDao roleDao;

    /** Unique run identifier so seeded rows never collide across repeated runs. */
    private String runId;
    private RoleEntity role;
    private RoomTypeEntity roomType;

    /** Three distinct projects: the non-ADMIN caller is a member of A and B, but NOT C. */
    private Long projectA;
    private Long projectB;
    private Long projectC;

    /** One room in each project, so the LIST filter is proven per owning project. */
    private Long roomA;
    private Long roomB;
    private Long roomC;

    @BeforeEach
    void setUp() {
        runId = String.valueOf(System.nanoTime());
        role = seedRole();
        roomType = seedRoomType();

        projectA = seedProject("A-" + runId);
        projectB = seedProject("B-" + runId);
        projectC = seedProject("C-" + runId);

        roomA = seedRoom(projectA, "room-A-" + runId);
        roomB = seedRoom(projectB, "room-B-" + runId);
        roomC = seedRoom(projectC, "room-C-" + runId);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ----------------------------------------------------------------------
    // LIST filtering (Requirements 7.5, 5.3, 5.4)
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("LIST: non-ADMIN caller sees exactly the rooms whose project they are a member of (7.5, 5.3)")
    void listNonAdminReturnsOnlyMemberRooms() {
        UserEntity user = seedUser();
        // Member of project A and B only.
        saveMembership(user, projectA);
        saveMembership(user, projectB);
        projectAccessCache.invalidate(user.getId());
        authenticate(user.getId(), "ROLE_FOREMAN");

        Page<RoomServiceModel> page = roomService.find(PAGE, null);

        assertThat(page.getContent())
                .extracting(RoomServiceModel::getId)
                .containsExactlyInAnyOrder(roomA, roomB)
                .doesNotContain(roomC);
    }

    @Test
    @DisplayName("LIST: non-ADMIN caller with no memberships sees no rooms (7.5)")
    void listNonAdminWithoutMembershipsExcludesAll() {
        UserEntity user = seedUser();
        projectAccessCache.invalidate(user.getId());
        authenticate(user.getId(), "ROLE_FOREMAN");

        Page<RoomServiceModel> page = roomService.find(PAGE, null);

        assertThat(page.getContent())
                .extracting(RoomServiceModel::getId)
                .doesNotContain(roomA, roomB, roomC);
    }

    @Test
    @DisplayName("LIST: ADMIN caller bypasses membership filtering and sees all rooms (5.4)")
    void listAdminSeesAllRooms() {
        UserEntity admin = seedUser();
        authenticate(admin.getId(), "ROLE_" + ForemenPermissionEvaluator.ADMIN_ROLE_CODE);

        Page<RoomServiceModel> page = roomService.find(PAGE, null);

        assertThat(page.getContent())
                .extracting(RoomServiceModel::getId)
                .contains(roomA, roomB, roomC);
    }

    // ----------------------------------------------------------------------
    // By-id denial for non-members (Requirement 5.5)
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("READ by id: non-member denied with 404 error.entity.not.found (5.5)")
    void readByIdNonMemberDenied() {
        UserEntity user = seedUser();
        saveMembership(user, projectA);
        projectAccessCache.invalidate(user.getId());
        authenticate(user.getId(), "ROLE_FOREMAN");

        // Member of A can read the room in A.
        assertThat(roomService.findById(roomA).getId()).isEqualTo(roomA);

        // Non-member of C is denied — and the denial reveals nothing about the room in C.
        assertDenied(() -> roomService.findById(roomC));
    }

    @Test
    @DisplayName("UPDATE by id: non-member denied with 404 and no change persisted (5.5)")
    void updateByIdNonMemberDenied() {
        UserEntity user = seedUser();
        saveMembership(user, projectA);
        projectAccessCache.invalidate(user.getId());
        authenticate(user.getId(), "ROLE_FOREMAN");

        assertDenied(() -> roomService.update(roomC, modelWithLabel(projectC, "hacked-" + runId)));

        // The out-of-scope room's label is unchanged (the check throws before any write).
        assertThat(roomDao.findById(roomC).orElseThrow().getLabel()).isEqualTo("room-C-" + runId);
    }

    @Test
    @DisplayName("DELETE by id: non-member denied with 404 and row still present (5.5)")
    void deleteByIdNonMemberDenied() {
        UserEntity user = seedUser();
        saveMembership(user, projectA);
        projectAccessCache.invalidate(user.getId());
        authenticate(user.getId(), "ROLE_FOREMAN");

        assertDenied(() -> roomService.deleteById(roomC));

        // The out-of-scope room still exists (the check throws before the delete reaches the DAO).
        assertThat(roomDao.findById(roomC)).isPresent();
    }

    // --- Assertion helpers ---

    /** Asserts the action throws the Access_Denied_Outcome: 404 with message code error.entity.not.found. */
    private void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        ForemenApiException ex = catchThrowableOfType(action, ForemenApiException.class);
        assertThat(ex).as("expected a ForemenApiException to be thrown").isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getMessageCode()).isEqualTo("error.entity.not.found");
    }

    // --- Seeding / auth helpers (unique per run via runId) ---

    private void authenticate(Long userId, String authority) {
        SecurityContextHolder.clearContext();
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                String.valueOf(userId), "n/a", List.of(new SimpleGrantedAuthority(authority)));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private RoleEntity seedRole() {
        RoleEntity r = new RoleEntity();
        r.setCode("ROOM-SCOPING-ROLE-" + runId);
        r.setNameRU("Роль");
        r.setNamePL("Rola");
        r.setSystem(false);
        return roleDao.save(r);
    }

    private RoomTypeEntity seedRoomType() {
        RoomTypeEntity rt = new RoomTypeEntity();
        rt.setCode("ROOM-SCOPING-TYPE-" + runId);
        rt.setNameRU("Тип");
        rt.setNamePL("Typ");
        rt.setActive(true);
        return roomTypeDao.save(rt);
    }

    private UserEntity seedUser() {
        UserEntity u = new UserEntity();
        u.setName("Room Scoping Test User");
        u.setEmail("room-scoping+" + System.nanoTime() + "@example.com");
        u.setRole(role);
        return userDao.save(u);
    }

    private Long seedProject(String name) {
        ProjectEntity project = new ProjectEntity();
        project.setName(name);
        project.setStatus(ProjectStatus.DRAFT);
        return projectDao.save(project).getId();
    }

    /** Persists a room (manual metrics) in the given project so the LIST filter is proven per project. */
    private Long seedRoom(Long projectId, String label) {
        RoomEntity room = new RoomEntity();
        room.setProject(projectDao.findById(projectId).orElseThrow());
        room.setRoomType(roomType);
        room.setLabel(label);
        room.setFloorArea(new BigDecimal("10.00"));
        return roomDao.save(room).getId();
    }

    private void saveMembership(UserEntity user, Long projectId) {
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setUser(user);
        member.setProjectId(projectId);
        member.setProjectRole(role);
        projectMemberDao.save(member);
    }

    /**
     * A minimal update model targeting a room in {@code projectId} with a new {@code label} and no
     * geometry (manual path). Used only for the non-member update-denial test, where the guard
     * throws before the model is ever applied.
     */
    private RoomServiceExtendedModel modelWithLabel(Long projectId, String label) {
        RoomServiceExtendedModel model = new RoomServiceExtendedModel();
        model.setProjectId(projectId);
        model.setRoomTypeId(roomType.getId());
        model.setLabel(label);
        return model;
    }
}
