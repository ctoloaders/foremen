package com.foremen.config.mail;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables the invite-related configuration properties so they are bound and validated at
 * application startup (Requirement 7).
 *
 * <p>{@link InviteProperties} ({@code foremen.invite}) carries the invite-token lifetime and
 * {@link MailInviteProperties} ({@code foremen.mail}) carries the invitation-link base URL. Both
 * are {@code @Validated}, so an invalid value aborts context startup with a configuration error.
 */
@Configuration
@EnableConfigurationProperties({InviteProperties.class, MailInviteProperties.class})
public class MailConfig {
}
