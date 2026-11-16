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
import java.util.Map;
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
import com.foremen.controller.model.staff.StaffViewAssembler;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.approval.DecidedSide;
import com.foremen.service.procurement.approval.PositionApprovalBulkExecutor;
import com.foremen.service.procurement.approval.PositionApprovalService;
import com.foremen.service.procurement.approval.PositionApprovalService.AcceptCommand;
import com.foremen.service.procurement.approval.PositionApprovalService.Decider;
import com.foremen.service.procurement.approval.PositionApprovalService.SendChoiceCommand;
import com.foremen.service.procurement.approval.StaffApprovalData;
import com.foremen.service.procurement.approval.StaffApprovalFilter;
import com.foremen.service.procurement.approval.StaffApprovalQueryService;
import com.foremen.service.procurement.position.PositionAudienceResolver;
import com.foremen.service.procurement.position.PositionPriceVisibility.Audience;

import jakarta.persistence.EntityManager;

/**
 * FOR-11-02 task 5.2: ABAC mapping, startup completeness, role checks and request handling of
 * {@link PositionApprovalStaffController} with a standalone MockMvc.
 *
 * <p>Validates: Requirements 1.5, 4.5, 6.1, 6.7, 9.1, 10.3, 12.1, 13.1, 13.2, 14.2
 */
@DisplayName("PositionApprovalStaffController ABAC + request handling")
class PositionApprovalStaffControllerAbacTest {

    private static final long USER_ID = 42L;

