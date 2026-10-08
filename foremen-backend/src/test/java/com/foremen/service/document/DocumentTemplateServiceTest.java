package com.foremen.service.document;

import java.util.LinkedHashSet;
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
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MultipartFile;

import com.foremen.dao.DocumentTemplateDao;
import com.foremen.dao.SignableDocumentTypeDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.DocumentTemplateEntity;
import com.foremen.dao.model.SignableDocumentTypeEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.document.docx.DocxBodyExtractor;
import com.foremen.service.signing.merge.MergeContext;
import com.foremen.service.signing.merge.MergeMode;
import com.foremen.service.signing.merge.MergeResult;
import com.foremen.service.signing.merge.TemplateMergeEngine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DocumentTemplateService} (FOR-05-08-document-signing, task 10.2), exercising
 * the admin, versioned template library: the versioned save that bumps {@code version} to
 * {@code max(existing) + 1} while retaining prior versions (R3a.5), the one-active-per-
 * ({@code documentType}, {@code locale}) enforcement on {@link DocumentTemplateService#activate}
 * (R3.2), the deactivate-never-delete retire path (R2.5 / R3a.1), the test-merge preview (R3.4,
 * R3a.4), and the {@code .docx} import that extracts a body through {@link DocxBodyExtractor} and
 * lands a new active version (R3a.1).
 *
 * <p>All collaborators ({@link DocumentTemplateDao}, {@link SignableDocumentTypeDao},
 * {@link UserDao}, {@link DocxBodyExtractor}, {@link TemplateMergeEngine}) are mocked and the
 * {@link SecurityContextHolder} is driven manually with a numeric principal id resolving to a
 * {@link UserEntity} for the {@code uploadedBy} provenance — following the
 * {@code DocumentMediaServiceTest} Mockito+SecurityContext convention (no Spring context).
 *
 * <p><b>Deactivate, never delete (R2.5 / R3a.1).</b> The service exposes no delete path; the tests
 * assert the generic {@code CrudRepository} delete methods are NEVER invoked on
 * {@code templateDao}, so a template is only ever retired by {@code active = false}.
 *
 * <p><b>Validates: Requirements 3.2, 3a.1, 3a.5</b>
 */
@ExtendWith(MockitoExtension.class)
class DocumentTemplateServiceTest {

    @Mock
    private DocumentTemplateDao templateDao;
    @Mock
    private SignableDocumentTypeDao documentTypeDao;
    @Mock
    private UserDao userDao;
    @Mock
    private DocxBodyExtractor docxBodyExtractor;
    @Mock
    private TemplateMergeEngine mergeEngine;

    private DocumentTemplateService service;

    private static final Long TYPE_ID = 7L;
    private static final Long UPLOADER_USER_ID = 11L;
    private static final String LOCALE_PL = "PL";
    private static final String TYPE_CODE = "CONTRACT_WORKS";

    @BeforeEach
    void setUp() {
        service = new DocumentTemplateService(templateDao, documentTypeDao, userDao, docxBodyExtractor, mergeEngine);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------------------------------
    // R3a.5 — versioned save bumps version to max(existing)+1 and retains prior versions
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("save: bumps version to max(existing)+1 for the (type, locale) and never overwrites prior versions (R3a.5)")
    void saveBumpsVersionToMaxPlusOne() {
        authenticateUploader();
        when(documentTypeDao.findById(TYPE_ID)).thenReturn(Optional.of(type()));
        // Existing history newest-first: versions 3, 2, 1 — new save must land version 4.
        when(templateDao.findByDocumentTypeIdAndLocaleOrderByVersionDesc(TYPE_ID, LOCALE_PL))
                .thenReturn(List.of(template(300L, 3, false), template(200L, 2, false), template(100L, 1, false)));
        when(templateDao.findByDocumentTypeIdAndLocaleAndActiveTrue(TYPE_ID, LOCALE_PL))
                .thenReturn(Optional.empty());
        when(templateDao.save(any(DocumentTemplateEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        DocumentTemplateDto saved = service.save(new TemplateSaveInput(TYPE_ID, "Works v4", LOCALE_PL, "{ContactName}"));

        ArgumentCaptor<DocumentTemplateEntity> captor = ArgumentCaptor.forClass(DocumentTemplateEntity.class);
        verify(templateDao).save(captor.capture());
        DocumentTemplateEntity persisted = captor.getValue();
        assertThat(persisted.getVersion()).isEqualTo(4);
        assertThat(persisted.isActive()).isTrue();
        assertThat(persisted.getStorageUri()).isEqualTo("{ContactName}");
        assertThat(persisted.getUploadedBy().getId()).isEqualTo(UPLOADER_USER_ID);
        assertThat(saved.version()).isEqualTo(4);
        assertThat(saved.active()).isTrue();

        // Never deletes any prior version — they are retained for audit.
        assertNeverDeletes();
    }

    @Test
    @DisplayName("create: first template for a (type, locale) with no history lands version 1, active (R3a.5)")
    void createFirstVersionIsOne() {
        authenticateUploader();
        when(documentTypeDao.findById(TYPE_ID)).thenReturn(Optional.of(type()));
        when(templateDao.findByDocumentTypeIdAndLocaleOrderByVersionDesc(TYPE_ID, LOCALE_PL))
                .thenReturn(List.of());
        when(templateDao.findByDocumentTypeIdAndLocaleAndActiveTrue(TYPE_ID, LOCALE_PL))
                .thenReturn(Optional.empty());
        when(templateDao.save(any(DocumentTemplateEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        DocumentTemplateDto created = service.create(new TemplateSaveInput(TYPE_ID, "Works v1", LOCALE_PL, "body"));

        assertThat(created.version()).isEqualTo(1);
        assertThat(created.active()).isTrue();
        assertNeverDeletes();
    }

    @Test
    @DisplayName("save: deactivates the currently-active version for the same (type, locale) so one-active holds (R3.2)")
    void saveDeactivatesPreviouslyActive() {
        authenticateUploader();
        when(documentTypeDao.findById(TYPE_ID)).thenReturn(Optional.of(type()));
        when(templateDao.findByDocumentTypeIdAndLocaleOrderByVersionDesc(TYPE_ID, LOCALE_PL))
                .thenReturn(List.of(template(100L, 1, true)));
        DocumentTemplateEntity currentlyActive = template(100L, 1, true);
        when(templateDao.findByDocumentTypeIdAndLocaleAndActiveTrue(TYPE_ID, LOCALE_PL))
                .thenReturn(Optional.of(currentlyActive));
        when(templateDao.save(any(DocumentTemplateEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.save(new TemplateSaveInput(TYPE_ID, "Works v2", LOCALE_PL, "body"));

        // The prior active is persisted deactivated, and the new version is persisted — two saves, no delete.
        assertThat(currentlyActive.isActive()).isFalse();
        verify(templateDao, times(2)).save(any(DocumentTemplateEntity.class));
        assertNeverDeletes();
    }

    // ------------------------------------------------------------------------------------------
    // R3a.5 — listByTypeAndLocale returns the version history (newest first, mapped to DTOs)
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("listByTypeAndLocale: returns the full version history for the (type, locale), newest first (R3a.5)")
    void listByTypeAndLocaleReturnsVersionHistory() {
        when(templateDao.findByDocumentTypeIdAndLocaleOrderByVersionDesc(TYPE_ID, LOCALE_PL))
                .thenReturn(List.of(template(300L, 3, true), template(200L, 2, false), template(100L, 1, false)));

        List<DocumentTemplateDto> history = service.listByTypeAndLocale(TYPE_ID, LOCALE_PL);

        assertThat(history).extracting(DocumentTemplateDto::version).containsExactly(3, 2, 1);
        assertThat(history).extracting(DocumentTemplateDto::active).containsExactly(true, false, false);
        assertThat(history).extracting(DocumentTemplateDto::documentTypeCode)
                .containsOnly(TYPE_CODE);
    }

    // ------------------------------------------------------------------------------------------
    // R3.2 — activate enforces one-active-per-(documentType, locale)
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("activate: deactivates the currently-active version before activating the target (R3.2)")
    void activateDeactivatesTheCurrentlyActive() {
        DocumentTemplateEntity target = template(200L, 2, false);
        DocumentTemplateEntity currentlyActive = template(100L, 1, true);
        when(templateDao.findById(200L)).thenReturn(Optional.of(target));
        when(templateDao.findByDocumentTypeIdAndLocaleAndActiveTrue(TYPE_ID, LOCALE_PL))
                .thenReturn(Optional.of(currentlyActive));
        when(templateDao.save(any(DocumentTemplateEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        DocumentTemplateDto result = service.activate(200L);

        // The previously-active (v1) was deactivated and the target (v2) activated.
        assertThat(currentlyActive.isActive()).isFalse();
        assertThat(target.isActive()).isTrue();
        assertThat(result.active()).isTrue();
        assertThat(result.version()).isEqualTo(2);
        verify(templateDao).save(currentlyActive);
        verify(templateDao).save(target);
        assertNeverDeletes();
    }

    @Test
    @DisplayName("activate: activating the already-active version is idempotent — no spurious deactivation (R3.2)")
    void activateAlreadyActiveIsIdempotent() {
        DocumentTemplateEntity target = template(100L, 1, true);
        when(templateDao.findById(100L)).thenReturn(Optional.of(target));
        when(templateDao.findByDocumentTypeIdAndLocaleAndActiveTrue(TYPE_ID, LOCALE_PL))
                .thenReturn(Optional.of(target));
        when(templateDao.save(any(DocumentTemplateEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        DocumentTemplateDto result = service.activate(100L);

        assertThat(result.active()).isTrue();
        assertThat(target.isActive()).isTrue();
        // Only the target itself is saved (re-activated); it is never deactivated as the "current active".
        verify(templateDao, times(1)).save(target);
        assertNeverDeletes();
    }

    @Test
    @DisplayName("activate: with no other active version simply activates the target (R3.2)")
    void activateWithNoCurrentActive() {
        DocumentTemplateEntity target = template(200L, 2, false);
        when(templateDao.findById(200L)).thenReturn(Optional.of(target));
        when(templateDao.findByDocumentTypeIdAndLocaleAndActiveTrue(TYPE_ID, LOCALE_PL))
                .thenReturn(Optional.empty());
        when(templateDao.save(any(DocumentTemplateEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        DocumentTemplateDto result = service.activate(200L);

        assertThat(result.active()).isTrue();
        verify(templateDao, times(1)).save(target);
        assertNeverDeletes();
    }

    @Test
    @DisplayName("activate: unknown template rejected 404 error.entity.not.found (R3.2)")
    void activateUnknownRejected() {
        when(templateDao.findById(999L)).thenReturn(Optional.empty());

        assertApiError(() -> service.activate(999L), HttpStatus.NOT_FOUND, "error.entity.not.found");

        verify(templateDao, never()).save(any(DocumentTemplateEntity.class));
        assertNeverDeletes();
    }

    // ------------------------------------------------------------------------------------------
    // R2.5 / R3a.1 — deactivate sets active=false and NEVER deletes
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("deactivate: sets active=false and persists; never deletes (R2.5 / R3a.1)")
    void deactivateSetsInactiveNeverDeletes() {
        DocumentTemplateEntity target = template(100L, 1, true);
        when(templateDao.findById(100L)).thenReturn(Optional.of(target));
        when(templateDao.save(any(DocumentTemplateEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        DocumentTemplateDto result = service.deactivate(100L);

        assertThat(target.isActive()).isFalse();
        assertThat(result.active()).isFalse();
        verify(templateDao).save(target);
        assertNeverDeletes();
    }

    @Test
    @DisplayName("deactivate: already-inactive version is idempotent and still never deletes (R2.5)")
    void deactivateAlreadyInactiveIsIdempotent() {
        DocumentTemplateEntity target = template(100L, 1, false);
        when(templateDao.findById(100L)).thenReturn(Optional.of(target));
        when(templateDao.save(any(DocumentTemplateEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        DocumentTemplateDto result = service.deactivate(100L);

        assertThat(result.active()).isFalse();
        assertNeverDeletes();
    }

    @Test
    @DisplayName("deactivate: unknown template rejected 404 and never deletes (R2.5)")
    void deactivateUnknownRejected() {
        when(templateDao.findById(999L)).thenReturn(Optional.empty());

        assertApiError(() -> service.deactivate(999L), HttpStatus.NOT_FOUND, "error.entity.not.found");

        verify(templateDao, never()).save(any(DocumentTemplateEntity.class));
        assertNeverDeletes();
    }

    // ------------------------------------------------------------------------------------------
    // R3.4 / R3a.4 — testMerge runs the engine and returns rendered body + unresolved placeholders
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("testMerge: runs the merge engine in MARK_BLANK over the template body and returns body + unresolved (R3.4, R3a.4)")
    void testMergeReturnsRenderedBodyAndUnresolved() {
        DocumentTemplateEntity template = template(100L, 1, true);
        template.setStorageUri("Dear {ContactName}, total {TotalBeforeTax}");
        when(templateDao.findById(100L)).thenReturn(Optional.of(template));
        MergeResult result = new MergeResult(
                "Dear «___», total «___»", new LinkedHashSet<>(List.of("ContactName", "TotalBeforeTax")));
        when(mergeEngine.render(eq("Dear {ContactName}, total {TotalBeforeTax}"), any(MergeContext.class),
                eq(MergeMode.MARK_BLANK))).thenReturn(result);

        TestMergeResultDto dto = service.testMerge(100L);

        assertThat(dto.renderedHtml()).isEqualTo("Dear «___», total «___»");
        assertThat(dto.unresolvedPlaceholders()).containsExactly("ContactName", "TotalBeforeTax");
        verify(mergeEngine).render(eq("Dear {ContactName}, total {TotalBeforeTax}"), any(MergeContext.class),
                eq(MergeMode.MARK_BLANK));
        assertNeverDeletes();
    }

    @Test
    @DisplayName("testMerge: fully-resolved template reports an empty unresolved list (R3.4)")
    void testMergeFullyResolved() {
        DocumentTemplateEntity template = template(100L, 1, true);
        template.setStorageUri("Static body, no tokens");
        when(templateDao.findById(100L)).thenReturn(Optional.of(template));
        when(mergeEngine.render(anyString(), any(MergeContext.class), eq(MergeMode.MARK_BLANK)))
                .thenReturn(new MergeResult("Static body, no tokens", new LinkedHashSet<>()));

        TestMergeResultDto dto = service.testMerge(100L);

        assertThat(dto.renderedHtml()).isEqualTo("Static body, no tokens");
        assertThat(dto.unresolvedPlaceholders()).isEmpty();
    }

    @Test
    @DisplayName("testMerge: unknown template rejected 404, engine never invoked (R3.4)")
    void testMergeUnknownRejected() {
        when(templateDao.findById(999L)).thenReturn(Optional.empty());

        assertApiError(() -> service.testMerge(999L), HttpStatus.NOT_FOUND, "error.entity.not.found");

        verifyNoInteractions(mergeEngine);
        assertNeverDeletes();
    }

    // ------------------------------------------------------------------------------------------
    // R3a.1 — importDocx extracts via DocxBodyExtractor and persists a new active version
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("importDocx: extracts the body via DocxBodyExtractor and persists a new active version (R3a.1)")
    void importDocxExtractsAndPersistsNewActiveVersion() {
        authenticateUploader();
        when(documentTypeDao.findById(TYPE_ID)).thenReturn(Optional.of(type()));
        // Existing version 1 present → import lands version 2.
        when(templateDao.findByDocumentTypeIdAndLocaleOrderByVersionDesc(TYPE_ID, LOCALE_PL))
                .thenReturn(List.of(template(100L, 1, true)));
        when(templateDao.findByDocumentTypeIdAndLocaleAndActiveTrue(TYPE_ID, LOCALE_PL))
                .thenReturn(Optional.empty());
        when(templateDao.save(any(DocumentTemplateEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(docxBodyExtractor.extractBody(any(MultipartFile.class)))
                .thenReturn("Imported {ContactName} body");

        MultipartFile docx = new MockMultipartFile(
                "file", "contract.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[] {1, 2, 3});

        DocumentTemplateDto imported = service.importDocx(TYPE_ID, "Imported", LOCALE_PL, docx);

        verify(docxBodyExtractor).extractBody(docx);
        ArgumentCaptor<DocumentTemplateEntity> captor = ArgumentCaptor.forClass(DocumentTemplateEntity.class);
        verify(templateDao).save(captor.capture());
        DocumentTemplateEntity persisted = captor.getValue();
        assertThat(persisted.getStorageUri()).isEqualTo("Imported {ContactName} body");
        assertThat(persisted.getVersion()).isEqualTo(2);
        assertThat(persisted.isActive()).isTrue();
        assertThat(persisted.getName()).isEqualTo("Imported");
        assertThat(persisted.getLocale()).isEqualTo(LOCALE_PL);
        assertThat(imported.active()).isTrue();
        assertThat(imported.version()).isEqualTo(2);
        assertNeverDeletes();
    }

    // ------------------------------------------------------------------------------------------
    // validation
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("save: blank name rejected 400 error.document.template.invalid, nothing persisted")
    void saveBlankNameRejected() {
        authenticateUploader();

        assertApiError(() -> service.save(new TemplateSaveInput(TYPE_ID, "  ", LOCALE_PL, "body")),
                HttpStatus.BAD_REQUEST, "error.document.template.invalid");

        verify(templateDao, never()).save(any(DocumentTemplateEntity.class));
        assertNeverDeletes();
    }

    @Test
    @DisplayName("save: null body rejected 400 error.document.template.invalid, nothing persisted")
    void saveNullBodyRejected() {
        authenticateUploader();

        assertApiError(() -> service.save(new TemplateSaveInput(TYPE_ID, "Works", LOCALE_PL, null)),
                HttpStatus.BAD_REQUEST, "error.document.template.invalid");

        verify(templateDao, never()).save(any(DocumentTemplateEntity.class));
        assertNeverDeletes();
    }

    // ------------------------------------------------------------------------------------------
    // fixtures / helpers
    // ------------------------------------------------------------------------------------------

    private SignableDocumentTypeEntity type() {
        SignableDocumentTypeEntity type = new SignableDocumentTypeEntity();
        type.setId(TYPE_ID);
        type.setCode(TYPE_CODE);
        type.setNamePL("Umowa");
        return type;
    }

    private DocumentTemplateEntity template(Long id, int version, boolean active) {
        DocumentTemplateEntity template = new DocumentTemplateEntity();
        template.setId(id);
        template.setDocumentType(type());
        template.setName("Works v" + version);
        template.setLocale(LOCALE_PL);
        template.setStorageUri("body v" + version);
        template.setVersion(version);
        template.setActive(active);
        UserEntity uploader = new UserEntity();
        uploader.setId(UPLOADER_USER_ID);
        template.setUploadedBy(uploader);
        return template;
    }

    /** The service exposes no delete path — a template is only ever retired by active=false (R2.5 / R3a.1). */
    private void assertNeverDeletes() {
        verify(templateDao, never()).delete(any(DocumentTemplateEntity.class));
        verify(templateDao, never()).deleteById(anyLong());
        verify(templateDao, never()).deleteAll();
    }

    /** Authenticate as the uploader: numeric principal id resolving to a user for uploadedBy. */
    private void authenticateUploader() {
        UserEntity user = new UserEntity();
        user.setId(UPLOADER_USER_ID);
        lenient().when(userDao.findById(UPLOADER_USER_ID)).thenReturn(Optional.of(user));
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(String.valueOf(UPLOADER_USER_ID), "n/a", authorities);
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
