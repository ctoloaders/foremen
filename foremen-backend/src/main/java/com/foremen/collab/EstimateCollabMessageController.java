package com.foremen.collab;

import java.security.Principal;

import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import com.foremen.collab.CollabMessages.CellRef;
import com.foremen.collab.CollabMessages.FocusMessage;
import com.foremen.collab.CollabMessages.OpMessage;

/**
 * FOR-05-13 — inbound STOMP handlers of the collaborative estimate. Authorization has already been
 * enforced by {@link StompAuthChannelInterceptor} before these run ({@code /op} needs ESTIMATE
 * UPDATE, {@code /focus} needs ESTIMATE READ, both on an accessible project).
 *
 * <p>Payloads are JSON strings parsed here with the collaboration mapper; malformed frames are
 * ignored. Results are not returned to the sender directly — every change is broadcast on
 * {@code /topic/estimate/{projectId}} to all editors (the sender included, which is how it learns
 * its edit's server {@code seq}).
 */
@Controller
public class EstimateCollabMessageController {

    private final EstimateCollabService collabService;

    public EstimateCollabMessageController(EstimateCollabService collabService) {
        this.collabService = collabService;
    }

    /** Adds ({@code ADD}) or removes ({@code REMOVE}) one shared-draft staged edit. */
    @MessageMapping("/estimate/{projectId}/op")
    public void op(@DestinationVariable Long projectId, @Payload String body, Principal principal) {
        if (!(principal instanceof CollabPrincipal user)) {
            return;
        }
        OpMessage op = collabService.fromJson(body, OpMessage.class);
        if (op == null || op.type() == null) {
            return;
        }
        switch (op.type()) {
            case OpMessage.ADD -> collabService.addEdit(projectId, user, op.stagedId(), op.payload());
            case OpMessage.REMOVE -> collabService.removeEdit(projectId, user, op.stagedId());
            default -> { /* unknown op type — ignore */ }
        }
    }

    /** Presence heartbeat carrying the cell this editor is focused on ({@code null} ids = none). */
    @MessageMapping("/estimate/{projectId}/focus")
    public void focus(@DestinationVariable Long projectId, @Payload String body, Principal principal,
                      @Header("simpSessionId") String sessionId) {
        if (!(principal instanceof CollabPrincipal user)) {
            return;
        }
        FocusMessage focus = body == null || body.isBlank() ? null : collabService.fromJson(body, FocusMessage.class);
        CellRef cell = focus == null ? null : new CellRef(focus.workItemId(), focus.roomId());
        collabService.touchPresence(projectId, user, sessionId, cell);
    }
}
