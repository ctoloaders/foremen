package com.foremen.service;

import com.foremen.dao.OtpTokenDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.OtpTokenEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.mail.OtpMailSender;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Encapsulates the OTP client-authentication business logic (Requirements 4, 5, 6): code
 * generation, rate-limit enforcement, silent-success eligibility, persistence, email dispatch,
 * and (in a later task) code verification.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class OtpService {

    /** OTP code time-to-live in minutes (Requirement 4.6). */
    static final int CODE_TTL_MINUTES = 15;

    /** Maximum verification attempts before a code is locked (Requirement 6.6). */
    static final int MAX_ATTEMPTS = 3;

    /** Maximum OTP requests permitted per email within the trailing hour (Requirement 5.1). */
    static final int RATE_LIMIT_PER_HOUR = 5;

    /** Role code identifying a client user (Requirement — Eligible_Email). */
    private static final String CLIENT_ROLE_CODE = "CLIENT";

    private final OtpTokenDao otpTokenDao;
    private final UserDao userDao;
    private final OtpMailSender otpMailSender;
    private final OtpCodeGenerator codeGenerator;

    /**
     * Handle {@code POST /api/auth/otp/request} (Requirements 4, 5).
     *
     * <p>Evaluation order matters for the non-enumeration guarantee:
     * <ol>
     *   <li>Enforce the per-email rate limit by counting {@code otp_tokens} rows created in the
     *       trailing hour, <strong>before</strong> any user lookup (Requirement 5.1, 5.2). If the
     *       count is at or above {@link #RATE_LIMIT_PER_HOUR}, reject with HTTP 429 — an outcome
     *       identical for every email regardless of eligibility (Requirement 5.3).</li>
     *   <li>Within the limit, resolve the email. Only an ACTIVE user whose role code is
     *       {@code CLIENT} (an Eligible_Email) results in a generated + persisted code and a
     *       dispatched email (Requirement 4.3). For any other email the method returns silently
     *       with no code, no persistence, and no email (Silent_Success, Requirement 4.4).</li>
     * </ol>
     *
     * @param email the requested recipient email
     */
    public void request(String email) {
        Instant now = Instant.now();
        // The rate-limit window is measured against the audited createdDate, which is a
        // LocalDateTime populated from the JVM clock, so the cutoff must be a LocalDateTime too.
        LocalDateTime windowStart = LocalDateTime.now().minusHours(1);

        // (5.1, 5.2) Rate limit is evaluated BEFORE the user lookup so its outcome depends only
        // on the request count, never on whether the email is an eligible client.
        long recentRequests = otpTokenDao.countByEmailIgnoreCaseAndCreatedDateAfter(email, windowStart);
        if (recentRequests >= RATE_LIMIT_PER_HOUR) {
            // (5.3) Identical 429 for any email; no code, no persistence, no email.
            throw new ForemenApiException(HttpStatus.TOO_MANY_REQUESTS, "error.auth.otp.rate.limited");
        }

        // (5.5 -> 4) Within the limit, proceed to the Silent_Success eligibility branch.
        Optional<UserEntity> user = userDao.findByEmail(email);
        if (!isEligible(user)) {
            // (4.4) Silent_Success: unknown / non-CLIENT / non-ACTIVE — no distinction disclosed.
            return;
        }

        // (4.3, 4.6) Eligible_Email: generate, persist a fresh token, and dispatch the email.
        String code = codeGenerator.generate();
        OtpTokenEntity token = new OtpTokenEntity();
        token.setEmail(email);
        token.setCode(code);
        token.setExpiresAt(now.plus(CODE_TTL_MINUTES, ChronoUnit.MINUTES));
        token.setUsed(false);
        token.setAttempts(0);
        otpTokenDao.save(token);

        // (4.7) Dispatch through the reused JavaMailSender + Thymeleaf infrastructure.
        otpMailSender.send(user.get(), code);
    }

    /**
     * Handle {@code POST /api/auth/otp/verify} (Requirement 6): walk a fixed verification state
     * machine over the newest OTP token for the email and, on success, mark it used and return
     * the authenticated user.
     *
     * <p>Evaluation order is deterministic and — except the wrong-code branch — makes no state
     * change:
     * <ol>
     *   <li>no token row for the email → {@code error.auth.otp.invalid} (400) (Requirement 6.4);</li>
     *   <li>{@code attempts >= MAX_ATTEMPTS} → {@code error.auth.otp.attempts.exceeded} (400), no
     *       increment (Requirement 6.6);</li>
     *   <li>{@code expiresAt <= now} → {@code error.auth.otp.expired} (400), {@code used}
     *       unchanged (Requirement 6.7);</li>
     *   <li>{@code used == true} → {@code error.auth.otp.invalid} (400) (Requirement 6.8, 6.10);</li>
     *   <li>code mismatch → increment {@code attempts} by one, save, then
     *       {@code error.auth.otp.invalid} (400) (Requirement 6.5);</li>
     *   <li>otherwise valid → confirm Eligible_Email, set {@code used = true}, save, and return the
     *       user (Requirement 6.3).</li>
     * </ol>
     *
     * <p>Rationale: {@code attempts.exceeded} precedes {@code expired}/{@code used}/code-match so a
     * locked code reports the lock; {@code expired} precedes {@code used} so a client sees the
     * actionable expiry message; {@code used} and unknown/wrong codes share
     * {@code error.auth.otp.invalid} to avoid disclosing which specific code value was consumed.
     *
     * @param email the client email
     * @param code  the submitted OTP code
     * @return the authenticated ACTIVE CLIENT user on success
     */
    public UserEntity verifyCode(String email, String code) {
        Instant now = Instant.now();

        // (6.4) Newest token for the email regardless of used/expired state so the distinct
        // expired / attempts-exceeded / used branches can be surfaced. Absent → invalid.
        OtpTokenEntity token = otpTokenDao.findFirstByEmailIgnoreCaseOrderByCreatedDateDesc(email)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.BAD_REQUEST, "error.auth.otp.invalid"));

        // (6.6) Locked code: reject without incrementing attempts.
        if (token.getAttempts() >= MAX_ATTEMPTS) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.auth.otp.attempts.exceeded");
        }

        // (6.7) Expired code: reject, leaving used unchanged.
        if (!token.getExpiresAt().isAfter(now)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.auth.otp.expired");
        }

        // (6.8, 6.10) Already-used code: reject as invalid.
        if (token.isUsed()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.auth.otp.invalid");
        }

        // (6.5) Wrong code: increment attempts and reject as invalid.
        if (!token.getCode().equals(code)) {
            token.setAttempts(token.getAttempts() + 1);
            otpTokenDao.save(token);
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.auth.otp.invalid");
        }

        // (6.3) Valid code: confirm the email still belongs to an ACTIVE CLIENT, consume the code,
        // and return the authenticated user.
        Optional<UserEntity> user = userDao.findByEmail(email);
        if (!isEligible(user)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.auth.otp.invalid");
        }

        token.setUsed(true);
        otpTokenDao.save(token);
        return user.get();
    }

    /**
     * An Eligible_Email maps to exactly one user that is both ACTIVE and has role code
     * {@code CLIENT} (case-sensitive exact match).
     */
    private boolean isEligible(Optional<UserEntity> user) {
        return user
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .filter(u -> u.getRole() != null && CLIENT_ROLE_CODE.equals(u.getRole().getCode()))
                .isPresent();
    }
}
