package com.foremen.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.HandlerMethod;

import com.foremen.config.security.PermissionResolver;
import com.foremen.config.security.PermissionResolver.Completeness;
import com.foremen.config.security.RequiresPermission;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.approval.ApprovalFilter;
import com.foremen.service.procurement.approval.ApprovalQueryService;
import com.foremen.service.procurement.approval.ApprovalSummary;
import com.foremen.service.procurement.approval.ClientApprovalData;
import com.foremen.service.procurement.approval.ClientViewAssembler;
import com.foremen.service.procurement.approval.DecidedSide;
import com.foremen.service.procurement.approval.PositionApprovalService;
import com.foremen.service.procurement.approval.PositionApprovalService.AcceptCommand;
import com.foremen.service.procurement.approval.PositionApprovalService.Decider;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

/**
 * FOR-11-02 task 5.1: ABAC mapping, startup completeness (PermissionAnnotationValidator contract) and
 * request handling of {@link PositionApprovalClientController} with a standalone MockMvc.
 *
 * <p>Validates: Requirements 1.5, 5.1, 5.3, 5.4, 5.5, 7.1, 8.1, 10.1, 11.1
 */
@DisplayName("PositionApprovalClientController ABAC + request handling")
class PositionApprovalClientControllerAbacTest {

    private static final long USER_ID = 77L;

