package com.foremen.service.document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MultipartFile;

import com.foremen.dao.DocumentSignatureDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.SignableDocumentDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.DocumentMediaKind;
import com.foremen.dao.model.DocumentSignatureEntity;
import com.foremen.dao.model.DocumentStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.dao.model.SignatureMethod;
import com.foremen.dao.model.SignatureStatus;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.document.mapper.DocumentDtoMapper;
import com.foremen.service.signing.SigningAuthorizationGuard;
import com.foremen.service.signing.SigningProgressCalculator;
import com.foremen.service.signing.provider.SignatureProvider;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.lifecycle.AfterTry;

/**
 * Property-based tests for the evidence/integrity preconditions {@link SignatureService} enforces
 * before a {@link DocumentSignatureEntity} may reach {@link SignatureStatus#SIGNED} (FOR-05-08,
 * design §Components {@code SignatureService (per-method completion)}; task 7.5).
 *
 * <p><b>Property 4: Evidence and integrity preconditions for reaching SIGNED.</b> A signature
 * reaches {@code SIGNED} only when its method-specific precondition holds:
 * <ul>
 *   <li>{@code PRINT} / {@code TABLET_INITIALS} require <b>attached evidence media</b>: an absent /
 *       empty upload is rejected with {@code 409 error.document.evidence.required} and the signature
 *       stays {@code PENDING} — it never reaches {@code SIGNED} without evidence (R6.2, R6.3,
 *       parent Property 16). When evidence is present the upload is stored first and only then does
 *       the signature become {@code SIGNED}.</li>
 *   <li>{@code ONLINE} / {@code PODPIS_GOV_PL} require the provider callback's reported hash to pass
 *       the integrity check against the document's stored {@code contentHash}: a mismatch (or a
 *       missing hash on either side) is rejected with {@code 409 error.document.hash.mismatch} and
 *       the signature stays {@code PENDING} — it never reaches {@code SIGNED} on a mismatch; it
 *       reaches {@code SIGNED} <b>iff</b> the two hashes are equal (R5.3, R6.4).</li>
 * </ul>
 *
 * <p>The invariant is exercised against the <b>real</b> {@link SignatureService}: it is built with
 * real pure collaborators ({@link SigningAuthorizationGuard}, {@link SigningProgressCalculator}) and
 * Mockito mocks for the persistence / provider / media / lifecycle seams. The two completion paths
 * are driven over arbitrary (evidence present/absent) and (hash match/mismatch) inputs, asserting
 * {@code SIGNED} <b>iff</b> the method precondition holds. The derived document-level
 * {@code recomputeSignedState} promotion is Property 1's concern and is stubbed here as a no-op; this
 * property is purely about the per-signature gate.
 *
 * <p>Follows the {@code DocumentMediaServiceTest} Mockito convention (manually-driven
 * {@link SecurityContextHolder}, {@link MockMultipartFile}) and the
 * {@code SignableDocumentSignedInvariantPropertyTest} jqwik convention.
 *
 * <p>Feature: FOR-05-08-document-signing, Property 4: Evidence and integrity preconditions for
 * reaching SIGNED.
 *
 * <p><b>Validates: Requirements 5.3, 6.2, 6.3, 6.4</b>
 */
@Tag("Feature: FOR-05-08-document-signing, Property 4: Evidence and integrity preconditions for reaching SIGNED")
class SignatureEvidenceIntegrityPropertyTest {

    private static final Long DOCUMENT_ID = 700L;
    private static final Long PROJECT_ID = 42L;
    private static final Long SIGNER_USER_ID = 11L;
    private static final String STORED_EVIDENCE_URI = "document-media/evidence-key";

    private SignableDocumentDao documentDao;
    private DocumentSignatureDao signatureDao;
    private ProjectMemberDao projectMemberDao;
    private UserDao userDao;
    private DocumentMediaService mediaService;
    private SignatureProvider signatureProvider;
    private SignableDocumentService documentService;
    private DocumentDtoMapper dtoMapper;

