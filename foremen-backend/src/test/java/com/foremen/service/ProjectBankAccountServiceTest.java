package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.link.BankLedgerAudit;

/** Unit tests for {@link ProjectBankAccountService} (FOR-11-06 R4.1, R4.2). */
class ProjectBankAccountServiceTest {

    private static final String PL_IBAN = "PL61109010140000071219812874";

    private ProjectDao projectDao;
    private ProjectMemberDao memberDao;
    private BankLedgerAudit audit;
    private ProjectBankAccountService service;
    private ProjectEntity project;

    @BeforeEach
    void setUp() {
        projectDao = mock(ProjectDao.class);
        memberDao = mock(ProjectMemberDao.class);
        audit = mock(BankLedgerAudit.class);
        service = new ProjectBankAccountService(projectDao, memberDao, audit);
        project = new ProjectEntity();
        when(projectDao.findById(7L)).thenReturn(Optional.of(project));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void login(String name, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                name, "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private void member(long userId, String projectRole, AssignmentStatus status) {
        ProjectMemberEntity m = new ProjectMemberEntity();
        RoleEntity role = new RoleEntity();
        role.setCode(projectRole);
        m.setProjectRole(role);
        m.setAssignmentStatus(status);
        when(memberDao.findByUserIdAndProjectId(userId, 7L)).thenReturn(Optional.of(m));
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminSetsNormalizedIbanAndAuditIsMasked() {
        login("1", "ADMIN");
        project.setBankAccount("DE89370400440532013000");

        String stored = service.set(7L, "pl61 1090 1014 0000 0712 1981 2874");

        assertThat(stored).isEqualTo(PL_IBAN);
        assertThat(project.getBankAccount()).isEqualTo(PL_IBAN);
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(audit).write(eq(ProjectEntity.class), eq(7L), eq("UPDATE"), before.capture(), after.capture());
        assertThat(before.getValue().get("bankAccount")).isEqualTo("******************3000");
        assertThat((String) after.getValue().get("bankAccount")).endsWith("2874").startsWith("*")
                .doesNotContain("PL61");
    }

    @Test
    void activeManagerMemberMayClearTheAccount() {
        login("5", "MANAGER");
        member(5L, "MANAGER", AssignmentStatus.ACTIVE);
        project.setBankAccount(PL_IBAN);

        assertThat(service.set(7L, null)).isNull();
        assertThat(project.getBankAccount()).isNull();
    }

    @Test
    void inactiveManagerMemberIsForbidden() {
        login("5", "MANAGER");
        member(5L, "MANAGER", AssignmentStatus.INACTIVE);

        assertThatThrownBy(() -> service.set(7L, PL_IBAN))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(projectDao, never()).save(any());
    }

    @Test
    void nonManagerMemberAndNonMemberAreForbidden() {
        login("6", "FOREMAN");
        member(6L, "FOREMAN", AssignmentStatus.ACTIVE);
        assertThatThrownBy(() -> service.set(7L, PL_IBAN)).isInstanceOf(ForemenApiException.class);

        login("8", "MANAGER");
        when(memberDao.findByUserIdAndProjectId(8L, 7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.set(7L, PL_IBAN)).isInstanceOf(ForemenApiException.class);
    }

    @Test
    void clientRoleAndUnauthenticatedCallerAreForbiddenWithoutAudit() {
        login("9", "CLIENT");
        when(memberDao.findByUserIdAndProjectId(9L, 7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.set(7L, PL_IBAN))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service.set(7L, PL_IBAN))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

        verify(projectDao, never()).save(any());
        verify(audit, never()).write(any(), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void clearingWritesMaskedBeforeAndNullAfter() {
        login("1", "ADMIN");
        project.setBankAccount(PL_IBAN);

        service.set(7L, "  ");

        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(audit).write(eq(ProjectEntity.class), eq(7L), eq("UPDATE"), before.capture(), after.capture());
        assertThat((String) before.getValue().get("bankAccount")).endsWith("2874").doesNotContain("PL61");
        assertThat(after.getValue()).containsEntry("bankAccount", null);
    }

    @Test
    void invalidIbanIsRejectedAndNothingSaved() {
        login("1", "ADMIN");

        assertThatThrownBy(() -> service.set(7L, "PL00123"))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(projectDao, never()).save(any());
    }

    @Test
    void unknownProjectIsNotFound() {
        login("1", "ADMIN");
        when(projectDao.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.set(99L, PL_IBAN))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void maskKeepsOnlyLastFour() {
        assertThat(ProjectBankAccountService.mask(null)).isNull();
        assertThat(ProjectBankAccountService.mask("ABCD")).isEqualTo("****");
        assertThat(ProjectBankAccountService.mask(PL_IBAN)).endsWith("2874").startsWith("*")
                .hasSize(PL_IBAN.length());
    }
}
