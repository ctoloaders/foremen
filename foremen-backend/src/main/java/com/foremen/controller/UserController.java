package com.foremen.controller;

import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.UserControllerMapper;
import com.foremen.dao.model.UserEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.ClientRegistrationService;
import com.foremen.service.UserService;
import com.foremen.service.model.UserServiceExtendedModel;
import com.foremen.service.model.UserServiceModel;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
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
}
