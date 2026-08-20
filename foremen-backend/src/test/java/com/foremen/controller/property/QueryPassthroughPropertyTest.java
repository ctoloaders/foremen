package com.foremen.controller.property;

import com.foremen.controller.AdminController;
import com.foremen.dao.model.BaseEntity;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.service.AdminService;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.BeforeProperty;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Property 3: Query Passthrough to Service
 *
 * For any query string (including null and blank), the AdminController's find, findExtended,
 * and getCount default methods SHALL pass the query through addCustomQueryCondition and then
 * to the service method unchanged. When addCustomQueryCondition is not overridden, the service
 * receives the exact same query string the controller received.
 *
 * Validates: Requirements 5.2, 5.4, 6.1, 7.2
 */
@Tag("Feature: FOR-01-07-crud-controller, Property 3: Query Passthrough to Service")
class QueryPassthroughPropertyTest {

    // --- Test record types ---

    record TestServiceModel(Long id, String name) {}

    record TestServiceExtendedModel(Long id, String name, String description) {}

    record TestDtoModel(Long id, String name) {}

    record TestDtoExtendedModel(Long id, String name, String description) {}

    static class TestDaoEntity extends BaseEntity {
        private String name;
    }

    // --- Mocks ---

    @SuppressWarnings("unchecked")
    private final ControllerToServiceMapper<TestServiceModel, TestServiceExtendedModel, TestDtoModel,
            TestDtoExtendedModel, Object, Object, Object, Object> mapper = Mockito.mock(ControllerToServiceMapper.class);

    @SuppressWarnings("unchecked")
    private final AdminService<TestServiceModel, TestServiceExtendedModel, TestDaoEntity, Long> service = Mockito.mock(AdminService.class);

    // --- Controller under test (default addCustomQueryCondition — passthrough) ---

    private final AdminController<TestServiceModel, TestServiceExtendedModel, TestDtoModel, TestDtoExtendedModel,
            TestDaoEntity, Long, Object, Object, Object, Object> controller =
            new AdminController<>() {
                @Override
                public ControllerToServiceMapper<TestServiceModel, TestServiceExtendedModel, TestDtoModel,
                        TestDtoExtendedModel, Object, Object, Object, Object> getMapper() {
                    return mapper;
                }

                @Override
                public AdminService<TestServiceModel, TestServiceExtendedModel, TestDaoEntity, Long> getService() {
                    return service;
                }
            };

    private final Pageable defaultPageable = PageRequest.of(0, 20);

    @BeforeProperty
    void setUp() {
        Mockito.reset(mapper, service);

        // Setup service.find to return an empty page
        Page<TestServiceModel> emptyPage = new PageImpl<>(List.of(), defaultPageable, 0);
        when(service.find(any(Pageable.class), any())).thenReturn(emptyPage);

        // Setup service.findExtended to return an empty page
        Page<TestServiceExtendedModel> emptyExtendedPage = new PageImpl<>(List.of(), defaultPageable, 0);
        when(service.findExtended(any(Pageable.class), any())).thenReturn(emptyExtendedPage);

        // Setup service.getCount to return 0
        when(service.getCount(any())).thenReturn(0L);
    }

    // --- Property: find passes query unchanged to service ---

    @Property(tries = 100)
    void findPassesQueryUnchangedToService(
            @ForAll("queryStrings") String query
    ) {
        controller.find(defaultPageable, query);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(service).find(eq(defaultPageable), captor.capture());
        assertThat(captor.getValue()).isEqualTo(query);

        Mockito.clearInvocations(service);
    }

    // --- Property: findExtended passes query unchanged to service ---

    @Property(tries = 100)
    void findExtendedPassesQueryUnchangedToService(
            @ForAll("queryStrings") String query
    ) {
        controller.findExtended(defaultPageable, query);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(service).findExtended(eq(defaultPageable), captor.capture());
        assertThat(captor.getValue()).isEqualTo(query);

        Mockito.clearInvocations(service);
    }

    // --- Property: getCount passes query unchanged to service ---

    @Property(tries = 100)
    void getCountPassesQueryUnchangedToService(
            @ForAll("queryStrings") String query
    ) {
        controller.getCount(query);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(service).getCount(captor.capture());
        assertThat(captor.getValue()).isEqualTo(query);

        Mockito.clearInvocations(service);
    }

    // --- Example: find passes null query unchanged to service ---

    @Example
    void findPassesNullQueryToService() {
        controller.find(defaultPageable, null);

        verify(service).find(eq(defaultPageable), eq(null));

        Mockito.clearInvocations(service);
    }

    // --- Example: findExtended passes null query unchanged to service ---

    @Example
    void findExtendedPassesNullQueryToService() {
        controller.findExtended(defaultPageable, null);

        verify(service).findExtended(eq(defaultPageable), eq(null));

        Mockito.clearInvocations(service);
    }

    // --- Example: getCount passes null query unchanged to service ---

    @Example
    void getCountPassesNullQueryToService() {
        controller.getCount(null);

        verify(service).getCount(eq(null));

        Mockito.clearInvocations(service);
    }

    // --- Providers ---

    @Provide
    Arbitrary<String> queryStrings() {
        return Arbitraries.oneOf(
                // Empty string
                Arbitraries.just(""),
                // Whitespace-only strings
                Arbitraries.strings().withChars(' ', '\t', '\n').ofMinLength(1).ofMaxLength(10),
                // Simple alphanumeric strings
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(50),
                // Query DSL patterns (field==value)
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20)
                        .map(name -> name + "==" + "value"),
                // Query DSL with operators
                Arbitraries.of(
                        "name==Test",
                        "status==active",
                        "priority>5",
                        "name==Test;status==active",
                        "category.name==Electronics",
                        "price>=100;price<=500",
                        "name=like=*test*",
                        "status=in=(active,pending)",
                        "createdAt>2024-01-01"
                ),
                // Mixed content with special characters
                Arbitraries.strings()
                        .withChars('a', 'z', 'A', 'Z', '0', '9', '=', ';', '.', '>', '<', '*', '(', ')', ',', ' ', '_', '-')
                        .ofMinLength(1)
                        .ofMaxLength(100)
        );
    }
}
