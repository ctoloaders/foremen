package com.foremen.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.service.document.ProviderCallback;
import com.foremen.service.document.SignableDocumentDto;
import com.foremen.service.document.SignatureService;

import lombok.RequiredArgsConstructor;

/**
 * FOR-05-08 (Requirements 5.3, 6.4, 13.1; design §Backend controllers
 * {@code SignatureCallbackController}): the provider webhook that receives the asynchronous outcome
 * of an {@code ONLINE} / {@code PODPIS_GOV_PL} signing ceremony and advances the matching pending
 * {@code DocumentSignature}.
 *
 * <h2>Intentionally unguarded (R13.1, per {@code entity-creation-rules})</h2>
 * This is an <b>unauthenticated external provider webhook</b>, exactly like {@link AuthController}:
 * it carries <b>none</b> of the three permission annotations
 * ({@code @PermissionResource} / {@code @PermissionOperation} / {@code @RequiresPermission}), so
 * {@code PermissionAnnotationValidator} treats it as deliberately unguarded rather than
 * half-annotated. It is NOT ABAC-protected because the caller is a QTSP / Profil Zaufany provider
 * (or the {@code StubSignatureProvider}), not a logged-in Foremen principal — there is no project
 * role to resolve. The path {@code /api/signatures/callback} is admitted to the public allowlist in
 * {@code SecurityConfig} so an unauthenticated provider request reaches this controller instead of
 * being rejected with 401 by the {@code anyRequest().authenticated()} catch-all.
 *
 * <p>Authenticity is enforced <b>inside</b> {@link SignatureService#onProviderCallback(ProviderCallback)}
 * rather than by the security layer: the inbound {@link ProviderCallback#providerRef()} must match a
 * pending signature it was issued for, and a {@code SIGNED} outcome is accepted only after the
 * sealed-evidence {@link ProviderCallback#contentHash()} verifies against the document's stored
 * {@code contentHash} (a mismatch is rejected with {@code 409 error.document.hash.mismatch}) — the
 * {@code providerRef} + {@code contentHash} pair is the webhook's validation (R5.3, R6.4). This
 * controller is a thin delegator: it maps the HTTP concern only and holds no business logic.
 */
@RestController
@RequestMapping("/api/signatures")
@RequiredArgsConstructor
public class SignatureCallbackController {

    private final SignatureService signatureService;

    /**
     * Receives a provider signing-ceremony callback and delegates to
     * {@link SignatureService#onProviderCallback(ProviderCallback)}, which matches the
     * {@code providerRef} to its pending signature, verifies the sealed-evidence integrity against
     * the frozen artifact's {@code contentHash}, and marks the signature {@code SIGNED} /
     * {@code DECLINED} accordingly (R5.3, R6.4, R13.1).
     *
     * @param callback the provider callback payload (its {@code providerRef} must match a pending
     *                 signature; a {@code SIGNED} outcome must carry a {@code contentHash} that
     *                 matches the document's stored hash)
     * @return HTTP 200 with the (possibly now-{@code SIGNED}) document DTO
     */
    @PostMapping("/callback")
    public ResponseEntity<SignableDocumentDto> callback(@RequestBody ProviderCallback callback) {
        return ResponseEntity.ok(signatureService.onProviderCallback(callback));
    }
}
