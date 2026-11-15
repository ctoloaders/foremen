package com.foremen.controller;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.controller.model.GoodsReceiptBatchResult;
import com.foremen.controller.model.GoodsReceiptDto;
import com.foremen.controller.model.GoodsReceiptFilter;
import com.foremen.controller.model.GoodsReceiptMediaDto;
import com.foremen.controller.model.GoodsReceiptRequest;
import com.foremen.controller.model.GoodsReceiptVoidRequest;
import com.foremen.controller.model.GoodsReceiptVoidResult;
import com.foremen.controller.model.ToReceiveDto;
import com.foremen.dao.model.ReceiptKind;
import com.foremen.service.procurement.receipt.GoodsReceiptMediaService;
import com.foremen.service.procurement.receipt.GoodsReceiptService;

import lombok.RequiredArgsConstructor;

/**
 * FOR-11-05 (R1.3, R1.5, R9.4, R13): the goods-receipt API under {@code /api/goods-receipts}.
 *
 * <p>ABAC: class-level {@code @PermissionResource("GOODS_RECEIPTS")} and an explicit method-level
 * {@code @RequiresPermission} on every handler (reads READ, record CREATE, void and photo changes UPDATE),
 * so {@code PermissionAnnotationValidator} classifies the controller COMPLETE. Project scoping (404 outside
 * the caller's projects) lives in the services.
 */
@RestController
@RequestMapping("/api/goods-receipts")
@RequiredArgsConstructor
@PermissionResource("GOODS_RECEIPTS")
public class GoodsReceiptController {

    static final String RESOURCE = "GOODS_RECEIPTS";
    static final int MAX_PAGE_SIZE = 200;

    private final GoodsReceiptService receiptService;
    private final GoodsReceiptMediaService mediaService;

    @PostMapping
    @RequiresPermission(resource = RESOURCE, operation = "CREATE")
    public ResponseEntity<GoodsReceiptBatchResult> create(@RequestBody GoodsReceiptRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(receiptService.record(request));
    }

    @GetMapping
    @RequiresPermission(resource = RESOURCE, operation = "READ")
    public ResponseEntity<Page<GoodsReceiptDto>> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long positionId,
            @RequestParam(required = false) Long shipmentId,
            @RequestParam(required = false) ReceiptKind kind,
            @RequestParam(required = false) Boolean includeVoided,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        GoodsReceiptFilter filter = new GoodsReceiptFilter(projectId, positionId, shipmentId, kind, includeVoided,
                from, to);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return ResponseEntity.ok(receiptService.list(filter, pageable));
    }

    @GetMapping("/to-receive")
    @RequiresPermission(resource = RESOURCE, operation = "READ")
    public ResponseEntity<List<ToReceiveDto>> toReceive(@RequestParam(required = false) Long projectId) {
        return ResponseEntity.ok(receiptService.toReceive(projectId));
    }

    @GetMapping("/{id}")
    @RequiresPermission(resource = RESOURCE, operation = "READ")
    public ResponseEntity<GoodsReceiptDto> get(@PathVariable Long id) {
        return ResponseEntity.ok(receiptService.get(id));
    }

    @PostMapping("/{id}/void")
    @RequiresPermission(resource = RESOURCE, operation = "UPDATE")
    public ResponseEntity<GoodsReceiptVoidResult> voidReceipt(@PathVariable Long id,
            @RequestBody GoodsReceiptVoidRequest request) {
        return ResponseEntity.ok(receiptService.voidReceipt(id, request));
    }

    @PostMapping("/{id}/media")
    @RequiresPermission(resource = RESOURCE, operation = "UPDATE")
    public ResponseEntity<List<GoodsReceiptMediaDto>> uploadMedia(@PathVariable Long id,
            @RequestPart("files") List<MultipartFile> files) {
        return ResponseEntity.ok(mediaService.upload(id, files));
    }

    @GetMapping("/{id}/media/{mediaId}/content")
    @RequiresPermission(resource = RESOURCE, operation = "READ")
    public ResponseEntity<byte[]> mediaContent(@PathVariable Long id, @PathVariable Long mediaId) {
        GoodsReceiptMediaService.Content content = mediaService.content(id, mediaId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(content.fileName(), StandardCharsets.UTF_8)
                                .build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(content.bytes());
    }

    @DeleteMapping("/{id}/media/{mediaId}")
    @RequiresPermission(resource = RESOURCE, operation = "UPDATE")
    public ResponseEntity<Void> deleteMedia(@PathVariable Long id, @PathVariable Long mediaId) {
        mediaService.delete(id, mediaId);
        return ResponseEntity.noContent().build();
    }
}
