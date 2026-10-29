package com.foremen.collab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import com.foremen.config.security.JwtClaims;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.permission.ForemenPermissionEvaluator;

/**
 * FOR-05-13 — the STOMP channel's security boundary: CONNECT authentication with the REST JWT and
 * per-project authorization of SUBSCRIBE/SEND (ESTIMATE READ to watch / focus, UPDATE to stage
 * edits), mirroring REST access. A denied frame is dropped (null), never forwarded.
 */
@DisplayName("StompAuthChannelInterceptor — collaboration channel authN/authZ")
class StompAuthChannelInterceptorTest {

    private static final long PROJECT = 5L;
    private static final long OTHER_PROJECT = 6L;

    private JwtTokenProvider tokenProvider;
    private ForemenPermissionEvaluator permissionEvaluator;
    private ProjectAccessCache projectAccessCache;
    private EstimateCollabService collabService;
    private StompAuthChannelInterceptor interceptor;
    private final MessageChannel channel = mock(MessageChannel.class);

    @BeforeEach
    void setUp() {
        tokenProvider = mock(JwtTokenProvider.class);
        permissionEvaluator = mock(ForemenPermissionEvaluator.class);
        projectAccessCache = mock(ProjectAccessCache.class);
        collabService = mock(EstimateCollabService.class);
        CollabAccessGuard guard = new CollabAccessGuard(permissionEvaluator, projectAccessCache);
        interceptor = new StompAuthChannelInterceptor(tokenProvider, guard, collabService);

        // MANAGER: READ + UPDATE on ESTIMATE; ESTIMATOR-like READ-only role: READ only.
        when(permissionEvaluator.isAllowed("MANAGER", "ESTIMATE", "READ")).thenReturn(true);
        when(permissionEvaluator.isAllowed("MANAGER", "ESTIMATE", "UPDATE")).thenReturn(true);
        when(permissionEvaluator.isAllowed("VIEWER", "ESTIMATE", "READ")).thenReturn(true);
        when(permissionEvaluator.isAllowed("VIEWER", "ESTIMATE", "UPDATE")).thenReturn(false);
        when(permissionEvaluator.isAllowed("ADMIN", "ESTIMATE", "READ")).thenReturn(true);
        when(permissionEvaluator.isAllowed("ADMIN", "ESTIMATE", "UPDATE")).thenReturn(true);
        // Users 1 (MANAGER) and 2 (VIEWER) are members of PROJECT only.
        when(projectAccessCache.get(1L)).thenReturn(Set.of(PROJECT));
        when(projectAccessCache.get(2L)).thenReturn(Set.of(PROJECT));
        when(projectAccessCache.get(9L)).thenReturn(Set.of());
    }

    // --- CONNECT -------------------------------------------------------------------------------

    @Test
    @DisplayName("CONNECT without an Authorization header is rejected")
    void connectWithoutTokenRejected() {
        Message<?> connect = frame(StompCommand.CONNECT, null, null, null);
        assertThatThrownBy(() -> interceptor.preSend(connect, channel)).isInstanceOf(MessagingException.class);
    }

    @Test
    @DisplayName("CONNECT with an invalid token is rejected")
    void connectWithInvalidTokenRejected() {
        when(tokenProvider.validate(anyString())).thenReturn(Optional.empty());
        Message<?> connect = frame(StompCommand.CONNECT, null, null, "Bearer nope");
        assertThatThrownBy(() -> interceptor.preSend(connect, channel)).isInstanceOf(MessagingException.class);
    }

    @Test
    @DisplayName("CONNECT with a valid token sets the CollabPrincipal (id, role, resolved name)")
    void connectWithValidTokenSetsPrincipal() {
        when(tokenProvider.validate("good")).thenReturn(Optional.of(
                new JwtClaims(1L, "MANAGER", "m@example.com", Instant.now().plusSeconds(600))));
        when(collabService.resolveName(1L)).thenReturn("Marek");
        StompHeaderAccessor accessor = accessor(StompCommand.CONNECT, null, null, "Bearer good");
        Message<?> out = interceptor.preSend(build(accessor), channel);

        assertThat(out).isNotNull();
        assertThat(accessor.getUser()).isEqualTo(new CollabPrincipal(1L, "MANAGER", "Marek"));
        assertThat(accessor.getUser().getName()).isEqualTo("1");
    }

