package com.foremen.config.mail;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Enables the invite-related configuration properties so they are bound and validated at
 * application startup (Requirement 7), and configures the asynchronous machinery used to dispatch
 * invitation emails off the request thread (FOR-03-02).
 *
 * <p>{@link InviteProperties} ({@code foremen.invite}) carries the invite-token lifetime and
 * {@link MailInviteProperties} ({@code foremen.mail}) carries the invitation-link base URL. Both
 * are {@code @Validated}, so an invalid value aborts context startup with a configuration error.
 *
 * <p>{@code @EnableAsync} activates {@code @Async} support and the {@link #mailTaskExecutor()} bean
 * provides a dedicated thread pool so invitation-email dispatch (see
 * {@code InvitationEmailDispatcher}) runs asynchronously after the issuing transaction commits.
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties({InviteProperties.class, MailInviteProperties.class})
public class MailConfig {

    /**
     * Dedicated thread pool for asynchronous invitation-email dispatch. Referenced by name from
     * {@code InvitationEmailDispatcher}'s {@code @Async("mailTaskExecutor")} listener so mail
     * sending never runs on the request thread.
     */
    @Bean("mailTaskExecutor")
    public ThreadPoolTaskExecutor mailTaskExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(5);
        ex.setQueueCapacity(100);
        ex.setThreadNamePrefix("mail-");
        ex.initialize();
        return ex;
    }
}
