package com.foremen.collab;

import java.nio.charset.StandardCharsets;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * FOR-05-13 — the cross-instance fan-out. Every backend instance subscribes to the Redis channel
 * {@link EstimateCollabService#CHANNEL}; each published {@link CollabMessages.CollabEvent} is forwarded
 * to <em>this</em> instance's STOMP subscribers of {@code /topic/estimate/{projectId}}. So an edit
 * staged by a browser connected to instance A reaches a browser connected to instance B.
 *
 * <p>Only registered while {@code foremen.collab.enabled} is true (default), so contexts without a
 * Redis server (unit/integration tests) never start a listener container.
 */
@Configuration
@ConditionalOnProperty(prefix = "foremen.collab", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CollabEventRelay {

    @Bean
    public RedisMessageListenerContainer collabRedisListenerContainer(RedisConnectionFactory connectionFactory,
                                                                     EstimateCollabService collabService) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // Reconnect calmly if Redis restarts or is not up yet; collaboration resumes on its own.
        container.setRecoveryInterval(5000L);
        MessageListener listener = (Message message, byte[] pattern) -> {
            String json = new String(message.getBody(), StandardCharsets.UTF_8);
            Long projectId = collabService.projectIdOf(json);
            collabService.deliverLocally(projectId, json);
        };
        container.addMessageListener(listener, new ChannelTopic(EstimateCollabService.CHANNEL));
        return container;
    }
}
