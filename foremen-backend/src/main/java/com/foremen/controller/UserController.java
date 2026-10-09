package com.foremen.controller;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.UserControllerMapper;
import com.foremen.dao.model.UserEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.ClientRegistrationService;
import com.foremen.service.UserService;
import com.foremen.service.WorkerRecordService;
import com.foremen.service.WorkerInvitationService;
import com.foremen.service.model.UserServiceExtendedModel;
import com.foremen.service.model.UserServiceModel;
import com.foremen.service.model.mapper.AuditServiceMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PermissionResource("USERS")
public class UserController implements AdminController<
        UserServiceModel,
        UserServiceExtendedModel,
        UserDtoModel,
        UserDtoExtendedModel,
        UserEntity,
        Long,
        UserCreateRequest,
        UserCreateResponse,
        UserUpdateRequest,
        UserUpdateResponse> {

    private final UserService userService;
    private final UserControllerMapper controllerMapper;
    private final ClientRegistrationService clientRegistrationService;
    private final WorkerRecordService workerRecordService;
    private final WorkerInvitationService workerInvitationService;
    private final AuditServiceMapper auditServiceMapper;

    @Override
    public ControllerToServiceMapper<UserServiceModel, UserServiceExtendedModel,
            UserDtoModel, UserDtoExtendedModel,
            UserCreateRequest, UserCreateResponse,
            UserUpdateRequest, UserUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<UserServiceModel, UserServiceExtendedModel, UserEntity, Long> getService() {
        return userService;
    }

    @Override
    public AuditServiceMapper getAuditServiceMapper() {
        return auditServiceMapper;
    }

    /**
     * Registers a CLIENT user for a specific project (Requirement 10). This is the dedicated
     * client-registration path, distinct from the generic {@code POST /api/users} CRUD create: the
     * role is never accepted in the request body and is fixed to {@code CLIENT} server-side, and a
     * {@code projectId} is required so the new client is immediately attached to that project.
     *
     * <p>Guarded by {@code @RequiresPermission(PROJECTS, EDIT)} (Req 10.2): a caller lacking the
     * grant receives 403 {@code error.access.denied} from the {@code PermissionInterceptor} (ADMIN
     * bypass included). {@code @Valid} rejects a blank {@code name}/{@code email} or a missing
     * {@code projectId} with 400 before any work (Req 10.3). Returns 201 Created with the created
     * client's identity and its project link (Req 10.10).
     */
    @PostMapping("/client")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = "PROJECTS", operation = "EDIT")
    public ClientRegistrationResponse registerClient(
            @Valid @RequestBody ClientRegistrationRequest request) {
        return clientRegistrationService.register(request);
    }

    /**
     * The {@code Worker_Record_Flow} (FOR-05-09, Requirement 13): creates one uninvited WORKER user
     * (no password, cannot authenticate, {@code active = true}) plus one WORKER membership on the
     * target project in a single atomic unit, sending no email. The role is fixed to {@code WORKER}
     * server-side (any role in the body is ignored).
     *
     * <p><b>Dual-permission guard (D7).</b> This handler declares the {@code PROJECTS/EDIT} half with
     * {@code @RequiresPermission} (the FOR-03-05 pattern, mirroring {@link #inviteWorker}); the
     * {@code PROJECT_MEMBERS/CREATE} half is asserted inside {@link WorkerRecordService#register}. A
     * caller lacking either operation receives 403 {@code error.access.denied}.
     *
     * <p>The request carries no bean-validation annotations: the flow validates every field in the
     * canonical record-flow order and reports all offending fields together with 400 (Requirement 13
     * criteria 2–6, 11). Returns 201 with the new user id, stored email (empty when none), project
     * id, and membership id (Requirement 13 criterion 1).
     */
    @PostMapping("/worker")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(resource = "PROJECTS", operation = "EDIT")
    public WorkerRecordResponse registerWorker(@RequestBody WorkerRecordRequest request) {
        return workerRecordService.register(request);
    }

    /**
     * The {@code Worker_Invitation_Flow} (FOR-05-09, Requirements 13.17&ndash;13.18): sends / re-sends
     * the FOR-03-02 staff password-set email to a not-yet-activated WORKER user that has a stored
     * email, so the worker sets their own password.
     *
     * <p><b>Dual-permission guard.</b> This handler declares the {@code PROJECTS/EDIT} half with
     * {@code @RequiresPermission} (the FOR-03-05 pattern); the {@code PROJECT_MEMBERS/CREATE} half is
     * asserted inside {@link WorkerInvitationService#invite(Long)}. A caller lacking either operation
     * receives 403 {@code error.access.denied}.
     *
     * <p>Returns 200 with the invited user's identity and new status. Rejections (404 non-existent,
     * 400 non-WORKER, 409 already-active, 400 email-required) send no email and change no state, per
     * the canonical order in {@link WorkerInvitationService}.
     */
    @PostMapping("/worker/{id}/invite")
    @ResponseStatus(HttpStatus.OK)
    @RequiresPermission(resource = "PROJECTS", operation = "EDIT")
    public WorkerInvitationResponse inviteWorker(@PathVariable("id") Long id) {
        return workerInvitationService.invite(id);
    }
}
