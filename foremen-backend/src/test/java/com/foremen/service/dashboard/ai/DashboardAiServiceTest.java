package com.foremen.service.dashboard.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.config.dashboard.DashboardAiProperties;
import com.foremen.dao.RoleDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.dashboard.DashboardAccessService;
import com.foremen.service.dashboard.DashboardNotificationEmitter;
import com.foremen.service.dashboard.ai.DashboardAiService.AiDraftResponse;
import com.foremen.service.dashboard.ai.WidgetDraftException.Reason;
import com.foremen.service.dashboard.config.WidgetConfig.Localized;
import com.foremen.service.dashboard.config.WidgetConfigValidator;
import com.foremen.service.dashboard.config.WidgetConfigValidator.Violation;
import com.foremen.service.dashboard.dataset.DatasetCatalogDto;
import com.foremen.service.dashboard.dataset.DatasetRegistry;

/**
 * FOR-10 task 5.3 — DashboardAiService with a stub draft client.
 * <b>Validates: Requirements 13.1-13.8</b>
 */
class DashboardAiServiceTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Long ADMIN = 7L;

    private final DatasetRegistry registry = mock(DatasetRegistry.class);
    private final WidgetConfigValidator validator = mock(WidgetConfigValidator.class);
    private final DashboardNotificationEmitter notifications = mock(DashboardNotificationEmitter.class);
    private final DashboardAccessService access = mock(DashboardAccessService.class);
    private final RoleDao roleDao = mock(RoleDao.class);
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    };

    private StubClient client;
    private DashboardAiService service;

    /** Controllable client (no mocking of the draft itself). */
    static class StubClient implements WidgetDraftClient {
        boolean enabled = true;
        RuntimeException failure;
        DraftResponse response;
        int calls;
        DraftRequest lastRequest;

        @Override public boolean isEnabled() { return enabled; }

        @Override public DraftResponse draft(DraftRequest request) {
            calls++;
            lastRequest = request;
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }

    @BeforeEach
    void setUp() {
        client = new StubClient();
        client.response = new DraftResponse(new Localized("Имя", "Nazwa"), new Localized("Опис", "Opis"),
                2, 1, DraftResponse.SCOPE_NONE, M.createObjectNode().put("dataset", "USERS"),
                new Localized("Пояснение", "Wyjaśnienie"));
        when(registry.catalog()).thenReturn(List.of(new DatasetCatalogDto("USERS", List.of("USERS"), false,
                false, false, null, List.of())));
        when(validator.validate(any(com.fasterxml.jackson.databind.JsonNode.class), anyBoolean()))
                .thenReturn(new WidgetConfigValidator.Result(null, List.of()));
        Page<RoleEntity> noRoles = new PageImpl<>(List.of());
        when(roleDao.findAll(any(Pageable.class))).thenReturn(noRoles);
        service = new DashboardAiService(client, registry, validator, notifications, access, roleDao,
                new DashboardAiProperties(true, 2, null), clock);
    }

    @Test
    void success_returnsDraftWithCatalogAndRoutesAndNeverNotifies() {
        AiDraftResponse r = service.draft(ADMIN, "show users", "ru");

        assertThat(r.nameRu()).isEqualTo("Имя");
        assertThat(r.namePl()).isEqualTo("Nazwa");
        assertThat(r.colSpan()).isEqualTo(2);
        assertThat(r.projectScope()).isEqualTo("NONE");
        assertThat(r.explanation().pl()).isEqualTo("Wyjaśnienie");
        assertThat(r.warnings()).isEmpty();
        assertThat(client.lastRequest.catalog()).hasSize(1);
        assertThat(client.lastRequest.routes()).isNotEmpty();
        verifyNoInteractions(notifications);
    }

    @Test
    void roleWithoutAccess_producesAdvisoryWarning() {
        RoleEntity role = new RoleEntity();
        role.setCode("FOREMAN");
        when(roleDao.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(role)));
        when(access.missingResources("FOREMAN", Set.of("USERS"))).thenReturn(Set.of("USERS"));

        AiDraftResponse r = service.draft(ADMIN, "p", null);

        assertThat(r.warnings()).hasSize(1);
        assertThat(r.warnings().get(0).message()).contains("FOREMAN");
    }

    @Test
    void disabledClient_is503AndNoNotification() {
        client.enabled = false;

        assertThatThrownBy(() -> service.draft(ADMIN, "p", null))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getMessage()).isEqualTo("error.dashboard.ai.disabled");
                });
        assertThat(client.calls).isZero();
        verifyNoInteractions(notifications);
    }

    @Test
    void disabledReasonFromClient_is503() {
        client.failure = new WidgetDraftException(Reason.DISABLED, "off");

        assertThatThrownBy(() -> service.draft(ADMIN, "p", null))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(notifications, never()).emitAiFailed(anyLong(), anyString());
    }

    @Test
    void clientFailure_is502AndNotifiesAdmin() {
        client.failure = new WidgetDraftException(Reason.LLM_UNAVAILABLE, "down");

        assertThatThrownBy(() -> service.draft(ADMIN, "secret prompt", null))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.getMessage()).isEqualTo("error.dashboard.ai.failed");
                });
        verify(notifications).emitAiFailed(ADMIN, "LLM_UNAVAILABLE");
    }

    @Test
    void invalidDraft_isRejectedAsFailedAndNotNotifiedAsSuccess() {
        when(validator.validate(any(com.fasterxml.jackson.databind.JsonNode.class), anyBoolean()))
                .thenReturn(new WidgetConfigValidator.Result(null,
                        List.of(new Violation("dataset", "dataset.unknown", "Unknown dataset"))));

        assertThatThrownBy(() -> service.draft(ADMIN, "p", null))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
        verify(notifications).emitAiFailed(ADMIN, "INVALID_RESPONSE");
    }

    @Test
    void rateLimit_blocksAfterLimitAndRecoversAfterWindow() {
        service.draft(ADMIN, "p", null);
        service.draft(ADMIN, "p", null);

        assertThatThrownBy(() -> service.draft(ADMIN, "p", null))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getMessage()).isEqualTo("error.dashboard.ai.rate.limited");
                });
        assertThat(client.calls).isEqualTo(2);
        // another admin is independent
        service.draft(8L, "p", null);

        now.addAndGet(60_001L);
        service.draft(ADMIN, "p", null);
        assertThat(client.calls).isEqualTo(4);
    }
}
