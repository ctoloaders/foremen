package com.foremen.service;

import com.foremen.controller.model.Candidate;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkerTypeDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.dao.model.WorkerKind;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.team.TeamBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProjectMemberService#searchCandidates} (FOR-05-09 task 10.1, Requirement
 * 11). They stub {@link UserDao#searchCandidates} and the {@link ProjectAccessCache} to verify the
 * validation 400s (role / block / pagination / term length), the project-access 404 gate, the way
 * the service translates the {@code term} / {@code role} / {@code block} filters into the DAO query
 * arguments, the page-size default and 50-cap, and the {@link Candidate} mapping (including the
 * WORKERS-only worker attributes and the absence of any secret field).
 *
 * <p>The default authenticated caller is ADMIN so the step-4 {@code projectAccessCheck} is bypassed
 * and each filter / mapping test reaches the DAO; the 404 test re-authenticates a non-ADMIN caller.
 */
@ExtendWith(MockitoExtension.class)
class ProjectMemberServiceCandidateTest {

    @Mock
    private ProjectMemberDao projectMemberDao;
    @Mock
    private UserDao userDao;
    @Mock
    private RoleDao roleDao;
    @Mock
    private ProjectDao projectDao;
    @Mock
    private WorkerTypeDao workerTypeDao;
    @Mock
    private ProjectAccessCache projectAccessCache;
    @Mock
    private com.foremen.service.offer.NotificationService notificationService;

    @InjectMocks
    private ProjectMemberService service;

    private static final Long PROJECT_ID = 42L;

    @BeforeEach
    void authenticateAdmin() {
        authenticate("1", "ROLE_ADMIN");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
    }

    // --- validation: role (Req 11.8) ---

    @Test
    @DisplayName("searchCandidates raises 400 error.project.member.role.not.assignable for an unassignable role, issuing no query")
    void unassignableRoleRaisesBadRequest() {
        assertThatThrownBy(() -> service.searchCandidates(PROJECT_ID, null, "ADMIN", null, 0, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.role.not.assignable");
                });
        verify(userDao, never()).searchCandidates(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    // --- validation: block (Req 11.12) ---

    @Test
    @DisplayName("searchCandidates raises 400 error.project.member.block.invalid for a bad block, issuing no query")
    void badBlockRaisesBadRequest() {
        assertThatThrownBy(() -> service.searchCandidates(PROJECT_ID, null, null, "MANAGERS", 0, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.getMessageCode()).isEqualTo("error.project.member.block.invalid");
                });
        verify(userDao, never()).searchCandidates(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    // --- validation: pagination + term length (Req 11.9) ---

    @Test
    @DisplayName("searchCandidates raises 400 for a negative page index, issuing no query")
    void negativePageRaisesBadRequest() {
        assertThatThrownBy(() -> service.searchCandidates(PROJECT_ID, null, null, null, -1, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode())
                        .isEqualTo("error.project.member.candidate.page.invalid"));
        verify(userDao, never()).searchCandidates(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    @DisplayName("searchCandidates raises 400 for a page size below 1, issuing no query")
    void sizeBelowOneRaisesBadRequest() {
        assertThatThrownBy(() -> service.searchCandidates(PROJECT_ID, null, null, null, 0, 0))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode())
                        .isEqualTo("error.project.member.candidate.page.invalid"));
        verify(userDao, never()).searchCandidates(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    @DisplayName("searchCandidates raises 400 for a term longer than 100 characters after trimming, issuing no query")
    void termTooLongRaisesBadRequest() {
        String tooLong = " " + "x".repeat(101) + " "; // 101 non-space chars after trim
        assertThatThrownBy(() -> service.searchCandidates(PROJECT_ID, tooLong, null, null, 0, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode())
                        .isEqualTo("error.project.member.candidate.page.invalid"));
        verify(userDao, never()).searchCandidates(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    // --- project access gate (Req 11.6) ---

    @Test
    @DisplayName("searchCandidates raises 404 error.entity.not.found for a non-accessible project (non-ADMIN), issuing no query")
    void nonAccessibleProjectRaisesNotFound() {
        authenticate("5", "ROLE_MANAGER");
        when(projectAccessCache.get(5L)).thenReturn(java.util.Set.of(999L)); // PROJECT_ID not accessible

        assertThatThrownBy(() -> service.searchCandidates(PROJECT_ID, null, null, null, 0, null))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.getMessageCode()).isEqualTo("error.entity.not.found");
                });
        verify(userDao, never()).searchCandidates(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    // --- term trimming + lower-casing (Req 11.2) ---

    @Test
    @DisplayName("searchCandidates trims, lower-cases, and %-wraps a supplied term before the query")
    void termIsTrimmedLowercasedAndWrapped() {
        when(userDao.searchCandidates(eq(PROJECT_ID), any(), isNull(), isNull(), eq(false), any()))
                .thenReturn(emptyPage());

        service.searchCandidates(PROJECT_ID, "  AnNa  ", null, null, 0, null);

        ArgumentCaptor<String> term = ArgumentCaptor.forClass(String.class);
        verify(userDao).searchCandidates(eq(PROJECT_ID), term.capture(), isNull(), isNull(), eq(false), any());
        assertThat(term.getValue()).isEqualTo("%anna%");
    }

    @Test
    @DisplayName("searchCandidates treats a whitespace-only term as not supplied (null to the query)")
    void blankTermIsTreatedAsNotSupplied() {
        when(userDao.searchCandidates(eq(PROJECT_ID), isNull(), isNull(), isNull(), eq(false), any()))
                .thenReturn(emptyPage());

        service.searchCandidates(PROJECT_ID, "   ", null, null, 0, null);

        verify(userDao).searchCandidates(eq(PROJECT_ID), isNull(), isNull(), isNull(), eq(false), any());
    }

    // --- block -> role translation (Req 11.11) ---

    @Test
    @DisplayName("block=WORKERS translates to the WORKER blockRole and no exclude flag")
    void workersBlockTranslatesToWorkerRole() {
        when(userDao.searchCandidates(eq(PROJECT_ID), isNull(), isNull(), eq("WORKER"), eq(false), any()))
                .thenReturn(emptyPage());

        service.searchCandidates(PROJECT_ID, null, null, "WORKERS", 0, null);

        verify(userDao).searchCandidates(eq(PROJECT_ID), isNull(), isNull(), eq("WORKER"), eq(false), any());
    }

    @Test
    @DisplayName("block=CLIENTS translates to the CLIENT blockRole and no exclude flag")
    void clientsBlockTranslatesToClientRole() {
        when(userDao.searchCandidates(eq(PROJECT_ID), isNull(), isNull(), eq("CLIENT"), eq(false), any()))
                .thenReturn(emptyPage());

        service.searchCandidates(PROJECT_ID, null, null, "CLIENTS", 0, null);

        verify(userDao).searchCandidates(eq(PROJECT_ID), isNull(), isNull(), eq("CLIENT"), eq(false), any());
    }

    @Test
    @DisplayName("block=ADMIN_STAFF sets the exclude-worker-client flag and no blockRole")
    void adminStaffBlockSetsExcludeFlag() {
        when(userDao.searchCandidates(eq(PROJECT_ID), isNull(), isNull(), isNull(), eq(true), any()))
                .thenReturn(emptyPage());

        service.searchCandidates(PROJECT_ID, null, null, "admin_staff", 0, null); // case-insensitive

        verify(userDao).searchCandidates(eq(PROJECT_ID), isNull(), isNull(), isNull(), eq(true), any());
    }

    @Test
    @DisplayName("a supplied role passes through to the query unchanged alongside the block filter")
    void roleAndBlockApplyBothFilters() {
        when(userDao.searchCandidates(eq(PROJECT_ID), isNull(), eq("WORKER"), eq("WORKER"), eq(false), any()))
                .thenReturn(emptyPage());

        service.searchCandidates(PROJECT_ID, null, "WORKER", "WORKERS", 0, null);

        verify(userDao).searchCandidates(eq(PROJECT_ID), isNull(), eq("WORKER"), eq("WORKER"), eq(false), any());
    }

    // --- pagination: default + cap (Req 11.7) ---

    @Test
    @DisplayName("searchCandidates defaults the page size to 20 when none is supplied")
    void defaultsPageSizeToTwenty() {
        when(userDao.searchCandidates(eq(PROJECT_ID), isNull(), isNull(), isNull(), eq(false), any()))
                .thenReturn(emptyPage());

        service.searchCandidates(PROJECT_ID, null, null, null, 0, null);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(userDao).searchCandidates(eq(PROJECT_ID), isNull(), isNull(), isNull(), eq(false), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageable.getValue().getPageNumber()).isZero();
    }

    @Test
    @DisplayName("searchCandidates clamps a requested size above 50 down to 50 (never rejects it)")
    void clampsPageSizeToFifty() {
        when(userDao.searchCandidates(eq(PROJECT_ID), isNull(), isNull(), isNull(), eq(false), any()))
                .thenReturn(emptyPage());

        service.searchCandidates(PROJECT_ID, null, null, null, 2, 500);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(userDao).searchCandidates(eq(PROJECT_ID), isNull(), isNull(), isNull(), eq(false), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
    }

    // --- candidate mapping (Req 11.4, 11.5) ---

    @Test
    @DisplayName("searchCandidates maps a WORKER user to a Candidate with the WORKERS block, worker kind, and contact person, and carries the total count")
    void mapsWorkerCandidateWithWorkerAttributesAndTotal() {
        UserEntity worker = user(7L, "Firma Bud", "firma@example.com", UserStatus.INVITED, "WORKER", true);
        worker.setWorkerKind(WorkerKind.COMPANY);
        worker.setContactPerson("Jan Kowalski");
        worker.setNip("1234563218"); // present on the entity, but must NOT leak into the Candidate
        when(userDao.searchCandidates(eq(PROJECT_ID), isNull(), isNull(), isNull(), eq(false), any()))
                .thenReturn(new PageImpl<>(List.of(worker), PageRequest.of(0, 20), 1));

        Page<Candidate> page = service.searchCandidates(PROJECT_ID, null, null, null, 0, null);

        assertThat(page.getTotalElements()).isEqualTo(1);
        Candidate c = page.getContent().get(0);
        assertThat(c.userId()).isEqualTo(7L);
        assertThat(c.name()).isEqualTo("Firma Bud");
        assertThat(c.email()).isEqualTo("firma@example.com");
        assertThat(c.status()).isEqualTo("INVITED");
        assertThat(c.companyRoleCode()).isEqualTo("WORKER");
        assertThat(c.block()).isEqualTo(TeamBlock.WORKERS);
        assertThat(c.workerKind()).isEqualTo(WorkerKind.COMPANY);
        assertThat(c.contactPerson()).isEqualTo("Jan Kowalski");
        // The Candidate record carries no NIP/tag/worker-type/password field at all.
        assertThat(Candidate.class.getRecordComponents())
                .noneMatch(rc -> rc.getName().toLowerCase().contains("nip")
                        || rc.getName().toLowerCase().contains("tag")
                        || rc.getName().toLowerCase().contains("worketype")
                        || rc.getName().toLowerCase().contains("workertype")
                        || rc.getName().toLowerCase().contains("password")
                        || rc.getName().toLowerCase().contains("token")
                        || rc.getName().toLowerCase().contains("rate")
                        || rc.getName().toLowerCase().contains("cost"));
    }

    @Test
    @DisplayName("searchCandidates maps a MANAGER user to an ADMIN_STAFF Candidate with no worker attributes")
    void mapsAdminStaffCandidateWithoutWorkerAttributes() {
        UserEntity manager = user(9L, "Anna Nowak", "anna@example.com", UserStatus.ACTIVE, "MANAGER", true);
        manager.setWorkerKind(WorkerKind.COMPANY); // stored but irrelevant for a non-WORKERS candidate
        when(userDao.searchCandidates(eq(PROJECT_ID), isNull(), isNull(), isNull(), eq(false), any()))
                .thenReturn(new PageImpl<>(List.of(manager), PageRequest.of(0, 20), 1));

        Candidate c = service.searchCandidates(PROJECT_ID, null, null, null, 0, null).getContent().get(0);

        assertThat(c.block()).isEqualTo(TeamBlock.ADMIN_STAFF);
        assertThat(c.companyRoleCode()).isEqualTo("MANAGER");
        // Worker attributes are populated only for the WORKERS block.
        assertThat(c.workerKind()).isNull();
        assertThat(c.contactPerson()).isNull();
    }

    // --- helpers ---

    private static Page<UserEntity> emptyPage() {
        return new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
    }

    private static UserEntity user(Long id, String name, String email, UserStatus status,
                                   String roleCode, boolean active) {
        RoleEntity role = new RoleEntity();
        role.setCode(roleCode);
        role.setNamePL(roleCode);
        role.setNameRU(roleCode);
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setName(name);
        user.setEmail(email);
        user.setStatus(status);
        user.setRole(role);
        user.setActive(active);
        return user;
    }

    private static void authenticate(String principalName, String... authorities) {
        var granted = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .map(a -> (org.springframework.security.core.GrantedAuthority) a)
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principalName, "n/a", granted));
    }
}
