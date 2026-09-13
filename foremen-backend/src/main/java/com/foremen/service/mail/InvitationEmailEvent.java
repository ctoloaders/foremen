package com.foremen.service.mail;

import com.foremen.dao.model.UserEntity;

/**
 * Immutable application event carrying the intent to dispatch an invitation email
 * (FOR-03-02). Published by {@code InviteService} <em>after</em> the invite token has been
 * persisted, and consumed by {@link InvitationEmailDispatcher} asynchronously once the
 * enclosing transaction commits.
 *
 * <p>Decoupling issuance (transactional, token persistence) from dispatch (asynchronous, best
 * effort) means a mail-transport failure can never block or roll back user creation.
 *
 * @param user       the recipient user; email address and locale are read from this entity
 * @param client     {@code true} for the client-portal variant, {@code false} for the
 *                   employee set-password variant
 * @param inviteLink the fully-qualified set-password link for the employee variant; {@code null}
 *                   for the client variant
 */
public record InvitationEmailEvent(UserEntity user, boolean client, String inviteLink) {
}
