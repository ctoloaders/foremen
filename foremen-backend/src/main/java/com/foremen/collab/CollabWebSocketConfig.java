package com.foremen.collab;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * FOR-05-13 — STOMP over WebSocket for the collaborative estimate.
 *
 * <ul>
 *   <li>Endpoint {@code /api/ws} (under {@code /api} so the existing nginx/Vite {@code /api} proxy
 *       carries it; the proxies add the WebSocket upgrade headers). Origins are restricted to the
 *       configured CORS origins.</li>
 *   <li>An in-memory simple broker on {@code /topic} delivers to this instance's subscribers; the
 *       cross-instance fan-out goes through Redis pub/sub ({@link CollabEventRelay}). Server and
 *       client heartbeats every 10 s detect dead connections, which drops their presence.</li>
 *   <li>Application destinations under {@code /app}; every inbound frame passes
 *       {@link StompAuthChannelInterceptor} (authN on CONNECT, per-project authZ on SUBSCRIBE/SEND).</li>
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(CollabProperties.class)
public class CollabWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;
    private final List<String> allowedOrigins;

    public CollabWebSocketConfig(StompAuthChannelInterceptor stompAuthChannelInterceptor,
                                 @Value("${foremen.cors.allowed-origins:http://localhost:3000}") List<String> allowedOrigins) {
        this.stompAuthChannelInterceptor = stompAuthChannelInterceptor;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/api/ws").setAllowedOriginPatterns(allowedOrigins.toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // A private scheduler for broker heartbeats (NOT a bean, so it never replaces the
        // application's @Scheduled task scheduler).
        ThreadPoolTaskScheduler heartbeatScheduler = new ThreadPoolTaskScheduler();
        heartbeatScheduler.setPoolSize(1);
        heartbeatScheduler.setThreadNamePrefix("collab-ws-heartbeat-");
        heartbeatScheduler.initialize();

        registry.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[] {10_000, 10_000})
                .setTaskScheduler(heartbeatScheduler);
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }
}
