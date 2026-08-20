package com.foremen.controller;

import com.foremen.service.ReadOnlyAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminReadOnlyControllerDefaultMethodsTest {

    @Mock
    private ReadOnlyAdminService<String, String, Object, Long> service;

    private AdminReadOnlyController<String, String, Object, Long> controller;

    @BeforeEach
    void setUp() {
        controller = new AdminReadOnlyController<>() {
            @Override
            public ReadOnlyAdminService<String, String, Object, Long> getService() {
                return service;
            }
        };
    }

    @Test
    @DisplayName("find delegates to service.find(pageable, query) and wraps result in ResponseEntity.ok()")
    void findDelegatesToServiceFind() {
        Pageable pageable = PageRequest.of(0, 10);
        String query = "name==Test";
        Page<String> expectedPage = new PageImpl<>(List.of("item1", "item2"), pageable, 2);

        when(service.find(pageable, query)).thenReturn(expectedPage);

        ResponseEntity<Page<String>> response = controller.find(pageable, query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(expectedPage);
        verify(service).find(pageable, query);
        verifyNoMoreInteractions(service);
    }

    @Test
    @DisplayName("find with null query delegates to service.find(pageable, null)")
    void findWithNullQueryDelegatesToServiceFind() {
        Pageable pageable = PageRequest.of(1, 20);
        Page<String> expectedPage = new PageImpl<>(List.of("a", "b", "c"), pageable, 3);

        when(service.find(pageable, null)).thenReturn(expectedPage);

        ResponseEntity<Page<String>> response = controller.find(pageable, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(expectedPage);
        verify(service).find(pageable, null);
    }

    @Test
    @DisplayName("findById delegates to service.findByIdLocalized(id) and wraps result in ResponseEntity.ok()")
    void findByIdDelegatesToServiceFindByIdLocalized() {
        Long id = 42L;
        String expectedModel = "localized-model";

        when(service.findByIdLocalized(id)).thenReturn(expectedModel);

        ResponseEntity<String> response = controller.findById(id);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(expectedModel);
        verify(service).findByIdLocalized(id);
        verifyNoMoreInteractions(service);
    }
}
