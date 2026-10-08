package com.foremen.service.document;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.dao.CompanyProfileDao;
import com.foremen.dao.CompanyProfileDao;
import com.foremen.dao.DocumentTemplateDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.SignableDocumentDao;
import com.foremen.dao.model.DocumentStatus;
import com.foremen.dao.model.DocumentTemplateEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.dao.model.SignableDocumentTypeEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.document.mapper.DocumentDtoMapper;
import com.foremen.service.signing.SigningAuthorizationGuard;
import com.foremen.service.signing.SigningProgressCalculator;
import com.foremen.service.signing.merge.MergeContext;
import com.foremen.service.signing.merge.MergeMode;
import com.foremen.service.signing.merge.MergeResult;
import com.foremen.service.signing.merge.TemplateMergeEngine;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SignableDocumentService} (FOR-05-08-document-signing, task 7.6) asserting the
 * <b>immutable-after-freeze</b> invariant — Property 3: body, binding, and type are immutable after
 * freeze; a {@code SIGNED} document is fully immutable.
 *
 * <p>Follows the {@code DocumentMediaServiceTest} Mockito + manually-driven
 * {@link SecurityContextHolder} convention (no Spring context): the service is built with mocked
 * collaborators, {@link SigningAuthorizationGuard} is stubbed to allow (the ABAC/own-ness narrowing
 * is covered by {@code SigningAuthorizationGuardTest}, not here), and {@link SignableDocumentDao}
 * returns a document in a chosen lifecycle status.
 *
 * <p>The invariant exercised:
 * <ul>
 *   <li><b>Body-mutating actions are frozen off {@code DRAFT}.</b> {@link SignableDocumentService#generate(Long)}
 *       and {@link SignableDocumentService#saveBody(Long, DocumentBodyInput)} — the only paths that
 *       change the body/template-binding/type — are rejected with {@code 409 error.document.frozen}
 *       once the document is no longer {@code DRAFT} (status {@code PENDING_SIGNATURES},
 *       {@code SIGNED}, or {@code VOID}), persisting nothing (R1.5, R1.6, R3.7).</li>
 *   <li><b>They succeed only while {@code DRAFT}.</b> On a {@code DRAFT} document both actions apply
 *       the body change and persist (R3.5) — the freeze gate opens only before
 *       {@code request-signatures}.</li>
 *   <li><b>Form-field entry is permitted only while {@code PENDING_SIGNATURES}.</b>
 *       {@link SignableDocumentService#fillFormFields(Long, FormFieldValuesInput)} is rejected with
 *       {@code 409 error.document.not.pending} on a {@code DRAFT}, {@code SIGNED}, or {@code VOID}
 *       document, and a {@code SIGNED}/{@code VOID} document therefore rejects every body/form-field
 *       mutation (fully immutable, R1.6).</li>
 * </ul>
 *
 * <p>Feature: FOR-05-08-document-signing, Property 3: Body, binding, and type are immutable after
 * freeze; SIGNED is fully immutable.
 *
 * <p><b>Validates: Requirements 1.5, 1.6, 3.7</b>
 */
@ExtendWith(MockitoExtension.class)
class SignableDocumentFreezeImmutabilityTest {

    @Mock
    private SignableDocumentDao documentDao;
    @Mock
    private DocumentTemplateDao templateDao;
    @Mock
    private CompanyProfileDao companyProfileDao;
    @Mock
    private ProjectMemberDao projectMemberDao;
    @Mock
    private RoomDao roomDao;
    @Mock
    private DocumentDtoMapper dtoMapper;
    @Mock
    private SigningAuthorizationGuard authorizationGuard;
    @Mock
    private TemplateMergeEngine mergeEngine;
    @Mock
    private EntityManager entityManager;

    private SignableDocumentService service;

    private static final Long DOCUMENT_ID = 500L;
    private static final Long PROJECT_ID = 42L;
    private static final String TYPE_CODE = "CONTRACT_WORKS";

    /** The three non-{@code DRAFT} statuses that freeze the body: body mutation must be rejected. */
    private static final List<DocumentStatus> FROZEN_STATUSES =
            List.of(DocumentStatus.PENDING_SIGNATURES, DocumentStatus.SIGNED, DocumentStatus.VOID);

    /** The three non-{@code PENDING_SIGNATURES} statuses: form-field fill must be rejected. */
    private static final List<DocumentStatus> NON_PENDING_STATUSES =
            List.of(DocumentStatus.DRAFT, DocumentStatus.SIGNED, DocumentStatus.VOID);

    @BeforeEach
    void setUp() {
        // Only the collaborators the frozen-gate / fill paths actually reach are mocked; the rest of
        // the 18-arg constructor is null (never dereferenced on these paths), mirroring the
        // SignableDocumentSignedInvariantPropertyTest constructor convention.
        service = new SignableDocumentService(
                documentDao,           // SignableDocumentDao
                templateDao,           // DocumentTemplateDao
                companyProfileDao,     // CompanyProfileDao
                null,                  // ProjectDao
                projectMemberDao,      // ProjectMemberDao
                roomDao,               // RoomDao
                null,                  // UserDao
                null,                  // SignableDocumentServiceMapper
                dtoMapper,             // DocumentDtoMapper
                null,                  // ProjectAccessCache
                null,                  // AuditLogDao
                entityManager,         // EntityManager
                null,                  // DocumentStatusMachine
                new SigningProgressCalculator(),
                authorizationGuard,    // SigningAuthorizationGuard (stubbed to allow)
                mergeEngine,           // TemplateMergeEngine
                null,                  // PdfFreezeService
                null,                  // ProjectActivationSignal
                null);                 // ApplicationEventPublisher

        // The guard allows every write — the ABAC/own-ness matrix is tested elsewhere; here we isolate
        // the lifecycle freeze gate.
        lenient().doNothing().when(authorizationGuard).assertAllowed(anyString(), any(), any(), anyBoolean());

        SecurityContextHolder.clearContext();
        authenticateAdmin();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------------------------------
    // R3.7 / R1.5 / R1.6 — generate() is rejected 409 error.document.frozen off DRAFT, stores nothing
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("generate: rejected 409 error.document.frozen for every non-DRAFT status, persists nothing (R3.7, R1.5, R1.6)")
    void generateRejectedWhenFrozen() {
        for (DocumentStatus frozen : FROZEN_STATUSES) {
            SignableDocumentEntity doc = document(frozen);
            when(documentDao.findById(DOCUMENT_ID)).thenReturn(Optional.of(doc));

            assertApiError(() -> service.generate(DOCUMENT_ID),
                    HttpStatus.CONFLICT, "error.document.frozen");

            assertNothingPersisted();
            org.mockito.Mockito.reset(documentDao, dtoMapper);
        }
    }

    @Test
    @DisplayName("saveBody: rejected 409 error.document.frozen for every non-DRAFT status, persists nothing (R3.7, R1.5, R1.6)")
    void saveBodyRejectedWhenFrozen() {
        for (DocumentStatus frozen : FROZEN_STATUSES) {
            SignableDocumentEntity doc = document(frozen);
            when(documentDao.findById(DOCUMENT_ID)).thenReturn(Optional.of(doc));

            assertApiError(() -> service.saveBody(DOCUMENT_ID, new DocumentBodyInput("hacked body")),
                    HttpStatus.CONFLICT, "error.document.frozen");

            // The body text, binding, and type are left untouched by the rejected mutation.
            assertThat(doc.getDocumentUri()).isNull();
            assertThat(doc.getContentHash()).isNull();
            assertNothingPersisted();
            org.mockito.Mockito.reset(documentDao, dtoMapper);
        }
    }

    // ------------------------------------------------------------------------------------------
    // R3.5 — generate() / saveBody() succeed only while DRAFT (the freeze gate is open before freeze)
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("saveBody: on a DRAFT document the body is written and persisted (R3.5)")
    void saveBodySucceedsWhenDraft() {
        SignableDocumentEntity doc = document(DocumentStatus.DRAFT);
        when(documentDao.findById(DOCUMENT_ID)).thenReturn(Optional.of(doc));
        when(documentDao.save(any(SignableDocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dtoMapper.toDto(any(SignableDocumentEntity.class))).thenReturn(draftDto());

        service.saveBody(DOCUMENT_ID, new DocumentBodyInput("new draft body"));

        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(doc.getDocumentUri()).isEqualTo("new draft body");
        assertThat(doc.getContentHash()).isNotBlank();
        verify(documentDao).save(doc);
    }

    @Test
    @DisplayName("generate: on a DRAFT document the merged body is written and persisted (R3.5)")
    void generateSucceedsWhenDraft() {
        SignableDocumentEntity doc = document(DocumentStatus.DRAFT);
        doc.setTemplateLocale("PL");
        when(documentDao.findById(DOCUMENT_ID)).thenReturn(Optional.of(doc));

        // Merge-context assembly reads these (best-effort, empty is fine for the freeze-gate test).
        when(projectMemberDao.findByProjectId(PROJECT_ID)).thenReturn(List.of());
        when(roomDao.findByProjectId(PROJECT_ID)).thenReturn(List.of());
        when(companyProfileDao.findAll()).thenReturn(List.of());

        DocumentTemplateEntity template = new DocumentTemplateEntity();
        template.setStorageUri("template body {Token}");
        when(templateDao.findByDocumentTypeIdAndLocaleAndActiveTrue(any(), eq("PL")))
                .thenReturn(Optional.of(template));
        when(mergeEngine.render(anyString(), any(MergeContext.class), eq(MergeMode.MARK_BLANK)))
                .thenReturn(new MergeResult("rendered body", java.util.Set.of()));
        when(documentDao.save(any(SignableDocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dtoMapper.toDto(any(SignableDocumentEntity.class))).thenReturn(draftDto());

        service.generate(DOCUMENT_ID);

        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.DRAFT);
        assertThat(doc.getDocumentUri()).isEqualTo("rendered body");
        verify(documentDao).save(doc);
    }

    // ------------------------------------------------------------------------------------------
    // R1.5 / R1.6 / R3.6 — fillFormFields() is permitted only in PENDING_SIGNATURES
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("fillFormFields: rejected 409 error.document.not.pending for DRAFT/SIGNED/VOID, persists nothing (R1.5, R1.6)")
    void fillFormFieldsRejectedWhenNotPending() {
        for (DocumentStatus notPending : NON_PENDING_STATUSES) {
            SignableDocumentEntity doc = document(notPending);
            when(documentDao.findById(DOCUMENT_ID)).thenReturn(Optional.of(doc));

            assertApiError(
                    () -> service.fillFormFields(DOCUMENT_ID,
                            new FormFieldValuesInput(List.of(new FormFieldValuesInput.FormFieldValue("pesel", "x")))),
                    HttpStatus.CONFLICT, "error.document.not.pending");

            assertNothingPersisted();
            org.mockito.Mockito.reset(documentDao, dtoMapper);
        }
    }

    @Test
    @DisplayName("fillFormFields: permitted on a PENDING_SIGNATURES document (R3.6)")
    void fillFormFieldsPermittedWhenPending() {
        SignableDocumentEntity doc = document(DocumentStatus.PENDING_SIGNATURES);
        when(documentDao.findById(DOCUMENT_ID)).thenReturn(Optional.of(doc));
        when(documentDao.save(any(SignableDocumentEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dtoMapper.toDto(any(SignableDocumentEntity.class))).thenReturn(pendingDto());

        service.fillFormFields(DOCUMENT_ID,
                new FormFieldValuesInput(List.of(new FormFieldValuesInput.FormFieldValue("pesel", "x"))));

        assertThat(doc.getStatus()).isEqualTo(DocumentStatus.PENDING_SIGNATURES);
        verify(documentDao).save(doc);
    }

    // ------------------------------------------------------------------------------------------
    // fixtures / helpers
    // ------------------------------------------------------------------------------------------

    private SignableDocumentEntity document(DocumentStatus status) {
        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT_ID);

        SignableDocumentTypeEntity type = new SignableDocumentTypeEntity();
        type.setId(7L);
        type.setCode(TYPE_CODE);

        SignableDocumentEntity doc = new SignableDocumentEntity();
        doc.setId(DOCUMENT_ID);
        doc.setProject(project);
        doc.setDocumentType(type);
        doc.setStatus(status);
        return doc;
    }

    private SignableDocumentDto draftDto() {
        return dtoWith(DocumentStatus.DRAFT);
    }

    private SignableDocumentDto pendingDto() {
        return dtoWith(DocumentStatus.PENDING_SIGNATURES);
    }

    private SignableDocumentDto dtoWith(DocumentStatus status) {
        return new SignableDocumentDto(
                DOCUMENT_ID, PROJECT_ID, TYPE_CODE, status, null, null, null, null,
                null, null, null, List.of(), List.of(), List.of(), null);
    }

    /** No document row saved and no form-field flush — the rejection side-effect assertion. */
    private void assertNothingPersisted() {
        verify(documentDao, never()).save(any(SignableDocumentEntity.class));
    }

    /**
     * Authenticate as an ADMIN (a write-eligible role) with a numeric principal id. ADMIN is resolved
     * by {@code actingProjectRole} straight from the ROLE_ADMIN authority, so the role path reaches
     * no project-membership / user DAO — keeping the freeze-gate isolation focused on the lifecycle.
     */
    private void authenticateAdmin() {
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken("11", "n/a", authorities);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static void assertApiError(Runnable action, HttpStatus status, String messageCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(status);
                    assertThat(api.getMessageCode()).isEqualTo(messageCode);
                });
    }
}
