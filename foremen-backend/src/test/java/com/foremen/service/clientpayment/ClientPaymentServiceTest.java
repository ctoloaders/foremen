package com.foremen.service.clientpayment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.dao.ClientPaymentDao;
import com.foremen.dao.ClientProformaLineDao;
import com.foremen.dao.model.ClientPaymentEntity;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.access.ProcurementProjectAccess;
import com.foremen.service.procurement.proforma.ProformaPdfService;

/**
 * FOR-11-06 task 2.5 (R1.7, R3.4): per-kind authorization and project scope of {@link ClientPaymentService}.
 *
 * <p>Validates: Requirements 1.7, 3.4
 */
class ClientPaymentServiceTest {

    private final ClientPaymentDao dao = mock(ClientPaymentDao.class);
    private final ProcurementProjectAccess access = mock(ProcurementProjectAccess.class);
    private final ClientPaymentService service = new ClientPaymentService(dao, mock(ClientProformaLineDao.class),
            mock(ClientPaymentQueryService.class), mock(ClientPaymentAssembler.class),
            new ClientPaymentAudienceResolver(), access, mock(ProformaPdfService.class),
            Clock.fixed(Instant.parse("2026-05-01T00:00:00Z"), ZoneOffset.UTC));

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void login(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "7", null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private void materialsPayment(long id, long projectId) {
        ClientPaymentEntity p = new ClientPaymentEntity(ClientPaymentKind.MATERIALS, "DRAFT");
        p.setId(id);
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        p.setProject(project);
        ClientProformaEntity pf = new ClientProformaEntity();
        pf.setId(900L + id);
        p.setProforma(pf);
        when(dao.findById(id)).thenReturn(Optional.of(p));
    }

    private static void assertStatus(Runnable r, HttpStatus expected) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(ForemenApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(expected));
    }

    @Test
    void clientGets403OnEveryStaffCheck() {
        login("CLIENT");
        assertStatus(service::requireStaff, HttpStatus.FORBIDDEN);
        assertStatus(service::requireWorks, HttpStatus.FORBIDDEN);
        assertStatus(service::requireMaterialsWrite, HttpStatus.FORBIDDEN);
    }

    @Test
    void financierMayNotWriteMaterialsButMayWorkWithSchedules() {
        login("FINANCIER");
        service.requireWorks();
        assertStatus(service::requireMaterialsWrite, HttpStatus.FORBIDDEN);
        materialsPayment(1L, 10L);
        when(access.allowedProjectIds(7L)).thenReturn(Set.of(10L));
        assertStatus(() -> service.materialsProformaIdForWrite(1L), HttpStatus.FORBIDDEN);
    }

    @Test
    void buyerCannotTouchWorksAndForemanCannotWriteMaterials() {
        login("BUYER");
        assertStatus(service::requireWorks, HttpStatus.FORBIDDEN);
        login("FOREMAN");
        assertStatus(service::requireMaterialsWrite, HttpStatus.FORBIDDEN);
    }

    @Test
    void managerResolvesTheProformaIdOfAnInScopePayment() {
        login("MANAGER");
        materialsPayment(2L, 10L);
        when(access.allowedProjectIds(7L)).thenReturn(Set.of(10L));
        assertThat(service.materialsProformaIdForWrite(2L)).isEqualTo(902L);
    }

    @Test
    void foreignProjectAndMissingPaymentAnswer404() {
        login("MANAGER");
        materialsPayment(3L, 99L);
        when(access.allowedProjectIds(7L)).thenReturn(Set.of(10L));
        assertStatus(() -> service.get(3L), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.get(404L), HttpStatus.NOT_FOUND);
    }
}
