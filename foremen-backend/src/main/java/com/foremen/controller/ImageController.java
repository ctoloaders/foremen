package com.foremen.controller;

import com.foremen.controller.model.ImageUploadResponse;
import com.foremen.dao.ResourceDao;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Backend-mediated image upload endpoint (FOR-04-17, Requirement 7.2). The frontend posts an image
 * file plus the target entity kind and ABAC resource here; the backend validates the upload, stores
 * the object in Google Cloud Storage via the shared {@link ImageStorage} seam, and returns the
 * bucket-relative object key plus its resolved CDN URL. There is NO direct-from-frontend signed-URL
 * upload — every image transits this endpoint.
 *
 * <h2>Dynamic-resource ABAC enforcement (Requirement 9.5, 9.6)</h2>
 * The resource an upload is charged against is not fixed per endpoint — the same endpoint serves
 * construction-material images ({@code MATERIALS_CONSTRUCTION}) and producer images
 * ({@code MATERIAL_PRODUCERS}), so the caller passes the target {@code resource} as a request
 * parameter. Java annotation attributes are compile-time constants, so
 * {@link com.foremen.config.security.RequiresPermission} cannot bind to a runtime value; the
 * permission is therefore enforced <em>programmatically</em> inside the handler:
 * <ol>
 *   <li>the {@code resource} parameter is validated against the set of seeded ABAC resources
 *       ({@link ResourceDao#existsByCode(String)}) — an unknown resource is rejected with 400,
 *       so an attacker cannot smuggle an arbitrary string past the matrix (Requirement 9.6);</li>
 *   <li>the caller's role is required to hold <strong>CREATE or UPDATE</strong> on that resource
 *       via {@link ForemenPermissionEvaluator} (ADMIN bypass included); otherwise 403
 *       {@code error.access.denied} (Requirement 9.5).</li>
 * </ol>
 *
 * <p>The controller intentionally carries none of {@code @PermissionResource} /
 * {@code @PermissionOperation} / {@code @RequiresPermission}: it is an interceptor-level
 * <em>Unguarded</em> handler (like {@code AuthController} / {@code DisplayPreferencesController}),
 * which the {@code PermissionAnnotationValidator} classifies as COMPLETE (none of the three) so the
 * application starts cleanly. The matrix check is done here by hand rather than by the interceptor
 * precisely because the resource is dynamic.
 */
@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
public class ImageController {

    private static final String ROLE_PREFIX = "ROLE_";

    private final ImageStorage imageStorage;
    private final ResourceDao resourceDao;
    private final ForemenPermissionEvaluator permissionEvaluator;

    /**
     * Upload an image for the given entity kind and target ABAC resource.
     *
     * @param file       the uploaded image (validated for type/size by {@link ImageStorage#store})
     * @param entityKind the key namespace under which the object is stored, e.g. {@code producers}
     *                   or {@code construction-materials} (Requirement 7.5)
     * @param resource   the target ABAC resource code the upload is charged against, e.g.
     *                   {@code MATERIALS_CONSTRUCTION} or {@code MATERIAL_PRODUCERS}
     * @return the stored object key plus its resolved CDN URL
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImageUploadResponse upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("entityKind") String entityKind,
            @RequestParam("resource") String resource) {
        // 1. Validate the dynamic resource param against the seeded resources (Req 9.6): reject an
        // unknown resource before any storage happens, so an arbitrary string cannot bypass ABAC.
        if (resource == null || resource.isBlank() || !resourceDao.existsByCode(resource)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.image.resource.unknown", resource);
        }

        // 2. Enforce CREATE or UPDATE on that resource programmatically (Req 9.5). An upload is a
        // write to the owning entity's image, so either write operation authorises it.
        String roleCode = currentRoleCode();
        if (roleCode == null) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, "error.auth.unauthorized");
        }
        boolean allowed = permissionEvaluator.isAllowed(roleCode, resource, "CREATE")
                || permissionEvaluator.isAllowed(roleCode, resource, "UPDATE");
        if (!allowed) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.access.denied");
        }

        // 3. Delegate to the shared ImageStorage seam. store(...) validates content type and size
        // (Req 7.6/7.7) and returns the namespaced bucket-relative object key (Req 7.5).
        String objectKey = imageStorage.store(file, entityKind);
        return new ImageUploadResponse(objectKey, imageStorage.toCdnUrl(objectKey));
    }

    /**
     * The authenticated caller's role code, read from the {@code ROLE_<code>} authority in the
     * security context, or {@code null} when there is no authenticated principal. Mirrors the
     * extraction used by {@code PermissionInterceptor}.
     */
    private String currentRoleCode() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null
                || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String authority = ga.getAuthority();
            if (authority != null && authority.startsWith(ROLE_PREFIX)) {
                return authority.substring(ROLE_PREFIX.length());
            }
        }
        return null;
    }
}
