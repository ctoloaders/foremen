package com.foremen.collab;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * FOR-05-13 — removes a session from every estimate roster it joined when its WebSocket closes
 * (tab closed, navigation away, network drop detected by heartbeats), so "who is editing" reflects
 * reality promptly instead of waiting for the presence TTL.
 */
@Component
public class CollabSessionListener {

    private final EstimateCollabService collabService;

    public CollabSessionListener(EstimateCollabService collabService) {
        this.collabService = collabService;
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs == null) {
            return;
        }
        Object joined = attrs.get(StompAuthChannelInterceptor.JOINED_PROJECTS_ATTR);
        if (!(joined instanceof Set<?> projects)) {
            return;
        }
        List<?> snapshot;
        synchronized (projects) {
            snapshot = List.copyOf(projects);
        }
        for (Object projectId : snapshot) {
            if (projectId instanceof Long id) {
                collabService.leave(id, event.getSessionId());
            }
        }
    }
}