    // --- SUBSCRIBE -----------------------------------------------------------------------------

    @Test
    @DisplayName("SUBSCRIBE to an accessible project's estimate topic is allowed and remembered")
    void subscribeAllowed() {
        StompHeaderAccessor accessor = accessor(StompCommand.SUBSCRIBE, "/topic/estimate/" + PROJECT, manager(), null);
        assertThat(interceptor.preSend(build(accessor), channel)).isNotNull();
        assertThat(accessor.getSessionAttributes().get(StompAuthChannelInterceptor.JOINED_PROJECTS_ATTR))
                .isEqualTo(Set.of(PROJECT));
    }

    @Test
    @DisplayName("SUBSCRIBE to a project the user is not a member of is dropped")
    void subscribeOtherProjectDenied() {
        assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/estimate/" + OTHER_PROJECT, manager(), null),
                channel)).isNull();
    }

    @Test
    @DisplayName("SUBSCRIBE to any non-estimate topic is dropped")
    void subscribeUnknownTopicDenied() {
        assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/anything", manager(), null), channel)).isNull();
        assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/estimate/5/../6", manager(), null), channel)).isNull();
    }

    @Test
    @DisplayName("SUBSCRIBE without an authenticated principal is dropped")
    void subscribeAnonymousDenied() {
        assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/estimate/" + PROJECT, null, null), channel)).isNull();
    }

    @Test
    @DisplayName("ADMIN may subscribe to any project (project-scope bypass)")
    void adminBypassesProjectScope() {
        CollabPrincipal admin = new CollabPrincipal(9L, "ADMIN", "Root");
        assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/estimate/" + OTHER_PROJECT, admin, null),
                channel)).isNotNull();
    }

    // --- SEND ----------------------------------------------------------------------------------

    @Test
    @DisplayName("staging an op needs ESTIMATE UPDATE: allowed for MANAGER, dropped for READ-only")
    void opRequiresUpdate() {
        assertThat(interceptor.preSend(frame(StompCommand.SEND, "/app/estimate/" + PROJECT + "/op", manager(), null),
                channel)).isNotNull();
        assertThat(interceptor.preSend(frame(StompCommand.SEND, "/app/estimate/" + PROJECT + "/op", viewer(), null),
                channel)).isNull();
    }

    @Test
    @DisplayName("presence focus needs only ESTIMATE READ (READ-only users still show as present)")
    void focusRequiresRead() {
        assertThat(interceptor.preSend(frame(StompCommand.SEND, "/app/estimate/" + PROJECT + "/focus", viewer(), null),
                channel)).isNotNull();
    }

    @Test
    @DisplayName("SEND to an unknown app destination or another project is dropped")
    void sendUnknownOrForeignDenied() {
        assertThat(interceptor.preSend(frame(StompCommand.SEND, "/app/estimate/" + PROJECT + "/save", manager(), null),
                channel)).isNull();
        assertThat(interceptor.preSend(frame(StompCommand.SEND, "/app/estimate/" + OTHER_PROJECT + "/op", manager(), null),
                channel)).isNull();
    }

    // --- helpers -------------------------------------------------------------------------------

    private static CollabPrincipal manager() {
        return new CollabPrincipal(1L, "MANAGER", "Marek");
    }

    private static CollabPrincipal viewer() {
        return new CollabPrincipal(2L, "VIEWER", "Vera");
    }

    private static StompHeaderAccessor accessor(StompCommand command, String destination,
                                                CollabPrincipal user, String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (user != null) {
            accessor.setUser(user);
        }
        if (authorization != null) {
            accessor.addNativeHeader("Authorization", authorization);
        }
        Map<String, Object> attrs = new HashMap<>();
        accessor.setSessionAttributes(attrs);
        accessor.setSessionId("s-1");
        accessor.setLeaveMutable(true);
        return accessor;
    }

    private static Message<?> build(StompHeaderAccessor accessor) {
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Message<?> frame(StompCommand command, String destination, CollabPrincipal user, String authorization) {
        return build(accessor(command, destination, user, authorization));
    }
}
