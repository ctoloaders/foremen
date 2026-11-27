package com.foremen.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.config.security.PermissionResource;
import com.foremen.config.security.RequiresPermission;
import com.foremen.service.estimateai.chat.OfferAiChatService;
import com.foremen.service.estimateai.chat.OfferAiChatService.MessageDto;
import com.foremen.service.estimateai.chat.OfferAiChatService.TurnResult;
import com.foremen.service.estimateai.chat.OfferAiChatService.UndoResult;

import lombok.RequiredArgsConstructor;

/**
 * FOR-05-04 (Requirements 10.1-10.8, 11.5-11.9) — the offer chat endpoints, under the existing
 * {@code OFFERS} resource (no new ABAC resource): {@code GET} needs {@code OFFERS READ}, the two
 * {@code POST}s {@code OFFERS UPDATE}; the service additionally requires {@code ESTIMATE UPDATE}, project
 * access and answers 404 to a CLIENT. Staff only; not part of the loaders agent toolset.
 */
@RestController
@RequestMapping("/api/offers/{offerId}/ai-chat")
@RequiredArgsConstructor
@PermissionResource("OFFERS")
public class OfferAiChatController {

    private static final String OFFERS_RESOURCE = "OFFERS";

    private final OfferAiChatService chatService;

    /** @param confirmPendingId id of the assistant message whose pending action is confirmed */
    public record SendRequest(String message, String clientMessageId, Long confirmPendingId) {
    }

    @GetMapping
    @RequiresPermission(resource = OFFERS_RESOURCE, operation = "READ")
    public ResponseEntity<List<MessageDto>> history(@PathVariable Long offerId) {
        return ResponseEntity.ok(chatService.history(offerId));
    }

    @PostMapping
    @RequiresPermission(resource = OFFERS_RESOURCE, operation = "UPDATE")
    public ResponseEntity<TurnResult> send(@PathVariable Long offerId, @RequestBody SendRequest request) {
        return ResponseEntity.ok(chatService.send(offerId, request == null ? null : request.message(),
                request == null ? null : request.clientMessageId(),
                request == null ? null : request.confirmPendingId()));
    }

    @PostMapping("/undo")
    @RequiresPermission(resource = OFFERS_RESOURCE, operation = "UPDATE")
    public ResponseEntity<UndoResult> undo(@PathVariable Long offerId) {
        return ResponseEntity.ok(chatService.undo(offerId));
    }
}
