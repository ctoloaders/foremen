package com.foremen.controller;

import java.util.List;
import java.util.Locale;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.service.OfferService;
import com.foremen.service.offer.ClientOfferReadModelAssembler;
import com.foremen.service.offer.OfferMultiComparisonService;
import com.foremen.service.offer.OfferSnapshotService;
import com.foremen.service.offer.OfferSnapshotViews.Comparison;
import com.foremen.service.offer.OfferSnapshotViews.MultiComparison;
import com.foremen.service.offer.OfferSnapshotViews.OfferSummary;
import com.foremen.service.offer.OfferSnapshotViews.SnapshotView;

import lombok.RequiredArgsConstructor;

/**
 * FOR-05-07 Batch 2 (task 10): the offers list of a project, the read-only snapshot of an offer and the
 * comparison of two offers. All handlers are guarded by {@code OFFERS} READ. CLIENT responses are
 * redacted by allow-list inside {@link OfferSnapshotService} (no base prices; DRAFT offers hidden).
 */
@RestController
@RequestMapping("/api/offers")
@RequiredArgsConstructor
@PermissionResource("OFFERS")
public class OfferSnapshotController {

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String OFFERS_RESOURCE = "OFFERS";
    private static final String READ_OPERATION = "READ";
    private static final String CLIENT_ROLE = "CLIENT";

    private final OfferSnapshotService offerSnapshotService;
    private final OfferMultiComparisonService offerMultiComparisonService;
    private final OfferService offerService;

    /** All offers of the project, newest first (CLIENT: only offers already sent to the client). */
    @GetMapping("/project/{projectId}/list")
    @RequiresPermission(resource = OFFERS_RESOURCE, operation = READ_OPERATION)
    public ResponseEntity<List<OfferSummary>> list(@PathVariable Long projectId) {
        offerService.assertProjectIdAccess(projectId);
        return ResponseEntity.ok(offerSnapshotService.listByProject(projectId, CLIENT_ROLE.equals(roleCode())));
    }

    /** Read-only matrix-shaped snapshot captured when the offer was prepared; 404 when none exists. */
    @GetMapping("/{offerId}/snapshot")
    @RequiresPermission(resource = OFFERS_RESOURCE, operation = READ_OPERATION)
    public ResponseEntity<?> snapshot(@PathVariable Long offerId) {
        offerService.assertOfferAccess(offerId);
        String role = roleCode();
        if (CLIENT_ROLE.equals(role)) {
            SnapshotView view = offerSnapshotService.snapshot(offerId, true, false, isRu());
            return ResponseEntity.ok(view);
        }
        // FOR-05-04 (R21.9): staff get the same object plus the per-cell AI explanations (aiCells).
        return ResponseEntity.ok(offerSnapshotService.staffSnapshot(
                offerId, ClientOfferReadModelAssembler.canSeeLabourComparison(role), isRu()));
    }

    /** Diff of two offers of the same project (400 for different projects); client-visible data only. */
    @GetMapping("/compare")
    @RequiresPermission(resource = OFFERS_RESOURCE, operation = READ_OPERATION)
    public ResponseEntity<Comparison> compare(@RequestParam("left") Long left, @RequestParam("right") Long right) {
        offerService.assertOfferAccess(left);
        offerService.assertOfferAccess(right);
        return ResponseEntity.ok(offerSnapshotService.compare(left, right, CLIENT_ROLE.equals(roleCode()), isRu()));
    }

    /** Comparison of 2 to 10 offers of one project (D4); the client computes differences against the first id. */
    @GetMapping("/compare-many")
    @RequiresPermission(resource = OFFERS_RESOURCE, operation = READ_OPERATION)
    public ResponseEntity<MultiComparison> compareMany(@RequestParam("ids") List<Long> ids) {
        if (ids != null) {
            ids.stream().distinct().forEach(offerService::assertOfferAccess);
        }
        return ResponseEntity.ok(offerMultiComparisonService.compareMany(ids, CLIENT_ROLE.equals(roleCode()), isRu()));
    }

    private static boolean isRu() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }

    private static String roleCode() {
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