    private final PermissionResolver resolver = new PermissionResolver();
    private StaffApprovalQueryService queryService;
    private PositionApprovalService approvalService;
    private PositionAudienceResolver audienceResolver;
    private ProcurementProjectAccess projectAccess;
    private PositionApprovalBulkExecutor bulkExecutor;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        queryService = mock(StaffApprovalQueryService.class);
        approvalService = mock(PositionApprovalService.class);
        audienceResolver = mock(PositionAudienceResolver.class);
        projectAccess = mock(ProcurementProjectAccess.class);
        bulkExecutor = mock(PositionApprovalBulkExecutor.class);
        PositionApprovalStaffController controller = new PositionApprovalStaffController(queryService,
                new StaffViewAssembler(mock(com.foremen.service.image.ImageStorage.class)), audienceResolver, approvalService, projectAccess,
                mock(EntityManager.class), Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneOffset.UTC),
                bulkExecutor);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        login("MANAGER", Audience.STAFF_FULL);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void login(String role, Audience audience) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                USER_ID, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        when(audienceResolver.current()).thenReturn(audience);
    }

    @Test
    @DisplayName("every handler is explicitly annotated, COMPLETE, with the design operation")
    void permissionMapping() {
        Map<String, String> expected = Map.ofEntries(
                Map.entry("list", "READ"), Map.entry("rounds", "READ"),
                Map.entry("send", "CREATE"), Map.entry("sendChoice", "CREATE"),
                Map.entry("proposeAnalog", "CREATE"),
                Map.entry("withdraw", "UPDATE"), Map.entry("changeDeadline", "UPDATE"),
                Map.entry("declineSuggestion", "UPDATE"),
                Map.entry("decideOnBehalf", "APPROVE"), Map.entry("decideOnBehalfBulk", "APPROVE"),
                Map.entry("withdrawBulk", "UPDATE"), Map.entry("clientOwnOnBehalf", "APPROVE"));
        int handlers = 0;
        for (Method m : PositionApprovalStaffController.class.getDeclaredMethods()) {
            RequiresPermission rp = m.getAnnotation(RequiresPermission.class);
            if (rp == null) {
                continue;
            }
            handlers++;
            HandlerMethod hm = new HandlerMethod(new PositionApprovalStaffController(null, null, null, null,
                    null, null, null, null), m);
            assertThat(resolver.classifyCompleteness(hm)).as(m.getName()).isEqualTo(Completeness.COMPLETE);
            assertThat(rp.resource()).isEqualTo("POSITION_APPROVALS");
            assertThat(rp.operation()).as(m.getName()).isEqualTo(expected.get(m.getName()));
        }
        assertThat(handlers).isEqualTo(expected.size());
    }

    @Test
    @DisplayName("staff list passes filters and returns an empty list")
    void listAllowed() throws Exception {
        when(projectAccess.allowedProjectIds(USER_ID)).thenReturn(Set.of(5L));
        when(queryService.loadStaffData(eq(5L), any(StaffApprovalFilter.class)))
                .thenReturn(new StaffApprovalData(List.of(), Map.of(), Map.of()));

        mvc.perform(get("/api/position-approvals/staff/projects/5")
                        .param("overdue", "true").param("blockNo", "2").param("sourcingPath", "AGREED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        ArgumentCaptor<StaffApprovalFilter> captor = ArgumentCaptor.forClass(StaffApprovalFilter.class);
        verify(queryService).loadStaffData(eq(5L), captor.capture());
        assertThat(captor.getValue().overdue()).isTrue();
        assertThat(captor.getValue().blockNo()).isEqualTo(2);
        assertThat(captor.getValue().sourcingPath()).isNotNull();
    }

    @Test
    @DisplayName("a CLIENT audience gets staff.forbidden on the staff reads, before any query")
    void clientForbidden() {
        login("CLIENT", Audience.CLIENT);

        assertThatThrownBy(() -> mvc.perform(get("/api/position-approvals/staff/projects/5")))
                .hasRootCauseInstanceOf(ForemenApiException.class)
                .hasRootCauseMessage(StaffViewAssembler.ERROR_FORBIDDEN);
        assertThatThrownBy(() -> mvc.perform(get("/api/position-approvals/staff/positions/8/rounds")))
                .hasRootCauseInstanceOf(ForemenApiException.class);
        verify(queryService, never()).loadStaffData(any(), any());
        verify(queryService, never()).loadStaffPosition(any());
    }

    @Test
    @DisplayName("a foreign project is denied for a manager")
    void foreignProjectDenied() {
        when(projectAccess.allowedProjectIds(USER_ID)).thenReturn(Set.of(5L));

        assertThatThrownBy(() -> mvc.perform(get("/api/position-approvals/staff/projects/9")))
                .hasRootCauseInstanceOf(ForemenApiException.class);
        verify(queryService, never()).loadStaffData(any(), any());
    }

    @Test
    @DisplayName("send, send-choice and withdraw delegate to the service")
    void writesDelegate() throws Exception {
        when(approvalService.send(any())).thenReturn(List.of(11L, 12L));
        mvc.perform(post("/api/position-approvals/staff/send").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"positionIds\":[1,2],\"decisionDeadline\":\"2026-06-10\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1]").value(12));

        when(approvalService.sendChoice(any())).thenReturn(30L);
        mvc.perform(post("/api/position-approvals/staff/send-choice").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"positionId\":3,\"materialIds\":[7,8]}"))
                .andExpect(status().isOk());
        ArgumentCaptor<SendChoiceCommand> choice = ArgumentCaptor.forClass(SendChoiceCommand.class);
        verify(approvalService).sendChoice(choice.capture());
        assertThat(choice.getValue().materialIds()).containsExactly(7L, 8L);

        mvc.perform(post("/api/position-approvals/staff/rounds/11/withdraw")).andExpect(status().isNoContent());
        verify(approvalService).withdraw(11L);
    }

    @Test
    @DisplayName("send with an empty position list is a 400")
    void sendValidation() throws Exception {
        mvc.perform(post("/api/position-approvals/staff/send").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"positionIds\":[]}"))
                .andExpect(status().isBadRequest());
        verify(approvalService, never()).send(any());
    }

    @Test
    @DisplayName("decide-on-behalf ACCEPT uses a staffOnBehalf decider and the comment")
    void decideOnBehalfAccept() throws Exception {
        mvc.perform(post("/api/position-approvals/staff/rounds/11/decide-on-behalf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ACCEPT\",\"optionId\":4,\"expectedUnitSalePrice\":12.50,"
                                + "\"comment\":\"by phone\"}"))
                .andExpect(status().isNoContent());

        ArgumentCaptor<AcceptCommand> cmd = ArgumentCaptor.forClass(AcceptCommand.class);
        ArgumentCaptor<Decider> decider = ArgumentCaptor.forClass(Decider.class);
        verify(approvalService).accept(eq(11L), cmd.capture(), decider.capture());
        assertThat(cmd.getValue().comment()).isEqualTo("by phone");
        assertThat(cmd.getValue().expectedUnitSalePrice()).isEqualByComparingTo(new BigDecimal("12.50"));
        assertThat(decider.getValue().side()).isEqualTo(DecidedSide.STAFF);
        assertThat(decider.getValue().onBehalf()).isTrue();
        assertThat(decider.getValue().userId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("decide-on-behalf REJECT delegates to reject; a blank comment is left to the service")
    void decideOnBehalfReject() throws Exception {
        mvc.perform(post("/api/position-approvals/staff/rounds/11/decide-on-behalf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"comment\":\"no\"}"))
                .andExpect(status().isNoContent());
        verify(approvalService).reject(eq(11L), eq("no"), any(Decider.class));

        mvc.perform(post("/api/position-approvals/staff/rounds/11/decide-on-behalf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"comment\":\" \"}"))
                .andExpect(status().isNoContent());
        verify(approvalService).reject(eq(11L), eq(" "), any(Decider.class));
    }

    @Test
    @DisplayName("on-behalf endpoints reject every role except MANAGER and ADMIN with onBehalf.forbidden")
    void onBehalfRoleCheck() throws Exception {
        for (String role : List.of("CLIENT", "BUYER")) {
            login(role, role.equals("CLIENT") ? Audience.CLIENT : Audience.STAFF_FULL);
            assertThatThrownBy(() -> mvc.perform(post(
                    "/api/position-approvals/staff/rounds/11/decide-on-behalf")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"decision\":\"REJECT\",\"comment\":\"x\"}")))
                    .hasRootCauseMessage(PositionApprovalStaffController.ERROR_ON_BEHALF_FORBIDDEN);
            assertThatThrownBy(() -> mvc.perform(post(
                    "/api/position-approvals/staff/positions/8/client-own-on-behalf")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"x\"}")))
                    .hasRootCauseMessage(PositionApprovalStaffController.ERROR_ON_BEHALF_FORBIDDEN);
        }
        verify(approvalService, never()).reject(any(), any(), any());
        verify(approvalService, never()).declareClientOwn(any(), any(), any());

        login("ADMIN", Audience.STAFF_FULL);
        mvc.perform(post("/api/position-approvals/staff/positions/8/client-own-on-behalf")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"phone\"}"))
                .andExpect(status().isNoContent());
        verify(approvalService).declareClientOwn(eq(8L), eq("phone"), any(Decider.class));
    }

    @Test
    @DisplayName("deadline, decline and propose-analog delegate")
    void otherWrites() throws Exception {
        mvc.perform(post("/api/position-approvals/staff/rounds/11/deadline")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decisionDeadline\":\"2026-06-20\"}"))
                .andExpect(status().isNoContent());
        verify(approvalService).changeDeadline(11L, java.time.LocalDate.of(2026, 6, 20));

        mvc.perform(post("/api/position-approvals/staff/suggestions/15/decline")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"not suitable\"}"))
                .andExpect(status().isNoContent());
        verify(approvalService).declineSuggestion(15L, "not suitable");

        when(approvalService.proposeAnalog(eq(8L), any())).thenReturn(99L);
        mvc.perform(post("/api/position-approvals/staff/positions/8/propose-analog")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"materialId\":5,\"unitSalePrice\":20.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(99));
    }

    @Test
    @DisplayName("bulk endpoints delegate to the executor, reject >200 items and non-staff roles")
    void bulkEndpoints() throws Exception {
        when(bulkExecutor.acceptOnBehalf(any(), any(Decider.class))).thenReturn(
                new PositionApprovalBulkExecutor.Result(List.of(1L), List.of(
                        new PositionApprovalBulkExecutor.Failure(2L, "error.positionApproval.round.notFound"))));
        mvc.perform(post("/api/position-approvals/staff/rounds/decide-on-behalf-bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"positionId\":1,\"expectedUnitSalePrice\":10.00},{\"positionId\":2}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded[0]").value(1))
                .andExpect(jsonPath("$.failed[0].positionId").value(2))
                .andExpect(jsonPath("$.failed[0].errorKey").value("error.positionApproval.round.notFound"));

        when(bulkExecutor.withdraw(any())).thenReturn(
                new PositionApprovalBulkExecutor.Result(List.of(5L), List.of()));
        mvc.perform(post("/api/position-approvals/staff/rounds/withdraw-bulk")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"roundId\":9}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded[0]").value(5));
        verify(bulkExecutor).withdraw(List.of(9L));

        StringBuilder many = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 201; i++) {
            many.append(i == 0 ? "" : ",").append("{\"roundId\":").append(i + 1).append('}');
        }
        many.append("]}");
        assertThatThrownBy(() -> mvc.perform(post("/api/position-approvals/staff/rounds/withdraw-bulk")
                        .contentType(MediaType.APPLICATION_JSON).content(many.toString())))
                .hasRootCauseInstanceOf(ForemenApiException.class)
                .rootCause().hasMessage(PositionApprovalBulkExecutor.ERROR_TOO_MANY);

        login("CLIENT", Audience.CLIENT);
        assertThatThrownBy(() -> mvc.perform(post("/api/position-approvals/staff/rounds/decide-on-behalf-bulk")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"positionId\":1}]}")))
                .hasRootCauseInstanceOf(ForemenApiException.class)
                .rootCause().hasMessage(PositionApprovalStaffController.ERROR_ON_BEHALF_FORBIDDEN);
    }
}
