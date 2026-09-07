package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.CreateProjectRequest;
import com.foremen.controller.model.ProjectCreateResponse;
import com.foremen.controller.model.ProjectListDto;
import com.foremen.controller.model.ProjectReadDto;
import com.foremen.controller.model.ProjectUpdateRequest;
import com.foremen.controller.model.ProjectUpdateResponse;
import com.foremen.controller.model.mapper.ProjectControllerMapper;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.ProjectService;
import com.foremen.config.security.PermissionOperation;
import com.foremen.service.model.ProjectServiceExtendedModel;
import com.foremen.service.model.ProjectServiceModel;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for {@code Project} (FOR-04-13), mapped at {@code /api/projects}.
 *
 * <p>It implements {@link AdminController}, inheriting the generic list/read/update/delete/count/
 * metadata/i18n endpoints (each keeping its {@code @PermissionOperation}), and combines them with
 * the class-level {@link PermissionResource}{@code ("PROJECTS")} so every inherited handler resolves
 * to a {@code PROJECTS}/{operation} pair (Requirements 6.1, 6.2).
 *
 * <p><b>Custom create.</b> Projects are created only through the custom transactional
 * {@link #create(CreateProjectRequest)} handler. It <strong>overrides</strong> the inherited generic
 * single-create, reusing (rather than duplicating) the interface's root {@code @PostMapping} so there
 * is exactly one root {@code POST /api/projects} handler, and declares its own
 * {@link RequiresPermission}{@code (resource="PROJECTS", operation="CREATE")} (Requirements 2.1,
 * 2.10). The generic (non-custom) create is additionally disabled at the service layer, which also
 * covers the still-mapped bulk-create path (Requirement 3.1). Generic list/read/update/delete remain
 * available and project-scoped (Requirement 3.2).
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@PermissionResource("PROJECTS")
public class ProjectController implements AdminController<
        ProjectServiceModel,
        ProjectServiceExtendedModel,
        ProjectListDto,
        ProjectReadDto,
        ProjectEntity,
        Long,
        CreateProjectRequest,
        ProjectCreateResponse,
        ProjectUpdateRequest,
        ProjectUpdateResponse> {

    private final ProjectService service;
    private final ProjectControllerMapper controllerMapper;

    @Override
    public ControllerToServiceMapper<ProjectServiceModel, ProjectServiceExtendedModel,
            ProjectListDto, ProjectReadDto,
            CreateProjectRequest, ProjectCreateResponse,
            ProjectUpdateRequest, ProjectUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<ProjectServiceModel, ProjectServiceExtendedModel,
            ProjectEntity, Long> getService() {
        return service;
    }

    /**
     * Custom transactional project-creation endpoint (Requirements 2.1, 2.10). Overrides the
     * inherited generic single-create so it <strong>replaces</strong> the root {@code POST
     * /api/projects} mapping (the inherited {@code @PostMapping} is retained through the interface,
     * avoiding a duplicate handler) with the custom orchestrator delegating to
     * {@link ProjectService#createProject(CreateProjectRequest)} (task 3.5), and returns HTTP
     * {@code 201 Created} with the created project.
     *
     * <p>It declares its own {@link RequiresPermission}{@code (resource="PROJECTS",
     * operation="CREATE")}, which takes precedence over the class {@link PermissionResource} +
     * inherited {@code @PermissionOperation} combination, so the endpoint resolves to
     * {@code PROJECTS}/{@code CREATE}. The generic (non-custom) create is additionally disabled at
     * the service layer, covering the still-mapped bulk-create path too (Requirement 3.1).
     */
    @Override
    @PostMapping
    @RequiresPermission(resource = "PROJECTS", operation = "CREATE")
    public ResponseEntity<ProjectCreateResponse> create(
            @Valid @RequestBody CreateProjectRequest request) {
        ProjectCreateResponse response = service.createProject(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * List projection (task 7.1). Overrides the inherited generic {@link AdminController#find} so
     * each returned {@link ProjectListDto} carries the {@code members} collection and the derived
     * {@code client} (design Change 2/3), which the base MapStruct mapping deliberately leaves
     * {@code ignore}d. Delegates to {@link ProjectService#findProjected(Pageable, String)}, which
     * reuses the inherited access-filtered/sorted/paged query and batch-hydrates the team in one
     * extra bounded query (no N+1). {@code addCustomQueryCondition} is applied for parity with the
     * generic flow, and the endpoint keeps resolving to {@code PROJECTS}/{@code READ}.
     */
    @Override
    @GetMapping
    @PermissionOperation("READ")
    public ResponseEntity<Page<ProjectListDto>> find(
            Pageable pageable,
            @RequestParam(name = "query", required = false) String query) {
        Page<ProjectListDto> page = service.findProjected(pageable, addCustomQueryCondition(query));
        return ResponseEntity.ok(page);
    }

    /**
     * Extended-list projection (task 7.1). Mirrors {@link #find(Pageable, String)} for the
     * {@code /extended} endpoint so extended list rows also carry the projected {@code members} +
     * derived {@code client}.
     */
    @Override
    @GetMapping("/extended")
    @PermissionOperation("READ")
    public ResponseEntity<Page<ProjectReadDto>> findExtended(
            Pageable pageable,
            @RequestParam(name = "query", required = false) String query) {
        Page<ProjectListDto> page = service.findProjected(pageable, addCustomQueryCondition(query));
        Page<ProjectReadDto> extended = page.map(ProjectController::toReadDto);
        return ResponseEntity.ok(extended);
    }

    /**
     * Single-record projection (task 7.1). Overrides the inherited generic {@link
     * AdminController#findById} so the returned {@link ProjectReadDto} carries the {@code members}
     * collection and the derived {@code client}. Delegates to
     * {@link ProjectService#findByIdProjected(Long)}, which reuses the by-id membership assertion
     * (non-member reads are denied) before projecting the team.
     */
    @Override
    @GetMapping("/{id}")
    @PermissionOperation("READ")
    public ResponseEntity<ProjectReadDto> findById(@PathVariable Long id) {
        return ResponseEntity.ok(service.findByIdProjected(id));
    }

    /** Adapts a projected list DTO into the identically-shaped read DTO for the extended endpoint. */
    private static ProjectReadDto toReadDto(ProjectListDto dto) {
        return new ProjectReadDto(
                dto.id(),
                dto.name(),
                dto.address(),
                dto.googlePlaceId(),
                dto.formattedAddress(),
                dto.latitude(),
                dto.longitude(),
                dto.area(),
                dto.startDate(),
                dto.endDate(),
                dto.status(),
                dto.members(),
                dto.client());
    }
}
