package com.foremen.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.config.security.PermissionAnnotationValidator;
import com.foremen.config.security.PermissionResolver;
import com.foremen.controller.advice.ForemenControllerAdvice;
import com.foremen.controller.model.ShipmentDto;
import com.foremen.controller.model.ShipmentFilter;
import com.foremen.service.procurement.shipment.RefreshOutcome;
import com.foremen.service.procurement.shipment.RefreshOutcome.Result;
import com.foremen.service.procurement.shipment.ShipmentService;
import com.foremen.service.procurement.shipment.ShipmentTrackingService;

/**
 * FOR-11-04 task 3.10 (R1.4, R1.5, R10, R16): {@link ShipmentController} refresh mapping and the
 * startup-validation check of its permission annotations. Standalone MockMvc, mocked services.
 */
class ShipmentControllerTest {

    private ShipmentService shipmentService;
    private ShipmentTrackingService trackingService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        shipmentService = mock(ShipmentService.class);
        trackingService = mock(ShipmentTrackingService.class);
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        mockMvc = MockMvcBuilders.standaloneSetup(new ShipmentController(shipmentService, trackingService))
                .setControllerAdvice(new ForemenControllerAdvice(new MessageResolver(source)))
                .build();
        when(shipmentService.get(1L)).thenReturn(dto());
    }

    @Test
    @DisplayName("refresh UPDATED / UNCHANGED / FAILED -> 200 with refreshResult")
    void refreshPerformedReturnsDtoWithResult() throws Exception {
        for (Result result : List.of(Result.UPDATED, Result.UNCHANGED, Result.FAILED)) {
            when(trackingService.refresh(1L, true)).thenReturn(RefreshOutcome.of(result));
            mockMvc.perform(post("/api/shipments/1/refresh"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.refreshResult").value(result.name()));
        }
    }

    @Test
    @DisplayName("refresh REJECTED/TOO_SOON -> 429 with retryAfterSeconds and Retry-After")
    void refreshTooSoon() throws Exception {
        when(trackingService.refresh(1L, true))
                .thenReturn(new RefreshOutcome(Result.REJECTED, RefreshOutcome.REASON_TOO_SOON, 42L));
        mockMvc.perform(post("/api/shipments/1/refresh"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.params.retryAfterSeconds").value(42))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Zbyt częste")));
    }

    @Test
    @DisplayName("refresh REJECTED CARRIER_PAUSED / SPACING -> carrierPaused; NOT_AVAILABLE -> notAvailable")
    void refreshRejections() throws Exception {
        when(trackingService.refresh(1L, true))
                .thenReturn(RefreshOutcome.of(Result.REJECTED, RefreshOutcome.REASON_CARRIER_PAUSED));
        mockMvc.perform(post("/api/shipments/1/refresh"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Przewoźnik")));

        when(trackingService.refresh(1L, true))
                .thenReturn(RefreshOutcome.of(Result.REJECTED, RefreshOutcome.REASON_SPACING));
        mockMvc.perform(post("/api/shipments/1/refresh"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Przewoźnik")));

        when(trackingService.refresh(1L, true))
                .thenReturn(RefreshOutcome.of(Result.REJECTED, RefreshOutcome.REASON_NOT_AVAILABLE));
        mockMvc.perform(post("/api/shipments/1/refresh"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("niedostępne")));
    }

    @Test
    @DisplayName("list and cancel delegate to the service")
    void listAndCancel() throws Exception {
        when(shipmentService.list(any(ShipmentFilter.class), any()))
                .thenReturn(new PageImpl<>(List.of(dto()),
                        org.springframework.data.domain.PageRequest.of(0, 20), 1));
        mockMvc.perform(get("/api/shipments?status=IN_TRANSIT").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1));

        when(shipmentService.cancel(1L)).thenReturn(dto());
        mockMvc.perform(post("/api/shipments/1/cancel")).andExpect(status().isOk());
        verify(shipmentService).cancel(1L);
    }

    @Test
    @DisplayName("startup validation accepts ShipmentController (every handler fully annotated)")
    void startupValidationPasses() {
        new ApplicationContextRunner()
                .withUserConfiguration(ValidationConfig.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration
    static class ValidationConfig {
        @Bean
        RequestMappingHandlerMapping requestMappingHandlerMapping() {
            return new RequestMappingHandlerMapping();
        }

        @Bean
        PermissionResolver permissionResolver() {
            return new PermissionResolver();
        }

        @Bean
        PermissionAnnotationValidator permissionAnnotationValidator(RequestMappingHandlerMapping mapping,
                                                                    PermissionResolver resolver) {
            return new PermissionAnnotationValidator(mapping, resolver);
        }

        @Bean
        ShipmentController shipmentController() {
            return new ShipmentController(mock(ShipmentService.class), mock(ShipmentTrackingService.class));
        }
    }

    private static ShipmentDto dto() {
        return new ShipmentDto(1L, 2L, "P", null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, false, false, null, true, null,
                List.of(), List.of());
    }

    static {
        Locale.setDefault(new Locale("pl"));
    }

    @Test
    @DisplayName("list passes the delayed flag into the filter")
    void listPassesDelayedFilter() throws Exception {
        when(shipmentService.list(any(ShipmentFilter.class), any()))
                .thenReturn(new PageImpl<>(List.of(dto()),
                        org.springframework.data.domain.PageRequest.of(0, 20), 1));
        mockMvc.perform(get("/api/shipments?delayed=true").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        org.mockito.ArgumentCaptor<ShipmentFilter> captor = org.mockito.ArgumentCaptor.forClass(ShipmentFilter.class);
        verify(shipmentService).list(captor.capture(), any());
        assertThat(captor.getValue().delayed()).isTrue();
    }

    @Test
    @DisplayName("a rule violation carries fieldErrors for its field, status and message key unchanged")
    void ruleViolationReportsFieldErrors() throws Exception {
        when(shipmentService.create(any())).thenThrow(
                new com.foremen.exception.ForemenApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        "error.shipment.link.invalid").withField("shopOrderLink"));
        mockMvc.perform(post("/api/shipments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.shopOrderLink").isNotEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }
}
