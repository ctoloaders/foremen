package com.foremen.collab;

import java.security.Principal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import com.foremen.config.security.JwtClaims;
import com.foremen.config.security.JwtTokenProvider;

/**
 * FOR-05-13 — authentication + authorization for the collaboration STOMP channel.
 *
 * <ul>
 *   <li><b>CONNECT</b> must carry {@code Authorization: Bearer <access token>} as a STOMP native
 *       header (browsers cannot set headers on the WebSocket handshake itself). The token is
 *       validated with the same {@link JwtTokenProvider} as the REST API; an invalid/missing token
 *       is rejected with a STOMP ERROR frame. The resulting {@link CollabPrincipal} becomes the
 *       session user.</li>
 *   <li><b>SUBSCRIBE</b> is allowed only to {@code /topic/estimate/{projectId}} with
 *       {@code ESTIMATE READ} on an accessible project.</li>
 *   <li><b>SEND</b> is allowed only to {@code /app/estimate/{projectId}/op} ({@code ESTIMATE
 *       UPDATE}) and {@code /app/estimate/{projectId}/focus} ({@code ESTIMATE READ}).</li>
 * </ul>
 * A denied SUBSCRIBE/SEND is dropped (returns {@code null}), so a client can never observe or alter
 * an estimate it could not read/update over REST. Joined project ids are kept in the session
 * attributes so {@link CollabSessionListener} can clean up presence on disconnect.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);

    /** Session attribute holding the {@code Set<Long>} of estimate rooms this session joined. */
    public static final String JOINED_PROJECTS_ATTR = "foremen.collab.projects";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Pattern TOPIC = Pattern.compile("^/topic/estimate/(\\d+)$");
    private static final Pattern APP = Pattern.compile("^/app/estimate/(\\d+)/(op|focus)$");

    private final JwtTokenProvider tokenProvider;
    private final CollabAccessGuard accessGuard;
    private final EstimateCollabService collabService;

    public StompAuthChannelInterceptor(JwtTokenProvider tokenProvider,
                                       CollabAccessGuard accessGuard,
                                       EstimateCollabService collabService) {
        this.tokenProvider = tokenProvider;
        this.accessGuard = accessGuard;
        this.collabService = collabService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        switch (command) {
            case CONNECT, STOMP -> {
                accessor.setUser(authenticate(accessor));
                return message;
            }
            case SUBSCRIBE -> {
                return authorizeSubscribe(accessor, message);
            }
            case SEND -> {
                return authorizeSend(accessor, message);
            }
            default -> {
                return message;
            }
        }
    }

    private CollabPrincipal authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new MessagingException("Unauthorized");
        }
        JwtClaims claims = tokenProvider.validate(header.substring(BEARER_PREFIX.length()))
                .orElseThrow(() -> new MessagingException("Unauthorized"));
        return new CollabPrincipal(claims.sub(), claims.role(), collabService.resolveName(claims.sub()));
    }

    private Message<?> authorizeSubscribe(StompHeaderAccessor accessor, Message<?> message) {
        String destination = accessor.getDestination();
        Matcher m = destination == null ? null : TOPIC.matcher(destination);
        CollabPrincipal user = principalOf(accessor.getUser());
        if (m == null || !m.matches()) {
            log.debug("Collab: rejected subscribe to {}", destination);
            return null;
        }
        Long projectId = Long.valueOf(m.group(1));
        if (!accessGuard.isAllowed(user, projectId, CollabAccessGuard.READ)) {
            log.debug("Collab: user {} denied subscribe to project {}", user == null ? null : user.userId(), projectId);
            return null;
        }
        rememberJoined(accessor, projectId);
        return message;
    }

    private Message<?> authorizeSend(StompHeaderAccessor accessor, Message<?> message) {
        String destination = accessor.getDestination();
        Matcher m = destination == null ? null : APP.matcher(destination);
        CollabPrincipal user = principalOf(accessor.getUser());
        if (m == null || !m.matches()) {
            log.debug("Collab: rejected send to {}", destination);
            return null;
        }
        Long projectId = Long.valueOf(m.group(1));
        String operation = "op".equals(m.group(2)) ? CollabAccessGuard.UPDATE : CollabAccessGuard.READ;
        if (!accessGuard.isAllowed(user, projectId, operation)) {
            log.debug("Collab: user {} denied {} on project {}", user == null ? null : user.userId(), operation, projectId);
            return null;
        }
        rememberJoined(accessor, projectId);
        return message;
    }

    @SuppressWarnings("unchecked")
    private static void rememberJoined(StompHeaderAccessor accessor, Long projectId) {
        Map<String, Object> attrs = accessor.getSessionAttributes();
        if (attrs == null) {
            return;
        }
        Set<Long> joined = (Set<Long>) attrs.computeIfAbsent(JOINED_PROJECTS_ATTR, k -> new HashSet<Long>());
        synchronized (joined) {
            joined.add(projectId);
        }
    }

    private static CollabPrincipal principalOf(Principal principal) {
        return principal instanceof CollabPrincipal cp ? cp : null;
    }
}
