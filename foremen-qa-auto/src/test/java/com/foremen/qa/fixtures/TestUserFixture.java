package com.foremen.qa.fixtures;

import com.foremen.qa.support.DataGen;
import com.foremen.qa.support.TestConfig;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Direct-DB test-user provisioning with bcrypt (Requirement 12).
 *
 * <p>Rendering role-specific UI requires logging in as an <b>ACTIVE user with a known password</b>.
 * This fixture provisions such users by writing directly to the isolated test database via JDBC
 * ({@link Db}), never through the application API, and hashes the generated password with
 * {@link BCryptPasswordEncoder} at cost factor 12 so the stored hash ({@code $2a$12$...}) verifies
 * against the backend's bcrypt check (Requirement 12.2, 12.3). All of this lives only in the QA test
 * code; nothing is added to the application (Requirement 12.8).
 *
 * <h2>Create</h2>
 * {@link #createActiveUser(String)} generates a run-unique email + password, resolves
 * {@code role_id} from {@code roles.code}, bcrypt-hashes the password, and inserts a
 * {@code status=ACTIVE}, {@code active=true} user, returning a {@link TestUser}. An overload accepts
 * an explicit email/password (e.g. for CLIENT, whose password is still set so UI password-login
 * works — OTP is not required for provisioning; Requirement 12.5).
 *
 * <h2>Missing roles</h2>
 * {@link #resolveRoleId(String)} fails clearly when a role code does not exist, <b>except</b> for
 * custom {@code TESTROLE_*} codes: when {@link TestConfig#createMissingRole()} is enabled (default)
 * the fixture creates such a role on demand and reports it so the caller can register teardown
 * (Requirement 12.9). System roles (ADMIN, MANAGER, FOREMAN, WORKER, FINANCIER, CLIENT and anything
 * flagged {@code system=true}) are never created or deleted.
 *
 * <h2>Cleanup</h2>
 * {@link #deleteUserAndResources(TestUser)} removes the user FK-safe: {@code project_members} →
 * {@code refresh_tokens} → {@code invite_tokens} → {@code otp_tokens} (by email) → {@code users}.
 * Every statement is idempotent (a missing row is a no-op), so partial failures still converge
 * (Requirement 12.6). Run-created dictionary rows/projects tracked in {@code World} are cleaned by
 * the caller's registered teardown via the API helper; this fixture owns only the user-scoped rows.
 *
 * <p>All SQL is parameterized — no test data is ever string-interpolated into a statement.
 */
public final class TestUserFixture {

    /** Cost factor 12 — matches the backend's {@code PasswordEncoderConfig} bcrypt strength. */
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);

    /** Prefix marking a custom, disposable QA role (never a system role). */
    private static final String CUSTOM_ROLE_PREFIX = "TESTROLE_";

    private final DataSource dataSource;

    /** Custom roles this fixture created on demand, in creation order (for FK-safe teardown). */
    private final java.util.List<Long> createdRoleIds = new java.util.ArrayList<>();

    public TestUserFixture() {
        this(Db.dataSource());
    }

    /** Package/test-visible constructor allowing an injected {@link DataSource}. */
    public TestUserFixture(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ---- Role resolution (Requirement 12.1, 12.9) ----

    /**
     * Resolve {@code roles.id} for the given {@code roles.code}.
     *
     * <p>If the code exists, its id is returned. If it does not exist and the code is a custom
     * {@code TESTROLE_*} and {@link TestConfig#createMissingRole()} is enabled, the role is created
     * (recorded for teardown) and its new id returned. Otherwise this fails with a clear message —
     * system roles are never created here.
     *
     * @param roleCode the role code (e.g. {@code MANAGER}, {@code CLIENT}, {@code TESTROLE_...})
     * @return the resolved (or newly created) role id
     */
    public long resolveRoleId(String roleCode) {
        Long existing = findRoleId(roleCode);
        if (existing != null) {
            return existing;
        }
        boolean custom = roleCode != null && roleCode.startsWith(CUSTOM_ROLE_PREFIX);
        if (custom && TestConfig.createMissingRole()) {
            long id = createCustomRole(roleCode);
            createdRoleIds.add(id);
            return id;
        }
        if (custom) {
            throw new IllegalStateException(
                    "Custom role '" + roleCode + "' does not exist and auto-create is disabled ("
                            + TestConfig.KEY_CREATE_MISSING_ROLE + "=false). Seed the role or enable "
                            + "auto-create.");
        }
        throw new IllegalStateException(
                "Role code '" + roleCode + "' does not exist in roles.code. System roles are never "
                        + "created by the QA fixture; only custom TESTROLE_* codes may be auto-created.");
    }

    // ---- Create (Requirement 12.1–12.5) ----

    /** Create an ACTIVE, login-ready user in {@code roleCode} with generated credentials. */
    public TestUser createActiveUser(String roleCode) {
        return createActiveUser(roleCode, DataGen.nextEmail(), DataGen.nextPassword());
    }

    /**
     * Create an ACTIVE, login-ready user in {@code roleCode} with an explicit email/password.
     *
     * <p>Steps: resolve {@code role_id} → bcrypt-hash the password → parameterized INSERT with
     * {@code status='ACTIVE'}, {@code active=true} → return a {@link TestUser}. For CLIENT the
     * password is set exactly like any other role so UI password-login succeeds (Requirement 12.5).
     */
    public TestUser createActiveUser(String roleCode, String email, String password) {
        // Never let a generated password surface as text in the demo report (Requirement 10.10).
        com.foremen.qa.report.Secrets.registerSecret(password);
        long roleId = resolveRoleId(roleCode);
        String hash = ENCODER.encode(password);
        String sql =
                "INSERT INTO users (name, email, phone, role_id, active, locale, status, "
                        + "password_hash, created_date, created_by) "
                        + "VALUES (?, ?, NULL, ?, true, 'ru', 'ACTIVE', ?, NOW(), 'qa')";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "QA " + email);
            ps.setString(2, email);
            ps.setLong(3, roleId);
            ps.setString(4, hash);
            ps.executeUpdate();
            long id = readGeneratedId(ps);
            return new TestUser(id, email, password, roleCode);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to insert test user '" + email + "' with role '" + roleCode + "': "
                            + e.getMessage(), e);
        }
    }

    // ---- Cleanup (Requirement 12.6) ----

    /**
     * Remove the user and all FK-dependent rows, FK-safe and idempotent.
     *
     * <p>Order: {@code project_members} → {@code refresh_tokens} → {@code invite_tokens} →
     * {@code otp_tokens} (by email) → {@code users}. Missing rows are a no-op, so re-running the
     * cleanup (e.g. once explicitly and once via automatic teardown) is safe. Any custom
     * {@code TESTROLE_*} roles this fixture created are removed last, after the user that referenced
     * them is gone.
     */
    public void deleteUserAndResources(TestUser user) {
        if (user == null) {
            return;
        }
        try (Connection c = dataSource.getConnection()) {
            executeUpdate(c, "DELETE FROM project_members WHERE user_id = ?", user.id());
            executeUpdate(c, "DELETE FROM refresh_tokens WHERE user_id = ?", user.id());
            executeUpdate(c, "DELETE FROM invite_tokens WHERE user_id = ?", user.id());
            executeUpdateStr(c, "DELETE FROM otp_tokens WHERE email = ?", user.email());
            executeUpdate(c, "DELETE FROM users WHERE id = ?", user.id());
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to clean up test user id=" + user.id() + " (" + user.email() + "): "
                            + e.getMessage(), e);
        }
        deleteCreatedCustomRoles();
    }

    /**
     * Delete any custom {@code TESTROLE_*} roles this fixture created on demand, but only if they
     * carry no remaining {@code users} references and are not system roles. Idempotent; safe to call
     * more than once (Requirement 12.9).
     */
    public void deleteCreatedCustomRoles() {
        if (createdRoleIds.isEmpty()) {
            return;
        }
        try (Connection c = dataSource.getConnection()) {
            java.util.Iterator<Long> it = createdRoleIds.iterator();
            while (it.hasNext()) {
                long roleId = it.next();
                // Guard: never delete a role still referenced by a user, or a system role.
                String sql =
                        "DELETE FROM roles WHERE id = ? AND system = false "
                                + "AND NOT EXISTS (SELECT 1 FROM users u WHERE u.role_id = roles.id)";
                executeUpdate(c, sql, roleId);
                it.remove();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clean up custom QA roles: " + e.getMessage(), e);
        }
    }

    // ---- Internals ----

    private Long findRoleId(String roleCode) {
        String sql = "SELECT id FROM roles WHERE code = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, roleCode);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to resolve role id for code '" + roleCode + "': " + e.getMessage(), e);
        }
    }

    private long createCustomRole(String roleCode) {
        String sql =
                "INSERT INTO roles (code, name_ru, name_pl, description_ru, description_pl, system, "
                        + "created_date, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, false, NOW(), 'qa')";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, roleCode);
            ps.setString(2, "QA " + roleCode);
            ps.setString(3, "QA " + roleCode);
            ps.setString(4, "QA custom test role " + roleCode);
            ps.setString(5, "QA custom test role " + roleCode);
            ps.executeUpdate();
            return readGeneratedId(ps);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to create custom role '" + roleCode + "': " + e.getMessage(), e);
        }
    }

    private static long readGeneratedId(PreparedStatement ps) throws SQLException {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            if (keys.next()) {
                return keys.getLong(1);
            }
            throw new SQLException("INSERT did not return a generated id");
        }
    }

    private static void executeUpdate(Connection c, String sql, long param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, param);
            ps.executeUpdate();
        }
    }

    private static void executeUpdateStr(Connection c, String sql, String param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            ps.executeUpdate();
        }
    }
}
