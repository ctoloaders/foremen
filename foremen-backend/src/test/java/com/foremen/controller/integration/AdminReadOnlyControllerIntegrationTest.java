package com.foremen.controller.integration;

import com.foremen.controller.AdminReadOnlyController;
import com.foremen.service.ReadOnlyAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitWebConfig(AdminReadOnlyControllerIntegrationTest.TestWebConfig.class)
class AdminReadOnlyControllerIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    @SuppressWarnings("unchecked")
    private ReadOnlyAdminService<TestModel, TestExtModel, Object, Long> mockReadOnlyService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        Mockito.reset(mockReadOnlyService);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    // --- Test Models ---

    record TestModel(Long id, String name) {}

    record TestExtModel(Long id, String namePL, String nameRU) {}

    // --- Configuration ---

    @Configuration
    @EnableWebMvc
    @Import(TestReadOnlyController.class)
    static class TestWebConfig implements WebMvcConfigurer {

        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new PageableHandlerMethodArgumentResolver());
        }

        @Bean
        @SuppressWarnings("unchecked")
        ReadOnlyAdminService<TestModel, TestExtModel, Object, Long> mockReadOnlyService() {
            return Mockito.mock(ReadOnlyAdminService.class);
        }
    }

    @RestController
    @RequestMapping("/api/readonly/test-entity")
    static class TestReadOnlyController implements AdminReadOnlyController<TestModel, TestExtModel, Object, Long> {

        @Autowired
        ReadOnlyAdminService<TestModel, TestExtModel, Object, Long> mockReadOnlyService;

        @Override
        public ReadOnlyAdminService<TestModel, TestExtModel, Object, Long> getService() {
            return mockReadOnlyService;
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("GET / returns 200 with Page of ServiceModel")
    void findReturns200WithPage() throws Exception {
        Pageable pageable = PageRequest.of(0, 20);
        Page<TestModel> page = new PageImpl<>(
                List.of(new TestModel(1L, "Item One"), new TestModel(2L, "Item Two")),
                pageable,
                2
        );

        when(mockReadOnlyService.find(any(Pageable.class), eq(null))).thenReturn(page);

        mockMvc.perform(get("/api/readonly/test-entity")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Item One"))
                .andExpect(jsonPath("$.content[1].id").value(2))
                .andExpect(jsonPath("$.content[1].name").value("Item Two"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.number").value(0));
    }

    @Test
    @DisplayName("GET /{id} returns 200 with localized ServiceModel")
    void findByIdReturns200WithLocalizedModel() throws Exception {
        TestModel model = new TestModel(42L, "Localized Name");

        when(mockReadOnlyService.findByIdLocalized(42L)).thenReturn(model);

        mockMvc.perform(get("/api/readonly/test-entity/42"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.name").value("Localized Name"));
    }
}
