package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.dao.model.ProcessingStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProcurementPaymentChoice;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.bank.cash.CashLedgerService;
import com.foremen.service.model.ProcurementDocumentServiceExtendedModel;
import com.foremen.service.permission.ForemenPermissionEvaluator;

import jakarta.persistence.EntityManager;

/**
 * FOR-07-03 task 4.4 (Requirements 7.2, 7.3, 7.4, 7.6, 10.5) - unit tests for the explicit membership
 * check, forbidden payment choices, cash-account handling and the status read.
 */
class ExpenseUploadServiceTest {

    private ProcurementDocumentService documentService;
    private ProjectAccessCache accessCache;
    private ForemenPermissionEvaluator evaluator;
    private EntityManager entityManager;
    private ExpenseUploadService service;
    private final MockMultipartFile file =
            new MockMultipartFile("file", "a.png", "image/png", new byte[] {1, 2, 3});

    @BeforeEach
    void setUp() {
        documentService = mock(ProcurementDocumentService.class);
        accessCache = mock(ProjectAccessCache.class);
        evaluator = mock(ForemenPermissionEvaluator.class);
        entityManager = mock(EntityManager.class);
        service = new ExpenseUploadService(documentService, accessCache, evaluator,
                mock(CashLedgerService.class), entityManager);
        when(documentService.upload(any(), any(), any(), any()))
                .thenReturn(new ProcurementDocumentServiceExtendedModel());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void login(long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(userId), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private ProcurementDocumentService.UploadPaymentRequest pay(ProcurementPaymentChoice c) {
        return new ProcurementDocumentService.UploadPaymentRequest(c, null, null, null);
    }

    @Test
    void ledgerDrivenPaymentChoicesAreRejectedBeforeAnythingElse() {
        login(5, "WORKER");
        for (ProcurementPaymentChoice choice : List.of(ProcurementPaymentChoice.LINKED_FROM_LEDGER,
                ProcurementPaymentChoice.FIND_BANK_TRANSACTION)) {
            assertThatThrownBy(() -> service.create(file, null, null, pay(choice)))
                    .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(e.getMessage()).contains("paymentChoiceNotAllowed");
                    });
        }
        verify(documentService, never()).upload(any(), any(), any(), any());
    }

    @Test
    void nonMemberGetsNotFoundForProject() {
        login(5, "WORKER");
        when(accessCache.get(5L)).thenReturn(Set.of(1L));
        assertThatThrownBy(() -> service.create(file, 99L, null, null))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(documentService, never()).upload(any(), any(), any(), any());
    }

    @Test
    void memberUploadsAndDelegatesWithCategory() {
        login(5, "WORKER");
        when(accessCache.get(5L)).thenReturn(Set.of(7L));
        service.create(file, 7L, 3L, null);
        verify(documentService).upload(eq(7L), eq(file), eq(3L), any());
    }

    @Test
    void adminSkipsMembershipAndNoProjectSkipsCache() {
        login(1, "ADMIN");
        service.create(file, 99L, null, pay(ProcurementPaymentChoice.NOT_LINKED));
        login(5, "WORKER");
        service.create(file, null, null, null);
        verify(accessCache, never()).get(any());
        verify(documentService).upload(eq(99L), eq(file), eq(null), any());
        verify(documentService).upload(eq(null), eq(file), eq(null), any());
    }

    @Test
    void cashAccountIgnoredWithoutLedgerReadButKeptWithIt() {
        login(5, "WORKER");
        when(evaluator.isAllowed("WORKER", "BANK_LEDGER", "READ")).thenReturn(false);
        var cash = new ProcurementDocumentService.UploadPaymentRequest(
                ProcurementPaymentChoice.PAID_CASH, 11L, new BigDecimal("10"), null);
        service.create(file, null, null, cash);

        login(6, "FINANCIER");
        when(evaluator.isAllowed("FINANCIER", "BANK_LEDGER", "READ")).thenReturn(true);
        service.create(file, null, null, cash);

        ArgumentCaptor<ProcurementDocumentService.UploadPaymentRequest> captor =
                ArgumentCaptor.forClass(ProcurementDocumentService.UploadPaymentRequest.class);
        verify(documentService, org.mockito.Mockito.times(2)).upload(any(), any(), any(), captor.capture());
        assertThat(captor.getAllValues().get(0).cashAccountId()).isNull();
        assertThat(captor.getAllValues().get(0).cashAmount()).isEqualByComparingTo("10");
        assertThat(captor.getAllValues().get(1).cashAccountId()).isEqualTo(11L);
    }

    @Test
    void statusVisibleToUploaderOrCompanyReaderOnly() {
        ProcurementDocumentEntity doc = new ProcurementDocumentEntity();
        doc.setId(40L);
        doc.setStatus(ProcessingStatus.PARSED);
        UserEntity uploader = new UserEntity();
        uploader.setId(5L);
        doc.setUploadedBy(uploader);
        when(entityManager.find(ProcurementDocumentEntity.class, 40L)).thenReturn(doc);

        login(5, "WORKER");
        assertThat(service.status(40L).status()).isEqualTo(ProcessingStatus.PARSED);

        login(8, "WORKER");
        when(evaluator.isAllowed("WORKER", "COMPANY_PURCHASES", "READ")).thenReturn(false);
        assertThatThrownBy(() -> service.status(40L)).isInstanceOfSatisfying(ForemenApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        login(9, "FINANCIER");
        when(evaluator.isAllowed("FINANCIER", "COMPANY_PURCHASES", "READ")).thenReturn(true);
        assertThat(service.status(40L).id()).isEqualTo(40L);
    }
}
