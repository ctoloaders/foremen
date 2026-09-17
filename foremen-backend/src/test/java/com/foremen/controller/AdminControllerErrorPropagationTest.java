package com.foremen.controller;

import com.foremen.exception.ForemenApiException;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Tests that ForemenApiException from the service layer propagates through controller
 * default methods without being caught — the controller is a pure delegation layer.
 *
 * Validates: Requirements 4.2, 8.2, 10.2, 11.2
 */
@ExtendWith(MockitoExtension.class)
class AdminControllerErrorPropagationTest {

    @Mock
    private AdminService<String, String, Object, Long> service;

    @Mock
    private ControllerToServiceMapper<String, String, String, String, String, String, String, String> mapper;

    private AdminController<String, String, String, String, Object, Long, String, String, String, String> controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController<>() {
            @Override
            public ControllerToServiceMapper<String, String, String, String, String, String, String, String> getMapper() {
                return mapper;
            }

            @Override
            @SuppressWarnings("unchecked")
            public AdminService<String, String, Object, Long> getService() {
                return service;
            }

            @Override
            public com.foremen.service.model.mapper.AuditServiceMapper getAuditServiceMapper() {
                return null; // audit endpoint not exercised by this error-propagation test
            }
        };
    }

    @Test
    @DisplayName("update with non-existent ID → ForemenApiException(404) propagates from service")
    void update_nonExistentId_propagates404() {
        Long nonExistentId = 999L;
        String updateRequest = "updateRequest";
        String serviceModel = "serviceModel";

        when(mapper.toUpdateServiceExtendedModel(updateRequest)).thenReturn(serviceModel);
        when(service.update(eq(nonExistentId), eq(serviceModel)))
                .thenThrow(new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", nonExistentId));

        assertThatThrownBy(() -> controller.update(nonExistentId, updateRequest))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException apiEx = (ForemenApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiEx.getMessageCode()).isEqualTo("error.entity.not.found");
                });
    }

    @Test
    @DisplayName("deleteById with non-existent ID → ForemenApiException(404) propagates from service")
    void deleteById_nonExistentId_propagates404() {
        Long nonExistentId = 999L;

        doThrow(new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", nonExistentId))
                .when(service).deleteById(nonExistentId);

        assertThatThrownBy(() -> controller.deleteById(nonExistentId))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException apiEx = (ForemenApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiEx.getMessageCode()).isEqualTo("error.entity.not.found");
                });
    }

    @Test
    @DisplayName("findById with non-existent ID → ForemenApiException(404) propagates from service")
    void findById_nonExistentId_propagates404() {
        Long nonExistentId = 999L;

        when(service.findById(nonExistentId))
                .thenThrow(new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", nonExistentId));

        assertThatThrownBy(() -> controller.findById(nonExistentId))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException apiEx = (ForemenApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(apiEx.getMessageCode()).isEqualTo("error.entity.not.found");
                });
    }

    @Test
    @DisplayName("setPropertiesToNull with invalid field → ForemenApiException(400) propagates from service")
    void setPropertiesToNull_invalidField_propagates400() {
        Long id = 1L;
        Set<String> invalidProperties = Set.of("nonExistentField");

        doThrow(new ForemenApiException(HttpStatus.BAD_REQUEST, "error.invalid.field.name", "nonExistentField"))
                .when(service).setPropertiesToNull(eq(id), eq(invalidProperties));

        assertThatThrownBy(() -> controller.setPropertiesToNull(id, invalidProperties))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException apiEx = (ForemenApiException) ex;
                    assertThat(apiEx.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(apiEx.getMessageCode()).isEqualTo("error.invalid.field.name");
                });
    }
}