    private final PermissionResolver resolver = new PermissionResolver();
    private ApprovalQueryService queryService;
    private PositionApprovalService approvalService;
    private ProcurementProjectAccess projectAccess;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        queryService = mock(ApprovalQueryService.class);
        approvalService = mock(PositionApprovalService.class);
        projectAccess = mock(ProcurementProjectAccess.class);
        EntityManager em = mock(EntityManager.class);
        when(em.createQuery(any(String.class), eq(Long.class))).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            TypedQuery<Long> q = mock(TypedQuery.class);
            when(q.setParameter(any(String.class), any())).thenReturn(q);
            when(q.getResultList()).thenReturn(List.of());
            return q;
        });
        PositionApprovalClientController controller = new PositionApprovalClientController(queryService,
                new ClientViewAssembler(), approvalService, projectAccess, em,
                Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                USER_ID, null, List.of(new SimpleGrantedAuthority("ROLE_CLIENT"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("every handler is explicitly annotated, COMPLETE, with the expected pair")
    void permissionMapping() throws Exception {
        int handlers = 0;
        for (Method m : PositionApprovalClientController.class.getDeclaredMethods()) {
            RequiresPermission rp = m.getAnnotation(RequiresPermission.class);
            if (rp == null) {
                continue;
            }
            handlers++;
            HandlerMethod hm = new HandlerMethod(new PositionApprovalClientController(null, null, null, null,
                    null, null), m);
            assertThat(resolver.classifyCompleteness(hm)).as(m.getName()).isEqualTo(Completeness.COMPLETE);
            assertThat(rp.resource()).isEqualTo("POSITION_APPROVALS");
            boolean read = Set.of("list", "summary", "position").contains(m.getName());
            assertThat(rp.operation()).as(m.getName()).isEqualTo(read ? "READ" : "APPROVE");
        }
        assertThat(handlers).isEqualTo(7);
    }

    @Test
    @DisplayName("list passes the filters to the query service when the project is allowed")
    void listAllowed() throws Exception {
        when(projectAccess.allowedProjectIds(USER_ID)).thenReturn(Set.of(5L));
        when(queryService.loadClientData(eq(5L), any(ApprovalFilter.class)))
                .thenReturn(new ClientApprovalData(List.of(), java.util.Map.of(), java.util.Map.of()));

        mvc.perform(get("/api/position-approvals/client/projects/5")
                        .param("onlyPending", "true").param("blockNo", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        ArgumentCaptor<ApprovalFilter> captor = ArgumentCaptor.forClass(ApprovalFilter.class);
        verify(queryService).loadClientData(eq(5L), captor.capture());
        assertThat(captor.getValue().onlyPending()).isTrue();
        assertThat(captor.getValue().blockNo()).isEqualTo(3);
        assertThat(captor.getValue().overdue()).isFalse();
        assertThat(captor.getValue().sourcingPath()).isNull();
    }

    @Test
    @DisplayName("list and summary of a foreign project are denied before any query runs")
    void foreignProjectDenied() {
        when(projectAccess.allowedProjectIds(USER_ID)).thenReturn(Set.of(5L));

        assertThatThrownBy(() -> mvc.perform(get("/api/position-approvals/client/projects/9")))
                .hasRootCauseInstanceOf(ForemenApiException.class);
        assertThatThrownBy(() -> mvc.perform(get("/api/position-approvals/client/projects/9/summary")))
                .hasRootCauseInstanceOf(ForemenApiException.class);
        verify(queryService, never()).summary(any());
    }

    @Test
    @DisplayName("summary returns the four counts")
    void summary() throws Exception {
        when(projectAccess.allowedProjectIds(USER_ID)).thenReturn(Set.of(5L));
        when(queryService.summary(5L)).thenReturn(new ApprovalSummary(2, 1, 4, 3));

        mvc.perform(get("/api/position-approvals/client/projects/5/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awaitingDecision").value(2))
                .andExpect(jsonPath("$.overdue").value(1))
                .andExpect(jsonPath("$.accepted").value(4))
                .andExpect(jsonPath("$.clientOwn").value(3));
    }

    @Test
    @DisplayName("accept delegates with the current user as client decider")
    void accept() throws Exception {
        mvc.perform(post("/api/position-approvals/client/rounds/11/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":4,\"expectedUnitSalePrice\":12.50}"))
                .andExpect(status().isNoContent());

        ArgumentCaptor<AcceptCommand> cmd = ArgumentCaptor.forClass(AcceptCommand.class);
        ArgumentCaptor<Decider> decider = ArgumentCaptor.forClass(Decider.class);
        verify(approvalService).accept(eq(11L), cmd.capture(), decider.capture());
        assertThat(cmd.getValue().optionId()).isEqualTo(4L);
        assertThat(cmd.getValue().expectedUnitSalePrice()).isEqualByComparingTo(new BigDecimal("12.50"));
        assertThat(decider.getValue().userId()).isEqualTo(USER_ID);
        assertThat(decider.getValue().side()).isEqualTo(DecidedSide.CLIENT);
        assertThat(decider.getValue().onBehalf()).isFalse();
    }

    @Test
    @DisplayName("accept without a price (package choice) passes DTO validation and reaches the service")
    void acceptWithoutPrice() throws Exception {
        mvc.perform(post("/api/position-approvals/client/rounds/11/accept")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"optionId\":4}"))
                .andExpect(status().isNoContent());

        ArgumentCaptor<AcceptCommand> cmd = ArgumentCaptor.forClass(AcceptCommand.class);
        verify(approvalService).accept(eq(11L), cmd.capture(), any(Decider.class));
        assertThat(cmd.getValue().expectedUnitSalePrice()).isNull();
    }

    @Test
    @DisplayName("reject with a blank comment is left to the service (decision.commentRequired)")
    void rejectBlankCommentDelegates() throws Exception {
        mvc.perform(post("/api/position-approvals/client/rounds/11/reject")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"  \"}"))
                .andExpect(status().isNoContent());
        verify(approvalService).reject(eq(11L), eq("  "), any(Decider.class));
    }

    @Test
    @DisplayName("suggest and client-own delegate; client-own accepts an empty body")
    void suggestAndClientOwn() throws Exception {
        mvc.perform(post("/api/position-approvals/client/positions/8/suggest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"link\":\"https://shop.example/x\",\"comment\":\"cheaper\"}"))
                .andExpect(status().isNoContent());
        verify(approvalService).suggest(eq(8L), any(), any(Decider.class));

        mvc.perform(post("/api/position-approvals/client/positions/8/client-own"))
                .andExpect(status().isNoContent());
        verify(approvalService).declareClientOwn(eq(8L), eq(null), any(Decider.class));
    }
}
