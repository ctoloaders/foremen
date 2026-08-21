package com.foremen.controller;

import com.foremen.controller.model.*;
import com.foremen.controller.model.mapper.UserControllerMapper;
import com.foremen.dao.model.UserEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import com.foremen.service.UserService;
import com.foremen.service.model.UserServiceExtendedModel;
import com.foremen.service.model.UserServiceModel;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
