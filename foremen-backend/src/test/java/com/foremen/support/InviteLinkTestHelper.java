package com.foremen.support;

import com.foremen.config.mail.MailInviteProperties;
import com.foremen.dao.InviteTokenDao;
import com.foremen.dao.model.InviteTokenEntity;
import com.foremen.service.InviteLinkBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;

/**
 * Test-only aid for resolving the current invite link of a user (FOR-03-02).
 *
 * <p>Lives in the test source set only &mdash; it is <b>not</b> part of production code. Because
 * invitation emails are now dispatched asynchronously (the link no longer travels back through a
 * synchronous mail-sender call), integration tests need a deterministic way to obtain the link a
 * user would have received in order to drive/assert the set-password flow. This helper rebuilds
 * that link directly from the persisted invite token, using the same {@link InviteLinkBuilder} and
 * base URL the production dispatcher uses.
 */
@Component
@RequiredArgsConstructor
public class InviteLinkTestHelper {

    private final InviteTokenDao inviteTokenDao;
    private final MailInviteProperties mailInviteProperties;

    /**
     * Returns the current (latest unused) invite link for the user, or {@code null} if the user has
     * no outstanding unused invite token.
     *
     * @param userId the id of the invited user
     * @return the fully-qualified invite link for the latest unused token, or {@code null}
     */
    public String inviteLinkForUser(Long userId) {
        return inviteTokenDao.findByUserIdAndUsedFalse(userId).stream()
                .max(Comparator.comparing(InviteTokenEntity::getExpiresAt))
                .map(t -> InviteLinkBuilder.buildInviteLink(
                        mailInviteProperties.inviteBaseUrl(), t.getToken()))
                .orElse(null);
    }
}
