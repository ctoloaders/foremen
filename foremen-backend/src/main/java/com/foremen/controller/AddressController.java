package com.foremen.controller;

import com.foremen.controller.model.PlaceDetailsDto;
import com.foremen.controller.model.PlacePredictionDto;
import com.foremen.service.google.GooglePlacesService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Standalone, reusable Google Places proxy exposed under {@code /api/addresses} (FOR-04-13,
 * Requirements 5.1, 5.2, 5.3, 5.6).
 *
 * <p>Address lookup is cross-cutting — procurement and other future features also capture
 * addresses — so the proxy lives here rather than on {@code ProjectController}, and it is guarded
 * by an <strong>authenticated-any-user</strong> rule instead of an ABAC resource operation. The
 * controller therefore carries <strong>none</strong> of {@code @PermissionResource} /
 * {@code @PermissionOperation} / {@code @RequiresPermission}: per the New Managed Entity Checklist,
 * a controller with none of the three is intentionally unguarded by ABAC (like {@code AuthController}
 * / {@code DisplayPreferencesController}) and does not trip {@code PermissionAnnotationValidator}.
 *
 * <p>Access is instead gated by the security chain: {@code SecurityConfig}'s
 * {@code anyRequest().authenticated()} catch-all requires an authenticated principal for
 * {@code /api/addresses/**} (an anonymous request is rejected with HTTP 401), but no specific
 * resource grant is required. Both handlers delegate to {@link GooglePlacesService}, which injects
 * the server-side API key internally and never echoes it in a response (Requirement 5.3).
 */
@RestController
@RequestMapping("/api/addresses")
@RequiredArgsConstructor
public class AddressController {

    private final GooglePlacesService googlePlacesService;

    /**
     * Returns Google Places autocomplete predictions for a free-text address query, each carrying a
     * description and a {@code placeId} (Requirement 5.1). The server-side key is never exposed.
     *
     * @param query the free-text address query
     * @return HTTP 200 with the (possibly empty) list of predictions
     */
    @GetMapping("/autocomplete")
    public ResponseEntity<List<PlacePredictionDto>> autocomplete(@RequestParam String query) {
        return ResponseEntity.ok(googlePlacesService.autocomplete(query));
    }

    /**
     * Resolves the canonical address details for a Google {@code placeId}: {@code formattedAddress},
     * {@code latitude}, {@code longitude}, and minimal address components (Requirement 5.2). The
     * server-side key is never exposed.
     *
     * @param placeId the Google-assigned {@code place_id}
     * @return HTTP 200 with the resolved {@link PlaceDetailsDto}
     */
    @GetMapping("/details")
    public ResponseEntity<PlaceDetailsDto> details(@RequestParam String placeId) {
        return ResponseEntity.ok(googlePlacesService.resolveDetails(placeId));
    }
}
