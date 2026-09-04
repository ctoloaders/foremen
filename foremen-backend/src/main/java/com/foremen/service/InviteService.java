package com.foremen.service;

import com.foremen.config.mail.InviteProperties;
import com.foremen.config.mail.MailInviteProperties;
import com.foremen.dao.InviteTokenDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.InviteTokenEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.mail.InvitationMailSender;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Encapsulates invite-token generation and role-dependent invitation-email dispatch (FOR-03-02,
 * Requirements 3 and 4).
 *
 * <p>The service is {@code @Transactional}: when {@link #issueInvite(UserEntity)} runs inside the
 * user-creation transaction (via the {@code UserService} create hook), a token-persist or mail
 * failure propagates and rolls back the enclosing transaction so neither the user nor the token is
 * retained (Requirements 3.6, 4.8).
 *
 * <p>Beyond issuance, this service also validates/consumes tokens for the set-password flow
 * ({@link #consume(String)}) and handles admin resend ({@link #resend(Long)}).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class InviteService {

    /** Role code identifying a client user, which receives the OTP-portal invitation variant. */
    private static final String CLIENT_ROLE_CODE = "CLIENT";

    private final InviteTokenDao inviteTokenDao;
    private final UserDao userDao;
    private final InvitationMailSender invitationMailSender;
    private final InviteProperties inviteProperties;
    private final MailInviteProperties mailInviteProperties;

    /**
     * Issues exactly one invite token for a freshly created user and dispatches the role-dependent
     * invitation email (Requirements 3.1&ndash;3.3, 3.5, 3.6, 3.8, 4.1, 4.2, 4.8).
     *
     * <p>Called immediately after the user is persisted, within the same transaction, so a
     * token-persist or mail failure rolls back the user insert.
     *
     * @param user the newly created (INVITED) user to invite
     */
    public void issueInvite(UserEntity user) {
        generateAndSend(user);
    }

    /**
     * Validates an invite token for the set-password flow and returns its owning token entity for
     * {@code AuthService} to complete activation (Requirements 5.6&ndash;5.8, 5.10).
     *
     * <p>Evaluation order is strictly invalid &rarr; used &rarr; expired &rarr; owner-status, so
     * every negative branch makes no state change:
     * <ul>
     *   <li>token absent &rarr; {@code ForemenApiException(400, "error.invite.token.invalid")}
     *       (Requirement 5.6);</li>
     *   <li>{@code used == true} &rarr; {@code (400, "error.invite.token.used")}
     *       (Requirement 5.8);</li>
     *   <li>{@code expiresAt <= now} &rarr; {@code (400, "error.invite.token.expired")}
     *       (Requirement 5.7);</li>
     *   <li>owning user {@code DEACTIVATED} &rarr; {@code (409,
     *       "error.invite.user.already.active")} with the token left unchanged
     *       (Requirement 5.10).</li>
     * </ul>
     * Otherwise (owner {@code INVITED}) the token entity is returned unchanged.
     *
     * @param token the raw invite-token value presented by the set-password request
     * @return the persisted, still-valid invite token whose owning user is INVITED
     * @throws ForemenApiException per the branches above
     */
    public InviteTokenEntity consume(String token) {
        InviteTokenEntity invite = inviteTokenDao.findByToken(token)
                .orElseThrow(() ->
                        new ForemenApiException(HttpStatus.BAD_REQUEST, "error.invite.token.invalid"));

        if (invite.isUsed()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.invite.token.used");
        }

        if (!invite.getExpiresAt().isAfter(Instant.now())) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.invite.token.expired");
        }

        UserEntity user = invite.getUser();
        if (user != null && user.getStatus() == UserStatus.DEACTIVATED) {
            throw new ForemenApiException(HttpStatus.CONFLICT, "error.invite.user.already.active");
        }

        return invite;
    }

    /**
     * Invalidates any outstanding unused invite token for the user and mints a fresh single-use
     * invite/set-password token, returning its raw value <em>without</em> dispatching an email
     * (FOR-03-06, Requirements 14.5, 14.14).
     *
     * <p>Used by the Google-exchange activation bridge: when a Google login matches an
     * {@code INVITED} account, no session is issued; instead a fresh set-password token is minted
     * so the account can complete activation through the existing
     * {@code POST /api/auth/set-password} flow. The minted token reuses the exact FOR-03-02
     * generation and TTL semantics ({@link #generateToken(UserEntity)}), so it is consumable by
     * {@link #consume(String)} with no new token type.
     *
     * <p>As with {@link #resend(Long)}, every currently-unused token for the user is marked
     * {@code used = true} first so that only the freshly minted token remains valid. Unlike the
     * invite and resend paths, this method never sends an invitation email &mdash; email dispatch
     * is not part of the activation-bridge path.
     *
     * @param user the {@code INVITED} user to mint a set-password token for
     * @return the raw invite/set-password token value
     */
    public String mintSetPasswordToken(UserEntity user) {
        List<InviteTokenEntity> outstanding = inviteTokenDao.findByUserIdAndUsedFalse(user.getId());
        for (InviteTokenEntity token : outstanding) {
            token.setUsed(true);
            inviteTokenDao.save(token);
        }

        return generateToken(user).getToken();
    }

    /**
     * Admin resend: invalidates any outstanding invite token for the target user and issues a
     * fresh one with a new invitation email (Requirement 6.6&ndash;6.9).
     *
     * <ul>
     *   <li>user absent &rarr; {@code ForemenApiException(404, "error.invite.user.not.found")},
     *       no state change, no email (Requirement 6.7);</li>
     *   <li>owner status {@code ACTIVE} or {@code DEACTIVATED} &rarr; {@code (409,
     *       "error.invite.user.already.active")}, no state change, no email
     *       (Requirements 6.8, 6.9);</li>
     *   <li>owner status {@code INVITED} &rarr; every currently-unused token for the user is
     *       marked {@code used = true}, then a new token (fresh UUID, fresh expiry,
     *       {@code used = false}) is generated and the role-dependent invitation email is sent
     *       via {@link #generateAndSend(UserEntity)} (Requirement 6.6).</li>
     * </ul>
     *
     * @param userId the id of the user to resend the invitation for
     * @throws ForemenApiException per the branches above
     */
    public void resend(Long userId) {
        UserEntity user = userDao.findById(userId)
                .orElseThrow(() ->
                        new ForemenApiException(HttpStatus.NOT_FOUND, "error.invite.user.not.found"));

        if (user.getStatus() != UserStatus.INVITED) {
            throw new ForemenApiException(HttpStatus.CONFLICT, "error.invite.user.already.active");
        }

        List<InviteTokenEntity> outstanding = inviteTokenDao.findByUserIdAndUsedFalse(userId);
        for (InviteTokenEntity token : outstanding) {
            token.setUsed(true);
            inviteTokenDao.save(token);
        }

        generateAndSend(user);
    }

    /**
     * Creates and persists a single-use invite token for the user, then sends the invitation email
     * matching the user's role.
     *
     * <p>Token: a canonical 36-character random UUID string (Requirement 3.1), {@code expiresAt}
     * set to now plus the configured TTL in hours (Requirement 3.2), {@code used = false} and
     * associated with the user (Requirement 3.3). Persisted via {@link InviteTokenDao}; the DB
     * unique constraint guarantees distinct token values (Requirement 3.5).
     *
     * <p>Email variant: a {@code CLIENT} role receives the client-portal invitation
     * (Requirement 4.2); any other (employee) role receives the set-password invitation with the
     * invite link (Requirement 4.1). A token-persist or mail failure propagates so the enclosing
     * transaction rolls back (Requirements 3.6, 4.8).
     *
     * @param user the user to issue the token for
     * @return the persisted invite token
     */
    private InviteTokenEntity generateAndSend(UserEntity user) {
        InviteTokenEntity saved = generateToken(user);

        if (isClient(user)) {
            invitationMailSender.sendClientPortalInvitation(user);
        } else {
            String inviteLink = InviteLinkBuilder.buildInviteLink(
                    mailInviteProperties.inviteBaseUrl(), saved.getToken());
            invitationMailSender.sendSetPasswordInvitation(user, inviteLink);
        }

        return saved;
    }

    /**
     * Creates and persists a single-use invite token for the user, without any email dispatch.
     *
     * <p>Token: a canonical 36-character random UUID string (Requirement 3.1), {@code expiresAt}
     * set to now plus the configured TTL in hours (Requirement 3.2), {@code used = false} and
     * associated with the user (Requirement 3.3). Persisted via {@link InviteTokenDao}; the DB
     * unique constraint guarantees distinct token values (Requirement 3.5).
     *
     * <p>Shared by {@link #generateAndSend(UserEntity)} (which then dispatches the role-dependent
     * invitation email) and {@link #mintSetPasswordToken(UserEntity)} (which skips email), so the
     * generation + TTL logic is not duplicated.
     *
     * @param user the user to issue the token for
     * @return the persisted invite token
     */
    private InviteTokenEntity generateToken(UserEntity user) {
        InviteTokenEntity invite = new InviteTokenEntity();
        invite.setToken(UUID.randomUUID().toString());
        invite.setUser(user);
        invite.setExpiresAt(Instant.now().plus(inviteProperties.ttlHours(), ChronoUnit.HOURS));
        invite.setUsed(false);

        return inviteTokenDao.save(invite);
    }

    /**
     * Returns {@code true} when the user's role code is {@code CLIENT}. Any other role code
     * (employee; ADMIN never reaches the invite flow) resolves to the set-password variant.
     */
    private boolean isClient(UserEntity user) {
        RoleEntity role = user.getRole();
        return role != null && CLIENT_ROLE_CODE.equalsIgnoreCase(role.getCode());
    }
}
