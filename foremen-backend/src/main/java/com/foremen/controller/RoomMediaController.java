package com.foremen.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.service.room.media.RoomMediaDto;
import com.foremen.service.room.media.RoomMediaService;

import lombok.RequiredArgsConstructor;

/**
 * FOR-05-02 (Requirements 10.2, 14.1): room media endpoints under the existing {@code ROOMS}
 * resource — list/content are READ, upload/delete are UPDATE (changing a room's attachments is an
 * update of the room). Every handler carries {@link RequiresPermission}, so the controller is fully
 * annotated for {@code PermissionAnnotationValidator}. Project access is verified per room in
 * {@link RoomMediaService}.
 */
@RestController
@RequestMapping("/api/rooms/{roomId}/media")
@RequiredArgsConstructor
@PermissionResource("ROOMS")
public class RoomMediaController {

    private static final String RESOURCE = "ROOMS";

    private final RoomMediaService mediaService;

    @GetMapping
    @RequiresPermission(resource = RESOURCE, operation = "READ")
    public ResponseEntity<List<RoomMediaDto>> list(@PathVariable Long roomId) {
        return ResponseEntity.ok(mediaService.list(roomId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(resource = RESOURCE, operation = "UPDATE")
    public ResponseEntity<List<RoomMediaDto>> upload(
            @PathVariable Long roomId, @RequestPart("files") List<MultipartFile> files) {
        return ResponseEntity.ok(mediaService.upload(roomId, files));
    }

    @GetMapping("/{mediaId}/content")
    @RequiresPermission(resource = RESOURCE, operation = "READ")
    public ResponseEntity<byte[]> content(@PathVariable Long roomId, @PathVariable Long mediaId) {
        RoomMediaService.Content content = mediaService.content(roomId, mediaId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(content.fileName(), StandardCharsets.UTF_8)
                                .build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(content.bytes());
    }

    @DeleteMapping("/{mediaId}")
    @RequiresPermission(resource = RESOURCE, operation = "UPDATE")
    public ResponseEntity<Void> delete(@PathVariable Long roomId, @PathVariable Long mediaId) {
        mediaService.delete(roomId, mediaId);
        return ResponseEntity.noContent().build();
    }
}
