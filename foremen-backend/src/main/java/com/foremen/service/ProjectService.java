package com.foremen.service;

import com.foremen.controller.model.ClientBlock;
import com.foremen.controller.model.ClientRegistrationRequest;
import com.foremen.controller.model.CreateProjectRequest;
import com.foremen.controller.model.NewClientInput;
import com.foremen.controller.model.PlaceDetailsDto;
import com.foremen.controller.model.ProjectCreateResponse;
import com.foremen.controller.model.ProjectListDto;
import com.foremen.controller.model.ProjectMemberInput;
import com.foremen.controller.model.ProjectMemberSummaryDto;
import com.foremen.controller.model.ProjectReadDto;
import com.foremen.dao.AdminDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkerTypeEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.google.GooglePlacesService;
import com.foremen.service.model.ProjectServiceExtendedModel;
import com.foremen.service.model.ProjectServiceModel;
import com.foremen.service.model.mapper.ProjectServiceMapper;
import jakarta.persistence.EntityManager;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Project-scoped anchor service for {@link ProjectEntity} (FOR-04-13).
 *
 * <p>It implements exactly one CRUD contract — {@link ProjectScopedService} — and never a plain
 * {@link AdminService} and never both. Because {@link ProjectScopedService} extends
 * {@link AdminService}, this service supplies the standard CRUD plumbing ({@link #getDao()},
 * {@link #getMapper()}, {@link #getEntityManager()}, {@link #getDaoModelClass()},
 * {@link #getAuditLogDao()}), the single mandatory per-entity override
 * {@link #getProjectIdPath()}, and wires {@link #allowedProjectIds(Long)} to
 * {@link ProjectAccessCache#get(Long)}.
 *
 * <p><b>Anchor.</b> {@code Project} is the project-scoped anchor of the FOR-04 series, so
 * {@link #getProjectIdPath()} returns {@code "id"} — the project's own id. The inherited list
 * filter therefore restricts non-ADMIN LIST reads to the caller's {@code project_members} rows and
 * by-id reads/writes assert membership; ADMIN bypasses both (Requirement 4.1).
 *
 * <p><b>Generic create disabled.</b> Projects are created only through the custom transactional
 * {@code POST /api/projects} orchestrator; the inherited generic {@code AdminService.create}
 * (single and bulk) is overridden to reject the request with a client-error
 * {@link ForemenApiException} before any persistence, so even bulk-create is refused
 * (Requirement 3.1). Generic list/read/update/delete remain available and project-scoped
 * (Requirement 3.2).
 */
@Service
public class ProjectService
        implements ProjectScopedService<ProjectServiceModel, ProjectServiceExtendedModel, ProjectEntity, Long> {

    /** Message code returned when generic create is invoked (Requirement 3.1). */
    static final String CREATE_UNSUPPORTED_MESSAGE = "error.project.create.unsupported";

    /** Message code returned when {@code endDate} is earlier than {@code startDate} (Requirement 3.6). */
    static final String DATE_RANGE_MESSAGE = "error.project.date.range";

    /** The server-resolved project role {@code code} always used for the client member (Requirements 2.7, 2.8). */
    static final String CLIENT_ROLE_CODE = "CLIENT";

    /** The WORKER project-role code; worker-type/NIP Internal_Attributes apply to WORKER members only (Requirement 19 criterion 8). */
    static final String WORKER_ROLE_CODE = "WORKER";

    /** Message code returned when the {@code CLIENT} role is not seeded (Requirement 2.8, Error Handling table). */
    static final String CLIENT_ROLE_MISSING_MESSAGE = "error.role.client.missing";

    private final ProjectDao projectDao;
    private final ProjectServiceMapper projectServiceMapper;
    private final ProjectAccessCache projectAccessCache;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final ProjectMemberService projectMemberService;
    private final ClientRegistrationService clientRegistrationService;
    private final RoleDao roleDao;
    private final GooglePlacesService googlePlacesService;

    public ProjectService(ProjectDao projectDao,
                          ProjectServiceMapper projectServiceMapper,
                          ProjectAccessCache projectAccessCache,
                          AuditLogDao auditLogDao,
                          EntityManager entityManager,
                          ProjectMemberService projectMemberService,
                          ClientRegistrationService clientRegistrationService,
                          RoleDao roleDao,
                          GooglePlacesService googlePlacesService) {
        this.projectDao = projectDao;
        this.projectServiceMapper = projectServiceMapper;
        this.projectAccessCache = projectAccessCache;
        this.auditLogDao = auditLogDao;
        this.entityManager = entityManager;
        this.projectMemberService = projectMemberService;
        this.clientRegistrationService = clientRegistrationService;
        this.roleDao = roleDao;
        this.googlePlacesService = googlePlacesService;
    }

    // --- CRUD plumbing (inherited from AdminService via ProjectScopedService) ---

    @Override
    public AdminDao<ProjectEntity, Long> getDao() {
        return projectDao;
    }

    @Override
    public AuditLogDao getAuditLogDao() {
        return auditLogDao;
    }

    @Override
    public ServiceToDaoMapper<ProjectEntity, ProjectServiceModel, ProjectServiceExtendedModel> getMapper() {
        return projectServiceMapper;
    }

    @Override
    public EntityManager getEntityManager() {
        return entityManager;
    }

    @Override
    public Class<ProjectEntity> getDaoModelClass() {
        return ProjectEntity.class;
    }

    // --- ProjectScopedService overrides ---

    /**
     * The single mandatory per-entity override. The project IS the anchor, so the project-id path is
     * the project's own {@code id} (Requirement 4.1).
     */
    @Override
    public String getProjectIdPath() {
        return "id";
    }

    /** Wires the allowed-project-ids lookup to the production cache. */
    @Override
    public Set<Long> allowedProjectIds(Long userId) {
        return projectAccessCache.get(userId);
    }

    // --- Generic create disabled (Requirement 3.1) ---

    /**
     * Generic single create is unsupported: projects are created only through the custom
     * transactional endpoint. Always throws before any persistence.
     */
    @Override
    public ProjectServiceExtendedModel create(ProjectServiceExtendedModel model) {
        throw createUnsupported();
    }

    /**
     * Generic bulk create is unsupported for the same reason as the single-create override; refused
     * before any persistence.
     */
    @Override
    public List<ProjectServiceExtendedModel> create(List<ProjectServiceExtendedModel> models) {
        throw createUnsupported();
    }

    private static ForemenApiException createUnsupported() {
        return new ForemenApiException(HttpStatus.BAD_REQUEST, CREATE_UNSUPPORTED_MESSAGE);
    }

    // --- Service-layer base-field guards (Requirements 3.5, 3.6, 8.8) ---

    /**
     * Reusable date-range guard invoked by the custom create orchestrator (task 3.5) and the update
     * path before persistence. When both {@code startDate} and {@code endDate} are supplied and
     * {@code endDate} is strictly earlier than {@code startDate}, it rejects the request with a
     * client-error {@link ForemenApiException} carrying HTTP 400 and message code
     * {@link #DATE_RANGE_MESSAGE} ({@code error.project.date.range}), so nothing is persisted
     * (Requirements 3.6, 8.8).
     *
     * <p>The rule is a cross-field invariant that bean validation on the request records cannot
     * express, so it lives here rather than as a field annotation. When either date is {@code null}
     * (open-ended range) the guard is a no-op — a single supplied bound is always valid.
     *
     * <p>The complementary single-field base validations — {@code name} non-blank 1..255,
     * {@code area} within 0.01..999999999.99, and {@code status} being one of the defined
     * {@link ProjectStatus} values — are enforced by bean validation on {@code CreateProjectRequest}
     * / {@code ProjectUpdateRequest} (the {@code @NotBlank}/{@code @Size}/{@code @DecimalMin}/
     * {@code @DecimalMax} constraints and the enum binding). Those reject an invalid value with a
     * field-identifying client error before the service is reached, so no additional service-layer
     * guard is needed for them (Requirement 3.5).
     *
     * @param startDate the project start date, or {@code null} for an open start
     * @param endDate   the project end date, or {@code null} for an open end
     * @throws ForemenApiException HTTP 400 {@code error.project.date.range} when both dates are
     *                             present and {@code endDate} precedes {@code startDate}
     */
    public void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, DATE_RANGE_MESSAGE);
        }
    }

    // --- Custom transactional create (Requirement 2) ---

    /**
     * Custom transactional project-creation orchestrator invoked by
     * {@code ProjectController.createProject} (Requirements 2.1, 2.10). Creates the project, assigns
     * each team member, and processes the optional client block in a single transaction; any step
     * failure rolls the whole unit back (Requirements 2.6, 2.9).
     *
     * <p><b>Orchestration steps</b> (all inside one {@link Transactional} unit, Requirements 2.6,
     * 2.9):
     * <ol>
     *   <li>Validate the date range ({@code endDate} not before {@code startDate}); single-field
     *       base validation ({@code name}, {@code area}, {@code status}) is enforced by bean
     *       validation on {@link CreateProjectRequest} before the service is reached (Requirement 3.5).</li>
     *   <li>Resolve the {@code googlePlaceId} details when a place id is supplied but no
     *       {@code formattedAddress}/{@code latitude}/{@code longitude} were provided (Requirement 5.7).</li>
     *   <li>Build and persist the {@link ProjectEntity}, defaulting {@code status} to {@code DRAFT}
     *       when the request omits it (Requirement 2.3).</li>
     *   <li>Assign each {@code members[i]} via
     *       {@link ProjectMemberService#assign(Long, Long, Long)} (Requirement 2.6).</li>
     *   <li>Process the optional {@code client} block: an {@code existingClientUserId} is assigned
     *       under the server-resolved {@code CLIENT} role (Requirement 2.7); a {@code newClient}
     *       reuses the FOR-03-05 {@link ClientRegistrationService#register} flow, which creates an
     *       INVITED CLIENT user, dispatches the invitation email, and assigns membership under the
     *       server-resolved {@code CLIENT} role (Requirement 2.8). No caller-supplied field can
     *       change the client's project role.</li>
     *   <li>Return the persisted project projection with its generated id, persisted {@code status},
     *       the assigned {@code members}, and the derived {@code client} (Requirement 2.10).</li>
     * </ol>
     *
     * <p>Any exception thrown at any step propagates so the {@link Transactional} boundary rolls the
     * whole unit back: no project row, no {@code project_members} row, and no client user remains
     * (Requirement 2.9). {@link ProjectMemberService} and the client-registration flow surface their
     * own errors per the Error Handling table (unknown user 404, unknown role 404, duplicate
     * membership 409, duplicate client email 409, CLIENT role missing 500).
     *
     * @param request the validated custom-create request
     * @return the created project projection (id, status, members, derived client)
     */
    @Transactional
    public ProjectCreateResponse createProject(CreateProjectRequest request) {
        // 1. Cross-field validation (single-field base validation is bean-validated on the request).
        validateDateRange(request.startDate(), request.endDate());

        // 2. Resolve Google Places details when a place id is supplied but the caller provided none
        //    of the derived fields (Requirement 5.7).
        String formattedAddress = request.formattedAddress();
        var latitude = request.latitude();
        var longitude = request.longitude();
        if (request.googlePlaceId() != null && !request.googlePlaceId().isBlank()
                && formattedAddress == null && latitude == null && longitude == null) {
            PlaceDetailsDto details = googlePlacesService.resolveDetails(request.googlePlaceId());
            if (details != null) {
                formattedAddress = details.formattedAddress();
                latitude = details.latitude();
                longitude = details.longitude();
            }
        }

        // 3. Build and persist the project, defaulting status to DRAFT when null (Requirement 2.3).
        ProjectEntity project = new ProjectEntity();
        project.setName(request.name());
        project.setAddress(request.address());
        project.setGooglePlaceId(request.googlePlaceId());
        project.setFormattedAddress(formattedAddress);
        project.setLatitude(latitude);
        project.setLongitude(longitude);
        project.setArea(request.area());
        project.setStartDate(request.startDate());
        project.setEndDate(request.endDate());
        project.setStatus(request.status() != null ? request.status() : ProjectStatus.DRAFT);

        ProjectEntity saved = projectDao.save(project);
        Long projectId = saved.getId();

        // 3a. Write the CREATE audit row within the same @Transactional boundary (FOR-04-bugs Bug 6,
        //     Requirement 2.6). The custom create orchestrator bypasses AdminService.create, so it
        //     must invoke the inherited saveAudit contract itself — otherwise no CREATE audit row is
        //     written (unlike generic entities and rooms, which AdminService audits). The base
        //     project entity is the audited subject (entityClass "ProjectEntity", the generated
        //     entityId, performedBy, snapshotAfter); members/client assignment produce their own
        //     audit rows via their services and are out of scope for this row (Requirement 3.7).
        saveAudit(null, saved, "CREATE");

        // 4. Assign each team member (Requirement 2.6). Failures (unknown user/role, duplicate
        //    membership) propagate and roll back the whole transaction (Requirement 2.9).
        List<ProjectMemberSummaryDto> memberSummaries = new ArrayList<>();
        List<ProjectMemberInput> members = request.members();
        if (members != null) {
            for (ProjectMemberInput member : members) {
                // FOR-05-09 (task 15.1, Requirement 26): the creation-restricted assign rejects a
                // Worker_Role entry (error.project.member.role.not.allowed.at.creation), any non-null
                // workerTypeId (error.project.member.worker.type.not.allowed), an ADMIN / non-system
                // role (error.project.member.role.not.assignable), a projectRoleId != Company_Role
                // (error.project.member.role.mismatch), and a repeated userId
                // (error.project.member.duplicate), in the per-entry order of Requirement 26 criterion
                // 5; a Client_Role entry for a CLIENT user stays accepted (criterion 4). Any rejection
                // propagates and the @Transactional boundary rolls the whole creation back (criterion
                // 2, 5).
                ProjectMemberEntity assigned = projectMemberService.assignAtCreation(
                        member.userId(), projectId, member.projectRoleId(), member.workerTypeId());
                memberSummaries.add(toSummary(assigned));
            }
        }

        // 5. Process the optional client block under the server-resolved CLIENT role (Requirements
        //    2.7, 2.8). The client's role is never taken from the request.
        ProjectMemberSummaryDto clientSummary = processClient(request.client(), projectId);
        if (clientSummary != null) {
            memberSummaries.add(clientSummary);
        }

        // 6. Return the persisted projection (Requirement 2.10).
        return new ProjectCreateResponse(
                saved.getId(),
                saved.getName(),
                saved.getAddress(),
                saved.getGooglePlaceId(),
                saved.getFormattedAddress(),
                saved.getLatitude(),
                saved.getLongitude(),
                saved.getArea(),
                saved.getStartDate(),
                saved.getEndDate(),
                saved.getStatus(),
                memberSummaries,
                clientSummary);
    }

    // --- List/read projection (Requirement 3.4, design Change 2/3) ---

    /**
     * Projects a page of projects into {@link ProjectListDto}s carrying the {@code members}
     * collection and the derived {@code client} (task 7.1). Delegates the base-field query to the
     * inherited generic {@link #find(Pageable, String)} — so all access filtering (project-scoped
     * membership for non-ADMIN, ADMIN bypass), sorting, and paging are unchanged — then hydrates the
     * team for the whole page in <strong>one</strong> extra batched query.
     *
     * <p><b>No N+1.</b> After the base page is resolved, the page's project entities are re-loaded in
     * a single {@code findAllByIdIn(ids)} call; touching each entity's {@link ProjectEntity#getMembers()}
     * collection then triggers Hibernate's {@code @BatchSize(size=100)} batch fetch, so every row's
     * members (and their {@code user}/{@code projectRole}) are hydrated by one bounded query rather
     * than one query per row. The whole method runs in a read-only transaction so the lazy
     * collections initialize inside an open session.
     *
     * @param pageable the page request (already sort-processed by the inherited find)
     * @param query    the raw filter query, or {@code null}
     * @return a page of list DTOs with {@code members} + derived {@code client}, preserving the base
     *         page's order and pagination metadata
     */
    @Transactional(readOnly = true)
    public Page<ProjectListDto> findProjected(Pageable pageable, String query) {
        Page<ProjectServiceModel> base = find(pageable, query);
        Map<Long, ProjectEntity> entities = hydrateMembers(base.map(ProjectServiceModel::getId).getContent());
        return base.map(model -> toListDto(model, entities.get(model.getId())));
    }

    /**
     * Projects a single project into a {@link ProjectReadDto} carrying the {@code members} collection
     * and the derived {@code client} (task 7.1). Reuses the inherited {@link #findById(Object)} for
     * the base fields and the by-id membership assertion, then reads the read-only {@code members}
     * collection off the already-loaded entity (the {@code @BatchSize} collection initializes inside
     * this read-only transaction — a single row is one bounded member fetch, no N+1).
     *
     * @param id the project id
     * @return the read DTO with {@code members} + derived {@code client}
     */
    @Transactional(readOnly = true)
    public ProjectReadDto findByIdProjected(Long id) {
        ProjectServiceExtendedModel base = findById(id);
        ProjectEntity entity = getDao().findById(id).orElse(null);
        return toReadDto(base, entity);
    }

    /**
     * Batch-loads the given project ids and returns them keyed by id. Loading the entities here (and
     * traversing their {@code members} collection during projection) lets the {@code @BatchSize}
     * collection hydrate every row's team in one bounded query instead of one per row.
     */
    private Map<Long, ProjectEntity> hydrateMembers(List<Long> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, ProjectEntity> byId = new LinkedHashMap<>();
        for (ProjectEntity entity : getDao().findAllByIdIn(projectIds)) {
            byId.put(entity.getId(), entity);
        }
        return byId;
    }

    /**
     * Builds a {@link ProjectListDto} from the base model plus the entity's hydrated members,
     * applying the FOR-05-09 Requirement 19 multi-client projection and CLIENT-caller suppression.
     */
    private ProjectListDto toListDto(ProjectServiceModel model, ProjectEntity entity) {
        boolean clientCaller = isCallerClient();
        List<ProjectMemberSummaryDto> members = clientCaller ? null : projectMembers(entity);
        List<ProjectMemberSummaryDto> clients = clientCaller ? null : deriveClients(entity);
        return new ProjectListDto(
                model.getId(),
                model.getName(),
                model.getAddress(),
                model.getGooglePlaceId(),
                model.getFormattedAddress(),
                model.getLatitude(),
                model.getLongitude(),
                model.getArea(),
                model.getStartDate(),
                model.getEndDate(),
                model.getStatus(),
                members,
                clients,
                firstOrNull(clients));
    }

    /**
     * Builds a {@link ProjectReadDto} from the base model plus the entity's hydrated members,
     * applying the FOR-05-09 Requirement 19 multi-client projection and CLIENT-caller suppression.
     */
    private ProjectReadDto toReadDto(ProjectServiceExtendedModel model, ProjectEntity entity) {
        boolean clientCaller = isCallerClient();
        List<ProjectMemberSummaryDto> members = clientCaller ? null : projectMembers(entity);
        List<ProjectMemberSummaryDto> clients = clientCaller ? null : deriveClients(entity);
        return new ProjectReadDto(
                model.id(),
                model.name(),
                model.address(),
                model.googlePlaceId(),
                model.formattedAddress(),
                model.latitude(),
                model.longitude(),
                model.area(),
                model.startDate(),
                model.endDate(),
                model.status(),
                members,
                clients,
                firstOrNull(clients));
    }

    /**
     * Maps a project entity's read-only {@code members} collection into member summaries, reusing
     * the {@link #toSummary(ProjectMemberEntity)} helper (which applies the admin-staff masking of
     * Requirement 19 criterion 8). Returns an empty list when the project has no members.
     */
    private List<ProjectMemberSummaryDto> projectMembers(ProjectEntity entity) {
        if (entity == null || entity.getMembers() == null) {
            return List.of();
        }
        boolean adminStaff = isCallerAdminStaff();
        boolean russian = isRussianLocale();
        List<ProjectMemberSummaryDto> summaries = new ArrayList<>();
        for (ProjectMemberEntity member : entity.getMembers()) {
            summaries.add(toSummary(member, russian, adminStaff));
        }
        return summaries;
    }

    /**
     * Derives the {@code clients} projection (Requirement 19 criteria 1–3): one summary per
     * Project_Member whose Project_Role code is {@code CLIENT}, ordered by membership id ascending,
     * including CLIENT members whose user is INVITED/inactive or whose Assignment_Status is INACTIVE.
     * Returns an empty list (never {@code null} for a non-CLIENT caller) when the project has no
     * CLIENT member. Each entry carries the same admin-staff masking as {@link #projectMembers}.
     */
    private List<ProjectMemberSummaryDto> deriveClients(ProjectEntity entity) {
        if (entity == null || entity.getMembers() == null) {
            return List.of();
        }
        boolean adminStaff = isCallerAdminStaff();
        boolean russian = isRussianLocale();
        return entity.getMembers().stream()
                .filter(m -> {
                    RoleEntity role = m.getProjectRole();
                    return role != null && CLIENT_ROLE_CODE.equals(role.getCode());
                })
                .sorted(java.util.Comparator.comparing(
                        ProjectMemberEntity::getId,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .map(m -> toSummary(m, russian, adminStaff))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /**
     * The derived {@code client}: the lowest-membership-id CLIENT member = {@code clients[0]}
     * (Requirement 19 criterion 3), or {@code null} when the project has no CLIENT member.
     */
    private static ProjectMemberSummaryDto firstOrNull(List<ProjectMemberSummaryDto> clients) {
        return clients == null || clients.isEmpty() ? null : clients.get(0);
    }

    /**
     * Processes the optional client block, assigning the client member under the server-resolved
     * {@code CLIENT} role. Returns the client's member summary, or {@code null} when no client block
     * is supplied (or it carries neither branch).
     */
    private ProjectMemberSummaryDto processClient(ClientBlock client, Long projectId) {
        if (client == null) {
            return null;
        }

        if (client.existingClientUserId() != null) {
            // Existing CLIENT user: assign under the server-resolved CLIENT role (Requirement 2.7).
            RoleEntity clientRole = resolveClientRole();
            ProjectMemberEntity assigned =
                    projectMemberService.assign(client.existingClientUserId(), projectId, clientRole.getId());
            return toSummary(assigned);
        }

        NewClientInput newClient = client.newClient();
        if (newClient != null) {
            // New client: reuse the FOR-03-05 flow — creates an INVITED CLIENT user, dispatches the
            // invitation email, and assigns membership under the server-resolved CLIENT role
            // (Requirement 2.8). The role is fixed to CLIENT server-side by that flow.
            clientRegistrationService.register(new ClientRegistrationRequest(
                    newClient.name(),
                    newClient.email(),
                    newClient.phone(),
                    newClient.locale(),
                    projectId,
                    null)); // no tags from the project-creation client path (FOR-05-09 task 14.1)
            RoleEntity clientRole = resolveClientRole();
            boolean russian = isRussianLocale();
            boolean adminStaff = isCallerAdminStaff();
            return new ProjectMemberSummaryDto(
                    null,
                    newClient.name(),
                    clientRole.getCode(),
                    localizedRoleName(clientRole, russian),
                    com.foremen.dao.model.AssignmentStatus.ACTIVE,
                    null,
                    null,
                    null,
                    null,
                    null,
                    adminStaff ? List.of() : null);
        }

        return null;
    }

    /** Resolves the seeded {@code CLIENT} role server-side (Requirements 2.7, 2.8). */
    private RoleEntity resolveClientRole() {
        return roleDao.findByCode(CLIENT_ROLE_CODE)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.INTERNAL_SERVER_ERROR, CLIENT_ROLE_MISSING_MESSAGE));
    }

    /**
     * Builds a member summary DTO from a persisted membership row for the create/update response
     * projection, resolved in the request locale with the current caller's admin-staff masking.
     */
    private ProjectMemberSummaryDto toSummary(ProjectMemberEntity member) {
        return toSummary(member, isRussianLocale(), isCallerAdminStaff());
    }

    /**
     * Builds a {@link ProjectMemberSummaryDto} from a persisted membership row (FOR-05-09
     * Requirement 19). The base fields ({@code userId}, {@code userName}, {@code roleCode},
     * {@code roleName}) and the never-internal {@code assignmentStatus} are always populated. The
     * Internal_Attributes (worker type id/code/name/active, NIP, tags) are populated only for a
     * WORKER member and only when {@code adminStaff} is {@code true}; otherwise they are left
     * {@code null}/omitted (Requirement 19 criterion 8, D12 admin-staff masking). {@code tags} is
     * copied as an immutable list for an admin-staff reader (empty when none), and nulled — hence
     * omitted by {@code @JsonInclude(NON_NULL)} — for a non-admin-staff reader.
     */
    private ProjectMemberSummaryDto toSummary(ProjectMemberEntity member, boolean russian, boolean adminStaff) {
        UserEntity user = member.getUser();
        RoleEntity role = member.getProjectRole();
        String roleCode = role == null ? null : role.getCode();

        boolean worker = roleCode != null && WORKER_ROLE_CODE.equals(roleCode);

        Long workerTypeId = null;
        String workerTypeCode = null;
        String workerTypeName = null;
        Boolean workerTypeActive = null;
        String nip = null;
        List<String> tags = null;

        if (adminStaff) {
            WorkerTypeEntity type = member.getWorkerType();
            if (type != null) {
                workerTypeId = type.getId();
                workerTypeCode = type.getCode();
                workerTypeName = localizedWorkerTypeName(type, russian);
                workerTypeActive = type.isActive();
            }
            if (worker && user != null) {
                nip = user.getNip();
            }
            tags = member.getTags() == null ? List.of() : List.copyOf(member.getTags());
        }

        return new ProjectMemberSummaryDto(
                user != null ? user.getId() : null,
                user != null ? user.getName() : null,
                roleCode,
                role != null ? localizedRoleName(role, russian) : null,
                member.getAssignmentStatus(),
                workerTypeId,
                workerTypeCode,
                workerTypeName,
                workerTypeActive,
                nip,
                tags);
    }

    /**
     * The localized Project_Role name: {@code nameRU} for a {@code ru} request, {@code namePL}
     * otherwise, matching the codebase RU/PL convention. (Masking never applies to the role name.)
     */
    private static String localizedRoleName(RoleEntity role, boolean russian) {
        return russian ? role.getNameRU() : role.getNamePL();
    }

    /**
     * The localized Worker_Type name: {@code nameRU} for a {@code ru} request, {@code namePL}
     * otherwise, falling back to the worker-type {@code code} when the chosen name is blank.
     */
    private static String localizedWorkerTypeName(WorkerTypeEntity type, boolean russian) {
        String name = russian ? type.getNameRU() : type.getNamePL();
        if (name == null || name.isBlank()) {
            return type.getCode();
        }
        return name;
    }

    /** True iff the request locale language is {@code ru} (case-insensitive). */
    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }

    /**
     * True iff the authenticated caller's Company_Role is {@code CLIENT}. A CLIENT caller has the
     * {@code members}, {@code clients}, and {@code client} projections suppressed (Requirement 19
     * criterion 9). False for an unauthenticated caller and for every non-CLIENT role.
     */
    private static boolean isCallerClient() {
        return callerHasRole(CLIENT_ROLE_CODE);
    }

    /**
     * True iff the authenticated caller is an Internal_Attribute_Viewer — ADMIN or any admin-staff
     * role (MANAGER / FOREMAN / ESTIMATOR / FINANCIER) — the gate for the Requirement 19 criterion 8
     * admin-staff masking (D12), shared with {@link ReadOnlyAdminService#ADMIN_STAFF_ROLE_CODES}.
     * False for WORKER / CLIENT and for an unauthenticated caller.
     */
    private static boolean isCallerAdminStaff() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String authority = ga.getAuthority();
            if (authority == null) {
                continue;
            }
            String code = authority.startsWith("ROLE_") ? authority.substring("ROLE_".length()) : authority;
            if (ReadOnlyAdminService.ADMIN_STAFF_ROLE_CODES.contains(code)) {
                return true;
            }
        }
        return false;
    }

    /** True iff the authenticated caller carries the given role authority ({@code ROLE_<code>}/{@code <code>}). */
    private static boolean callerHasRole(String roleCode) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String authority = ga.getAuthority();
            if (authority == null) {
                continue;
            }
            String code = authority.startsWith("ROLE_") ? authority.substring("ROLE_".length()) : authority;
            if (roleCode.equals(code)) {
                return true;
            }
        }
        return false;
    }
}
