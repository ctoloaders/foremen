package com.foremen.dao;

import com.foremen.dao.model.OtpTokenEntity;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Repository for {@link OtpTokenEntity}, backing OTP issuance, verification, and rate
 * limiting (Requirement 3). Mirrors the {@code RefreshTokenDao} conventions.
 */
@Repository
public interface OtpTokenDao extends AdminDao<OtpTokenEntity, Long> {

    /**
     * Newest unused, unexpired token for the email; drives the verify lookup (Requirement 3.1).
     */
    Optional<OtpTokenEntity> findFirstByEmailIgnoreCaseAndUsedFalseAndExpiresAtAfterOrderByCreatedDateDesc(
            String email, Instant now);

    /**
     * Newest token for the email regardless of used/expired state; used by the verify state
     * machine to surface the distinct expired / attempts-exceeded / used branches.
     */
    Optional<OtpTokenEntity> findFirstByEmailIgnoreCaseOrderByCreatedDateDesc(String email);

    /**
     * Rate-limit count of requests in the trailing window: the number of rows whose email
     * matches (case-insensitively) and whose {@code createdDate} is after the supplied cutoff
     * (Requirement 3.2, 5.1). A pure DB count, so it carries no in-memory state and survives
     * an application restart (Requirement 5.4).
     *
     * <p>The cutoff is a {@link LocalDateTime} because the derived query compares it against the
     * {@code createdDate} field inherited from {@code BaseEntity}, which is a {@code LocalDateTime}.
     * Passing an {@code Instant} here is rejected by Hibernate as a parameter-type mismatch.
     */
    long countByEmailIgnoreCaseAndCreatedDateAfter(String email, LocalDateTime cutoff);
}
