package com.foremen.controller;

import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.RoleControllerMapper;
import com.foremen.dao.model.RoleEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.RoleService;
import com.foremen.service.model.RoleServiceExtendedModel;
import com.foremen.service.model.RoleServiceModel;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController implements AdminController<
        RoleServiceModel,
        RoleServiceExtendedModel,
        RoleDtoModel,
        RoleDtoExtendedModel,
        RoleEntity,
        Long,
        RoleCreateRequest,
        RoleCreateResponse,
        RoleUpdateRequest,
        RoleUpdateResponse> {

    private final RoleService roleService;
    private final RoleControllerMapper controllerMapper;

    @Override
    public ControllerToServiceMapper<RoleServiceModel, RoleServiceExtendedModel,
            RoleDtoModel, RoleDtoExtendedModel,
            RoleCreateRequest, RoleCreateResponse,
            RoleUpdateRequest, RoleUpdateResponse> getMapper() {
        return controllerMapper;
    }

    @Override
    public AdminService<RoleServiceModel, RoleServiceExtendedModel, RoleEntity, Long> getService() {
        return roleService;
    }

    @GetMapping("/{id}/permissions")
    public ResponseEntity<RolePermissionResponse> getPermissions(@PathVariable Long id) {
        RolePermissionResponse response = roleService.getPermissions(id);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}/permissions")
    public ResponseEntity<RolePermissionResponse> replacePermissions(
            @PathVariable Long id,
            @Valid @RequestBody RolePermissionRequest request) {
        RolePermissionResponse response = roleService.replacePermissions(id, request);
        return ResponseEntity.ok(response);
    }
}
