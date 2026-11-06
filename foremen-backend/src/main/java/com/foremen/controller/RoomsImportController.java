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
            @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        accessGuard.assertProjectAccess(projectId);
        List<MultipartFile> valid = validator.validate(prompt, files);
        Long userId = RoomsImportAccessGuard.currentUserId();
        if (userId == null) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.access.denied");
        }
        RoomsImportService.StartResult started = service.start(projectId, userId, prompt, valid);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new RoomsImportStartResponse(started.jobId(), started.status()));
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
