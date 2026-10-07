package com.foremen.qa.fixtures;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

/**
 * Direct-DB reader for the client's one-time login code (FOR-QA-AUTO-05 offer-approval slice).
 *
 * <p>The CLIENT role signs in through the real {@code /auth/otp} UI, which issues a code the backend
 * persists in {@code otp_tokens}. On the docker/QA stack no real SMTP is configured, and the OTP
 * mail sender is suppressed under the {@code docker} profile ({@code LoggingOtpMailSender}), so the
 * code is NEVER delivered by email — temp-mail cannot work here. The only honest, repeatable way to
 * obtain the code is to read the freshest unused, unexpired row straight from the database via JDBC,
 * mirroring {@link TestUserFixture}'s {@link Db}-backed style.
 *
 * <p>No teardown is registered here: {@link TestUserFixture#deleteUserAndResources(TestUser)} already
 * removes {@code otp_tokens} rows by email as part of the client user's cleanup, so the codes this
 * fixture reads are swept together with the user that owns them.
 *
 * <p>All SQL is parameterized — the email is never interpolated into the statement.
 */
public final class OtpFixture {

    private final DataSource dataSource;

    public OtpFixture() {
        this(Db.dataSource());
    }

    /** Package/test-visible constructor allowing an injected {@link DataSource}. */
    public OtpFixture(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Return the newest unused, unexpired OTP code for {@code email}, or {@code null} when none is
     * currently outstanding. Matches the email case-insensitively and orders by creation time so a
     * resend always wins. The columns are the ones mapped by {@code OtpTokenEntity}/{@code BaseEntity}
     * and created by changeset {@code 016-create-otp-tokens} ({@code email}, {@code code},
     * {@code expires_at}, {@code used}, {@code created_date}).
     *
     * @param email the client's login email
     * @return the latest live 6-digit code, or {@code null} if there is no eligible row
     */
    public String latestCode(String email) {
        // The backend persists expires_at/created_date as UTC wall-clock in tz-less columns. The
        // JDBC session TZ defaults to the host JVM default (e.g. CEST), so a bare NOW() would be a
        // CEST wall-clock and the predicate expires_at > NOW() would be false the moment the row is
        // written. Compare against NOW() AT TIME ZONE 'UTC' (the current UTC wall-clock, independent
        // of the session TZ) so the comparison matches how the value was stored.
        String sql =
                "SELECT code FROM otp_tokens "
                        + "WHERE lower(email) = lower(?) AND used = false "
                        + "AND expires_at > (NOW() AT TIME ZONE 'UTC') "
                        + "ORDER BY created_date DESC LIMIT 1";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            // Belt-and-braces: pin this connection's session TZ to UTC so any other tz-sensitive
            // reads on it are consistent with the UTC wall-clock stored by the backend.
            try (PreparedStatement tz = c.prepareStatement("SET TIME ZONE 'UTC'")) {
                tz.execute();
            }
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to read the latest OTP code for '" + email + "': " + e.getMessage(), e);
        }
    }
}