    private SignatureService service;

    private void setUp() {
        documentDao = mock(SignableDocumentDao.class);
        signatureDao = mock(DocumentSignatureDao.class);
        projectMemberDao = mock(ProjectMemberDao.class);
        userDao = mock(UserDao.class);
        mediaService = mock(DocumentMediaService.class);
        signatureProvider = mock(SignatureProvider.class);
        documentService = mock(SignableDocumentService.class);
        dtoMapper = mock(DocumentDtoMapper.class);

        // Real pure collaborators — the actual guard and progress calculator, no stubs.
        SigningAuthorizationGuard authorizationGuard = new SigningAuthorizationGuard();
        SigningProgressCalculator progressCalculator = new SigningProgressCalculator();

        service = new SignatureService(
                documentDao, signatureDao, projectMemberDao, userDao, mediaService,
                signatureProvider, documentService, authorizationGuard, progressCalculator, dtoMapper,
                org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));

        // The derived document-level promotion is Property 1's concern; stub it to a no-op so this
        // property isolates the per-signature evidence/integrity gate.
        lenient().when(documentService.recomputeSignedState(any())).thenReturn(false);
        // The DTO assembly path is irrelevant to the precondition under test — return a minimal DTO.
        lenient().when(dtoMapper.toDto(any(SignableDocumentEntity.class))).thenAnswer(inv -> minimalDto());
        // The signature mutations persist through the DAO; echo the argument back.
        lenient().when(signatureDao.save(any(DocumentSignatureEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(documentDao.save(any(SignableDocumentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterTry
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------------------------------
    // Property 4a — PRINT / TABLET_INITIALS: SIGNED iff evidence present (R6.2, R6.3).
    // An empty / missing upload is rejected 409 error.document.evidence.required and the signature
    // never reaches SIGNED; a present upload is stored first and only then the signature is SIGNED.
    // Validates: Requirements 6.2, 6.3
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-05-08-document-signing, Property 4: Evidence and integrity preconditions for reaching SIGNED")
    void evidenceMethodReachesSignedIffEvidencePresent(
            @ForAll("evidenceMethods") SignatureMethod method,
            @ForAll boolean evidencePresent) {
        setUp();
        authenticateSigner();

        DocumentSignatureEntity signature = pendingSignature(method);
        SignableDocumentEntity doc = pendingDocument(signature);
        when(documentDao.findById(DOCUMENT_ID)).thenReturn(Optional.of(doc));

        MultipartFile upload = evidencePresent
                ? new MockMultipartFile("file", "evidence", "application/pdf", new byte[] {1, 2, 3})
                : new MockMultipartFile("file", "evidence", "application/pdf", new byte[0]);

        // When evidence is present the media service stores it and reports the evidence uri; when it
        // is absent the service rejects BEFORE ever calling the media service (asserted below).
        lenient().when(mediaService.upload(eq(DOCUMENT_ID), any(MultipartFile.class), any(DocumentMediaKind.class)))
                .thenReturn(mediaSummary(method));

        if (evidencePresent) {
            invokeEvidenceCompletion(method, upload);

            // Precondition held: the signature reached SIGNED with the stored evidence uri recorded,
            // and the evidence was stored first.
            assertThat(signature.getStatus()).isEqualTo(SignatureStatus.SIGNED);
            assertThat(signature.getSignedAt()).isNotNull();
            assertThat(signature.getEvidenceUri()).isEqualTo(STORED_EVIDENCE_URI);
            verify(mediaService).upload(eq(DOCUMENT_ID), any(MultipartFile.class), eq(mediaKind(method)));
        } else {
            // Precondition failed: rejected 409 evidence.required, signature stays PENDING, and the
            // media service is NEVER called — no evidence is ever stored for an unsigned attempt.
            assertApiError(() -> invokeEvidenceCompletion(method, upload),
                    HttpStatus.CONFLICT, "error.document.evidence.required");
            assertThat(signature.getStatus()).isEqualTo(SignatureStatus.PENDING);
            assertThat(signature.getSignedAt()).isNull();
            assertThat(signature.getEvidenceUri()).isNull();
            verify(mediaService, never())
                    .upload(anyLong(), any(MultipartFile.class), any(DocumentMediaKind.class));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Property 4b — ONLINE / PODPIS_GOV_PL: SIGNED iff the callback hash matches the stored
    // contentHash (R5.3, R6.4). onProviderCallback is the purest integrity gate: a SIGNED outcome is
    // accepted iff cb.contentHash equals doc.contentHash (both non-null); any mismatch / missing hash
    // is rejected 409 error.document.hash.mismatch and the signature never reaches SIGNED.
    // Validates: Requirements 5.3, 6.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 300)
    @Tag("Feature: FOR-05-08-document-signing, Property 4: Evidence and integrity preconditions for reaching SIGNED")
    void providerCallbackReachesSignedIffHashMatches(
            @ForAll("hashes") String storedHash,
            @ForAll("hashes") String callbackHash,
            @ForAll("providerMethods") SignatureMethod method) {
        setUp();

        DocumentSignatureEntity signature = pendingSignature(method);
        signature.setProviderRef("prov-ref-xyz");
        SignableDocumentEntity doc = pendingDocument(signature);
        doc.setContentHash(storedHash);
        // The webhook resolves the signature by providerRef over the full signature set.
        when(signatureDao.findAll()).thenReturn(List.<DocumentSignatureEntity>of(signature));

        ProviderCallback callback = new ProviderCallback(
                "prov-ref-xyz", SignatureStatus.SIGNED, STORED_EVIDENCE_URI, callbackHash, null);

        boolean expectedSigned = storedHash != null && callbackHash != null && storedHash.equals(callbackHash);

        if (expectedSigned) {
            service.onProviderCallback(callback);

            assertThat(signature.getStatus()).isEqualTo(SignatureStatus.SIGNED);
            assertThat(signature.getSignedAt()).isNotNull();
            assertThat(signature.getEvidenceUri()).isEqualTo(STORED_EVIDENCE_URI);
        } else {
            assertApiError(() -> service.onProviderCallback(callback),
                    HttpStatus.CONFLICT, "error.document.hash.mismatch");
            assertThat(signature.getStatus()).isEqualTo(SignatureStatus.PENDING);
            assertThat(signature.getSignedAt()).isNull();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Property 4c — a DECLINED provider outcome never reaches SIGNED and never runs the hash gate:
    // the signature moves to DECLINED regardless of hash match/mismatch (R4.4, R6.4). This confirms
    // the integrity gate guards only the SIGNED path.
    // Validates: Requirements 6.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-08-document-signing, Property 4: Evidence and integrity preconditions for reaching SIGNED")
    void providerDeclineNeverReachesSignedRegardlessOfHash(
            @ForAll("hashes") String storedHash,
            @ForAll("hashes") String callbackHash) {
        setUp();

        DocumentSignatureEntity signature = pendingSignature(SignatureMethod.ONLINE);
        signature.setProviderRef("prov-ref-decline");
        SignableDocumentEntity doc = pendingDocument(signature);
        doc.setContentHash(storedHash);
        when(signatureDao.findAll()).thenReturn(List.<DocumentSignatureEntity>of(signature));

        ProviderCallback callback = new ProviderCallback(
                "prov-ref-decline", SignatureStatus.DECLINED, null, callbackHash, "changed my mind");

        service.onProviderCallback(callback);

        assertThat(signature.getStatus()).isEqualTo(SignatureStatus.DECLINED);
        assertThat(signature.getDeclineReason()).isEqualTo("changed my mind");
        assertThat(signature.getSignedAt()).isNull();
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /** The two evidence-upload methods whose precondition is "attached evidence media" (R6.2, R6.3). */
    @Provide
    Arbitrary<SignatureMethod> evidenceMethods() {
        return Arbitraries.of(SignatureMethod.PRINT, SignatureMethod.TABLET_INITIALS);
    }

    /** The two provider methods whose precondition is "hash integrity against contentHash" (R5.3, R6.4). */
    @Provide
    Arbitrary<SignatureMethod> providerMethods() {
        return Arbitraries.of(SignatureMethod.ONLINE, SignatureMethod.PODPIS_GOV_PL);
    }

    /**
     * Arbitrary content hashes drawn from a small pool (plus {@code null} and blank), so equal and
     * unequal pairs — and missing-hash cases on either side — are all exercised with high collision
     * probability for the match branch.
     */
    @Provide
    Arbitrary<String> hashes() {
        return Arbitraries.of(
                "sha256-aaaa", "sha256-bbbb", "sha256-cccc", "", null);
    }

    // ------------------------------------------------------------------------------------------
    // Fixtures / helpers
    // ------------------------------------------------------------------------------------------

    private void invokeEvidenceCompletion(SignatureMethod method, MultipartFile upload) {
        if (method == SignatureMethod.PRINT) {
            service.completeViaScan(DOCUMENT_ID, upload);
        } else {
            service.completeViaTablet(DOCUMENT_ID, upload);
        }
    }

    private static DocumentMediaKind mediaKind(SignatureMethod method) {
        return method == SignatureMethod.PRINT
                ? DocumentMediaKind.SCAN
                : DocumentMediaKind.TABLET_INITIAL;
    }

    private DocumentMediaSummaryDto mediaSummary(SignatureMethod method) {
        return new DocumentMediaSummaryDto(
                900L, "evidence", "application/pdf", 3L, STORED_EVIDENCE_URI, mediaKind(method));
    }

    /** A PENDING signature owned by the authenticated signer (explicit signerUser match). */
    private DocumentSignatureEntity pendingSignature(SignatureMethod method) {
        UserEntity signer = new UserEntity();
        signer.setId(SIGNER_USER_ID);
        DocumentSignatureEntity signature = new DocumentSignatureEntity();
        signature.setStatus(SignatureStatus.PENDING);
        signature.setMethod(method);
        signature.setSignerUser(signer);
        signature.setSignerRole("CLIENT");
        return signature;
    }

    /** A PENDING_SIGNATURES document wrapping the given signature, back-referencing it. */
    private SignableDocumentEntity pendingDocument(DocumentSignatureEntity signature) {
        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT_ID);
        SignableDocumentEntity doc = new SignableDocumentEntity();
        doc.setId(DOCUMENT_ID);
        doc.setProject(project);
        doc.setStatus(DocumentStatus.PENDING_SIGNATURES);
        List<DocumentSignatureEntity> signatures = new ArrayList<>();
        signatures.add(signature);
        doc.setSignatures(signatures);
        signature.setDocument(doc);
        return doc;
    }

    private SignableDocumentDto minimalDto() {
        return new SignableDocumentDto(
                DOCUMENT_ID, PROJECT_ID, "CONTRACT_MAIN", DocumentStatus.PENDING_SIGNATURES,
                null, null, null, null, null, null, LocalDateTime.now(),
                List.of(), List.of(), List.of(), null);
    }

    /**
     * Authenticates the acting signer as the owner of the pending signature: a numeric principal id
     * (resolving the own-signature gate), a MANAGER project role so the authorization guard permits
     * the signer action, and the owning user resolvable through the DAOs.
     */
    private void authenticateSigner() {
        UserEntity user = new UserEntity();
        user.setId(SIGNER_USER_ID);
        lenient().when(userDao.findById(SIGNER_USER_ID)).thenReturn(Optional.of(user));
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(String.valueOf(SIGNER_USER_ID), "n/a", authorities);
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
