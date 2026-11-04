package com.foremen.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.config.image.ImageStorageProperties;
import com.foremen.dao.ProcurementDocumentDao;
import com.foremen.dao.model.DocumentPaymentStatus;
import com.foremen.dao.model.MatchStatus;
import com.foremen.dao.model.MatchTier;
import com.foremen.dao.model.ProcessingStatus;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProcurementPaymentChoice;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.model.ProcurementDocumentServiceExtendedModel;
import com.foremen.service.model.mapper.ProcurementDocumentServiceMapper;
import com.foremen.service.ocr.OcrResult;
import com.foremen.service.ocr.OcrService;
import com.foremen.service.ocr.ParsedLine;
import com.foremen.service.procurement.ExtractedLine;
import com.foremen.service.procurement.MatchEngine;
import com.foremen.service.procurement.MatchResult;
import com.foremen.service.procurement.PlannedMaterialCandidateProvider;
import com.foremen.service.procurement.ProcurementCashPayment;
import com.foremen.service.procurement.ProcurementDocumentParsedEvent;
import com.foremen.service.procurement.ProcurementLedgerEntryDto;
import com.foremen.service.procurement.ProcurementOcrRequested;
import com.foremen.service.procurement.ReprocessLinePartitioner;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FOR-07-04 task 6.11 — the FOR-06-03/04 procurement seams on {@link ProcurementDocumentService}:
 * the upload {@code paymentChoice} (PAID_CASH delegates to the cash hook, every other choice is
 * FOR-06-03-equivalent), {@code markPaidInCash} amount / date resolution and the UNMATCHED guard, and
 * the {@link ProcurementDocumentParsedEvent} + invoice-field persistence on the {@code PARSED}
 * transition. Collaborators are mocked; the cash hook is a recording handler.
 *
 * <p>Validates: Requirements 18.2, 18.3, 28.1, 28.2, 28.4, 28.5, 16.5
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProcurementDocumentPaymentSeamsTest {

    private static final long PROJECT_ID = 7L;
    private static final long DOCUMENT_ID = 900L;
    private static final OffsetDateTime UPLOADED_AT =
            OffsetDateTime.of(2024, 5, 20, 10, 0, 0, 0, ZoneOffset.UTC);

    @Mock private ProcurementDocumentDao documentDao;
    @Mock private ProjectAccessCache projectAccessCache;
    @Mock private ProcurementDocumentServiceMapper serviceMapper;
    @Mock private AuditLogDao auditLogDao;
    @Mock private EntityManager entityManager;
    @Mock private ImageStorage imageStorage;
    @Mock private OcrService ocrService;
    @Mock private MatchEngine matchEngine;
    @Mock private PlannedMaterialCandidateProvider candidateProvider;
    @Mock private ApplicationEventPublisher eventPublisher;

    private final List<ProcurementCashPayment> cashPayments = new ArrayList<>();
    private ProcurementDocumentService service;
    private ProcurementDocumentEntity document;

    @BeforeEach
    void setUp() {
        service = new ProcurementDocumentService(
                documentDao, projectAccessCache, serviceMapper, auditLogDao, entityManager,
                imageStorage, ocrService, matchEngine, candidateProvider, new ReprocessLinePartitioner(),
                eventPublisher, new ImageStorageProperties(null, null, null, null, null, null, null));
        service.setCashPaymentHandler(cashPayments::add);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT_ID);
        project.setStatus(ProjectStatus.ACTIVE);
        when(entityManager.find(ProjectEntity.class, PROJECT_ID)).thenReturn(project);

        document = new ProcurementDocumentEntity();
        document.setId(DOCUMENT_ID);
        document.setProject(project);
        document.setStatus(ProcessingStatus.PARSED);
        document.setRawObjectKey("procurement/7/raw.png");
        document.setSourceMimeType("image/png");
        document.setUploadedAt(UPLOADED_AT);
        when(documentDao.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));
        when(documentDao.save(any(ProcurementDocumentEntity.class))).thenAnswer(inv -> {
            ProcurementDocumentEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(DOCUMENT_ID);
            }
            return e;
        });

        when(imageStorage.isConfigured()).thenReturn(true);
        when(imageStorage.storeObject(any(), anyString())).thenReturn("procurement/7/raw-key.png");
        when(ocrService.isEnabled()).thenReturn(true);
        when(serviceMapper.toServiceExtendedModel(any())).thenAnswer(inv -> readModel(inv.getArgument(0)));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static ProcurementDocumentServiceExtendedModel readModel(ProcurementDocumentEntity e) {
        ProcurementDocumentServiceExtendedModel m = new ProcurementDocumentServiceExtendedModel();
        m.setId(e.getId());
        m.setUploadedAt(e.getUploadedAt());
        m.setDocumentTotal(e.getDocumentTotal());
        m.setLines(null);
        return m;
    }

    private static MockMultipartFile png() {
        return new MockMultipartFile("file", "r.png", "image/png", new byte[] {1, 2, 3});
    }

    // --- Upload payment choice (R28.1, R28.5, R16.5) ---------------------------------------------

    @Test
    @DisplayName("the FOR-06-03 upload and a null / NOT_LINKED / FIND / LINKED_FROM_LEDGER choice never call the cash hook")
    void nonCashChoicesDoNotPay() {
        service.upload(PROJECT_ID, png());
        service.upload(PROJECT_ID, png(), null);
        for (ProcurementPaymentChoice choice : List.of(ProcurementPaymentChoice.NOT_LINKED,
                ProcurementPaymentChoice.FIND_BANK_TRANSACTION, ProcurementPaymentChoice.LINKED_FROM_LEDGER)) {
            service.upload(PROJECT_ID, png(), new ProcurementDocumentService.UploadPaymentRequest(
                    choice, 3L, new BigDecimal("10.00"), LocalDate.of(2024, 1, 1)));
        }
        assertThat(cashPayments).isEmpty();
        // The OCR hand-off is unchanged for every upload.
        verify(eventPublisher, org.mockito.Mockito.times(5)).publishEvent(any(ProcurementOcrRequested.class));
    }

    @Test
    @DisplayName("PAID_CASH delegates the cash payment with the given account, amount and date")
    void paidCashDelegates() {
        ProcurementDocumentServiceExtendedModel result = service.upload(PROJECT_ID, png(),
                new ProcurementDocumentService.UploadPaymentRequest(ProcurementPaymentChoice.PAID_CASH,
                        3L, new BigDecimal("99.90"), LocalDate.of(2024, 5, 18)));

        assertThat(result.getId()).isEqualTo(DOCUMENT_ID);
        assertThat(cashPayments).containsExactly(new ProcurementCashPayment(
                DOCUMENT_ID, 3L, new BigDecimal("99.90"), LocalDate.of(2024, 5, 18)));
    }

    @Test
    @DisplayName("PAID_CASH without a date uses the upload date; without an account the default account (null)")
    void paidCashDefaultsDate() {
        service.upload(PROJECT_ID, png(), new ProcurementDocumentService.UploadPaymentRequest(
                ProcurementPaymentChoice.PAID_CASH, null, new BigDecimal("5"), null));

        assertThat(cashPayments).hasSize(1);
        ProcurementCashPayment payment = cashPayments.get(0);
        assertThat(payment.cashAccountId()).isNull();
        assertThat(payment.date()).isNotNull();
    }

    @Test
    @DisplayName("PAID_CASH with a missing / non-positive amount is rejected before anything is stored")
    void paidCashRequiresAmount() {
        assertThatThrownBy(() -> service.upload(PROJECT_ID, png(),
                new ProcurementDocumentService.UploadPaymentRequest(
                        ProcurementPaymentChoice.PAID_CASH, null, null, null)))
                .isInstanceOf(ForemenApiException.class)
                .hasMessageContaining(ProcurementDocumentService.CASH_AMOUNT_REQUIRED);
        assertThatThrownBy(() -> service.upload(PROJECT_ID, png(),
                new ProcurementDocumentService.UploadPaymentRequest(
                        ProcurementPaymentChoice.PAID_CASH, null, BigDecimal.ZERO, null)))
                .isInstanceOf(ForemenApiException.class)
                .hasMessageContaining(ProcurementDocumentService.CASH_AMOUNT_INVALID);

        verify(imageStorage, never()).storeObject(any(), anyString());
        verify(documentDao, never()).save(any());
        assertThat(cashPayments).isEmpty();
    }

    // --- markPaidInCash (R28.4) ------------------------------------------------------------------

    @Test
    @DisplayName("markPaidInCash falls back to the document total and the invoice date")
    void markPaidInCashFallsBackToTotalAndInvoiceDate() {
        document.setDocumentTotal(new BigDecimal("123.45"));
        document.setInvoiceDate(LocalDate.of(2024, 5, 1));

        service.markPaidInCash(DOCUMENT_ID, null, null, null);

        assertThat(cashPayments).containsExactly(new ProcurementCashPayment(
                DOCUMENT_ID, null, new BigDecimal("123.45"), LocalDate.of(2024, 5, 1)));
    }

    @Test
    @DisplayName("markPaidInCash without an invoice date uses the upload date; explicit values win")
    void markPaidInCashUploadDateAndExplicitValues() {
        service.markPaidInCash(DOCUMENT_ID, 4L, new BigDecimal("10"), null);
        service.markPaidInCash(DOCUMENT_ID, 4L, new BigDecimal("11"), LocalDate.of(2024, 6, 2));

        assertThat(cashPayments).containsExactly(
                new ProcurementCashPayment(DOCUMENT_ID, 4L, new BigDecimal("10"), LocalDate.of(2024, 5, 20)),
                new ProcurementCashPayment(DOCUMENT_ID, 4L, new BigDecimal("11"), LocalDate.of(2024, 6, 2)));
    }

    @Test
    @DisplayName("markPaidInCash requires an amount while the document total is unknown")
    void markPaidInCashRequiresAmountWithoutTotal() {
        assertThatThrownBy(() -> service.markPaidInCash(DOCUMENT_ID, null, null, null))
                .isInstanceOf(ForemenApiException.class)
                .hasMessageContaining(ProcurementDocumentService.CASH_AMOUNT_REQUIRED);
        assertThat(cashPayments).isEmpty();
    }

    @Test
    @DisplayName("markPaidInCash refuses a document that is not UNMATCHED (409)")
    void markPaidInCashRequiresUnmatched() {
        org.mockito.Mockito.doAnswer(inv -> {
            ProcurementDocumentServiceExtendedModel m = readModel(inv.getArgument(0));
            m.setPaymentStatus(DocumentPaymentStatus.PARTIALLY_PAID);
            return m;
        }).when(serviceMapper).toServiceExtendedModel(any());

        assertThatThrownBy(() -> service.markPaidInCash(DOCUMENT_ID, null, new BigDecimal("1"), null))
                .isInstanceOf(ForemenApiException.class)
                .hasMessageContaining(ProcurementDocumentService.PAYMENT_NOT_UNMATCHED);
        assertThat(cashPayments).isEmpty();
    }

    // --- PARSED transition (R18.2, R28.2) --------------------------------------------------------

    @Test
    @DisplayName("PARSED persists the invoice fields and publishes ProcurementDocumentParsedEvent")
    void parsedPersistsInvoiceFieldsAndPublishesEvent() {
        when(imageStorage.readObject(any())).thenReturn(new byte[] {1});
        when(candidateProvider.candidatesFor(PROJECT_ID)).thenReturn(List.of());
        when(matchEngine.match(any(ExtractedLine.class), any()))
                .thenReturn(new MatchResult(null, MatchTier.NONE, MatchStatus.UNMATCHED));
        when(ocrService.process(any(), any(), any(), any())).thenReturn(new OcrResult(List.of(),
                List.of(new ParsedLine("Klej", BigDecimal.ONE, "szt", BigDecimal.TEN, BigDecimal.TEN, null)),
                "Castorama", BigDecimal.TEN, "5260250274", "FV/1/2024", LocalDate.of(2024, 5, 19)));

        ProcessingStatus terminal = service.processQueued(DOCUMENT_ID, stage -> { });

        assertThat(terminal).isEqualTo(ProcessingStatus.PARSED);
        assertThat(document.getSupplierNip()).isEqualTo("5260250274");
        assertThat(document.getInvoiceNumber()).isEqualTo("FV/1/2024");
        assertThat(document.getInvoiceDate()).isEqualTo(LocalDate.of(2024, 5, 19));
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(events.capture());
        assertThat(events.getValue()).isEqualTo(new ProcurementDocumentParsedEvent(DOCUMENT_ID));
    }

    @Test
    @DisplayName("FAILED publishes no parsed event")
    void failedPublishesNoParsedEvent() {
        when(imageStorage.readObject(any())).thenReturn(new byte[] {1});
        when(ocrService.process(any(), any(), any(), any())).thenReturn(
                new OcrResult(List.of(), List.of(), null, null));

        assertThat(service.processQueued(DOCUMENT_ID, stage -> { })).isEqualTo(ProcessingStatus.FAILED);
        verify(eventPublisher, never()).publishEvent(any(ProcurementDocumentParsedEvent.class));
    }

    // --- Derived payment status (R16.4, R18.3) ---------------------------------------------------

    @Test
    @DisplayName("the read-model payment status is derived from the linked ledger amounts")
    void derivedPaymentStatus() {
        ProcurementLedgerEntryDto five = new ProcurementLedgerEntryDto(
                1L, "BANK", LocalDate.of(2024, 5, 1), "mBank", new BigDecimal("5"));
        assertThat(ProcurementDocumentServiceMapper.paymentStatus(BigDecimal.TEN, List.of()))
                .isEqualTo(DocumentPaymentStatus.UNMATCHED);
        assertThat(ProcurementDocumentServiceMapper.paymentStatus(BigDecimal.TEN, List.of(five)))
                .isEqualTo(DocumentPaymentStatus.PARTIALLY_PAID);
        assertThat(ProcurementDocumentServiceMapper.paymentStatus(null, List.of(five)))
                .isEqualTo(DocumentPaymentStatus.PARTIALLY_PAID);
        assertThat(ProcurementDocumentServiceMapper.paymentStatus(BigDecimal.TEN, List.of(five, five)))
                .isEqualTo(DocumentPaymentStatus.PAID);
    }
}
