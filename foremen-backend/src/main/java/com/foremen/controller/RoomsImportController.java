package com.foremen.controller;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.RoomsImportJobDto;
import com.foremen.controller.model.RoomsImportStartResponse;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.estimateai.EstimateAiStartGuard;
import com.foremen.service.roomsimport.RoomsImportAccessGuard;
import com.foremen.service.roomsimport.RoomsImportJobReader;
import com.foremen.service.roomsimport.RoomsImportService;
import com.foremen.service.roomsimport.RoomsImportUploadValidator;

import lombok.RequiredArgsConstructor;

/**
 * FOR-05-02 (Requirements 1, 2, 9.3, 14, 19.4, 24; design B2) — REST surface of the rooms auto-import,
 * rooted at {@code /api/rooms}. It lives in its own controller so the generic {@link RoomController}
 * CRUD surface is untouched; the literal {@code autoimport} segment (and the two-segment shape) never
 * collides with {@code /api/rooms/{id}}.
 *
 * <p><b>ABAC.</b> The class carries {@code @PermissionResource("ROOMS")} and every handler a method-level
 * {@code @RequiresPermission} (a complete declaration, so {@code PermissionAnnotationValidator} sees no
 * half-annotation): CREATE for start / cancel / discard, READ for polling and draft-media reads. Each
 * handler additionally verifies project access and answers {@code 404} for projects / jobs outside the
 * caller's scope.
 */
@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
@PermissionResource("ROOMS")
public class RoomsImportController {

    private static final String RESOURCE = "ROOMS";
    private static final String READ = "READ";
    private static final String CREATE = "CREATE";

    private final RoomsImportService service;
    private final RoomsImportJobReader reader;
    private final RoomsImportUploadValidator validator;
    private final RoomsImportAccessGuard accessGuard;
    private final EstimateAiStartGuard estimateStartGuard;

    /**
     * Starts an import: multipart {@code prompt} and {@code files}. {@code 202 {jobId,status}};
     * {@code 400/413/415} for invalid input, {@code 429} (with {@code Retry-After}) while a job is
     * active, {@code 503} when the feature is unavailable.
     */
    @PostMapping(value = "/project/{projectId}/autoimport", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(resource = RESOURCE, operation = CREATE)
    public ResponseEntity<RoomsImportStartResponse> start(
            @PathVariable Long projectId,
            @RequestParam(value = "prompt", required = false) String prompt,
            @RequestParam(value = "mode", required = false) String mode,
            @RequestParam(value = "useExistingRooms", required = false) Boolean useExistingRooms,
            @RequestParam(value = "packageCode", required = false) String packageCode,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        accessGuard.assertProjectAccess(projectId);
        boolean estimate = parseEstimateMode(mode);
        boolean existing = estimate && Boolean.TRUE.equals(useExistingRooms);
        if (estimate) {
            // FOR-05-04: permissions, feature switch, estimate lock, no-rooms: before anything is stored.
            estimateStartGuard.assertCanStart(projectId, existing, packageCode);
        }
        boolean noInput = (prompt == null || prompt.isBlank())
                && (files == null || files.stream().allMatch(f -> f == null || f.isEmpty()));
        List<MultipartFile> valid = existing && noInput ? List.of() : validator.validate(prompt, files);
        Long userId = RoomsImportAccessGuard.currentUserId();
        if (userId == null) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.access.denied");
        }
        RoomsImportService.StartOptions options = estimate
                ? new RoomsImportService.StartOptions("ESTIMATE", existing, packageCode)
                : RoomsImportService.StartOptions.ROOMS_ONLY;
        RoomsImportService.StartResult started = estimate
                ? service.start(projectId, userId, prompt, valid, options)
                : service.start(projectId, userId, prompt, valid); // ROOMS_ONLY: the FOR-05-02 call, unchanged
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new RoomsImportStartResponse(started.jobId(), started.status()));
    }

    /** {@code mode}: absent or {@code ROOMS_ONLY} is the FOR-05-02 behaviour, {@code ESTIMATE} the FOR-05-04 one. */
    private static boolean parseEstimateMode(String mode) {
        if (mode == null || mode.isBlank() || "ROOMS_ONLY".equalsIgnoreCase(mode.strip())) {
            return false;
        }
        if ("ESTIMATE".equalsIgnoreCase(mode.strip())) {
            return true;
        }
        throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.validation")
                .withParams(java.util.Map.of("field", "mode"));
    }

    /** The project's single job (any status) or {@code 204}. */
    @GetMapping("/project/{projectId}/autoimport/current")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<RoomsImportJobDto> current(@PathVariable Long projectId) {
        accessGuard.assertProjectAccess(projectId);
        return reader.currentOfProject(projectId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Status / stage / result of a job; {@code 404} when gone or outside the caller's projects. */
    @GetMapping("/autoimport/{jobId}")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<RoomsImportJobDto> get(@PathVariable Long jobId) {
        RoomsImportJobDto dto = reader.byId(jobId).orElseThrow(() -> RoomsImportAccessGuard.notFound(jobId));
        accessGuard.assertProjectAccess(dto.projectId());
        return ResponseEntity.ok(dto);
    }

    /** Cancels an <b>active</b> job: {@code 204}; {@code 409 not_cancellable} when terminal; {@code 404} when gone. */
    @PostMapping("/autoimport/{jobId}/cancel")
    @RequiresPermission(resource = RESOURCE, operation = CREATE)
    public ResponseEntity<Void> cancel(@PathVariable Long jobId) {
        Long projectId = reader.projectIdOf(jobId).orElseThrow(() -> RoomsImportAccessGuard.notFound(jobId));
        accessGuard.assertProjectAccess(projectId);
        service.cancel(jobId);
        return ResponseEntity.noContent().build();
    }

    /** Discards a job of any status (cancels an active one); idempotent {@code 204}. */
    @DeleteMapping("/autoimport/{jobId}")
    @RequiresPermission(resource = RESOURCE, operation = CREATE)
    public ResponseEntity<Void> discard(@PathVariable Long jobId) {
        reader.projectIdOf(jobId).ifPresent(projectId -> {
            accessGuard.assertProjectAccess(projectId);
            service.discard(jobId);
        });
        return ResponseEntity.noContent().build();
    }

    /** Draft media bytes of a job (cropped room picture). */
    @GetMapping("/autoimport/{jobId}/media/{mediaId}")
    @RequiresPermission(resource = RESOURCE, operation = READ)
    public ResponseEntity<byte[]> draftMedia(@PathVariable Long jobId, @PathVariable String mediaId) {
        Long projectId = reader.projectIdOf(jobId).orElseThrow(() -> RoomsImportAccessGuard.notFound(jobId));
        accessGuard.assertProjectAccess(projectId);
        byte[] bytes = reader.draftMedia(jobId, mediaId).orElseThrow(() -> RoomsImportAccessGuard.notFound(jobId));
        return ResponseEntity.ok()
                .contentType(mediaTypeOf(mediaId))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(bytes);
    }

    private static MediaType mediaTypeOf(String mediaId) {
        String lower = mediaId.toLowerCase();
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
