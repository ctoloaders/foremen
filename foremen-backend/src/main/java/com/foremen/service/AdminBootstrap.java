package com.foremen.service;

import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Provisions or updates the single ADMIN account at application startup, guarded by
 * {@code foremen.admin.create=true} (Requirement 11).
 *
 * <p>The bean is registered only when {@code FOREMEN_ADMIN_CREATE} (property
 * {@code foremen.admin.create}) equals {@code true}; when the flag is {@code false}, unset,
 * or any other value, the bean is not created (11.1).
 *
 * <p>When active, it runs once during startup and enforces the following, all within a
 * single transaction:
 * <ol>
 *   <li>Blank password &rarr; fail startup (11.7); blank email &rarr; fail startup (11.8).
 *       Neither branch touches the database.</li>
 *   <li>More than one ADMIN user &rarr; fail startup (11.3).</li>
 *   <li>No ADMIN user &rarr; create one ACTIVE ADMIN with a runtime bcrypt hash (11.4).</li>
 *   <li>Exactly one ADMIN whose email differs (case-insensitively) from the configured email
 *       &rarr; fail startup, no change (11.5).</li>
 *   <li>Exactly one ADMIN with matching email and a non-empty password &rarr; update its hash (11.6).</li>
 * </ol>
 *
 * <p>No plaintext or default admin password is ever persisted &mdash; only the runtime bcrypt
 * hash of the configured password (11.9). Startup is failed by throwing from {@link #run}, which
 * aborts the application context.
 */
@Component
@ConditionalOnProperty(name = "foremen.admin.create", havingValue = "true")
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    /** Exact, case-sensitive role code identifying an administrator. */
    private static final String ADMIN_ROLE_CODE = "ADMIN";

    private final UserDao userDao;
    private final RoleDao roleDao;
    private final BCryptPasswordEncoder passwordEncoder;

    @Value("${foremen.admin.email:}")
    private String adminEmail;

    @Value("${foremen.admin.password:}")
    private String adminPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // 1. Validate configuration before touching the database.
        if (adminPassword == null || adminPassword.isEmpty()) {
            throw new IllegalStateException(
                    "Admin bootstrap enabled but FOREMEN_ADMIN_PASSWORD is missing; "
                            + "set a non-empty admin password to create/update the ADMIN account.");
        }
        if (adminEmail == null || adminEmail.isEmpty()) {
            throw new IllegalStateException(
                    "Admin bootstrap enabled but FOREMEN_ADMIN_EMAIL is missing; "
                            + "set a non-empty admin email to create/update the ADMIN account.");
        }

        // 2. Enforce the single-ADMIN invariant.
        long adminCount = userDao.countByRoleCode(ADMIN_ROLE_CODE);
        if (adminCount > 1) {
            throw new IllegalStateException(
                    "Admin bootstrap found " + adminCount + " users with role " + ADMIN_ROLE_CODE
                            + "; expected at most one. Refusing to start.");
        }

        if (adminCount == 0) {
            createAdmin();
        } else {
            updateExistingAdmin();
        }
    }

    private void createAdmin() {
        RoleEntity adminRole = roleDao.findByCode(ADMIN_ROLE_CODE)
                .orElseThrow(() -> new IllegalStateException(
                        "Admin bootstrap cannot create the ADMIN account: role " + ADMIN_ROLE_CODE
                                + " does not exist. Ensure the role seed migration has run."));

        UserEntity admin = new UserEntity();
        admin.setName(ADMIN_ROLE_CODE);
        admin.setEmail(adminEmail);
        admin.setRole(adminRole);
        admin.setActive(true);
        admin.setStatus(UserStatus.ACTIVE);
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        userDao.save(admin);
        log.info("Admin bootstrap created ADMIN user for email {}", adminEmail);
    }

    private void updateExistingAdmin() {
        List<UserEntity> admins = userDao.findByRoleCode(ADMIN_ROLE_CODE);
        UserEntity existing = admins.get(0);

        if (!existing.getEmail().equalsIgnoreCase(adminEmail)) {
            throw new IllegalStateException(
                    "Admin bootstrap: an ADMIN user already exists with a different email; "
                            + "FOREMEN_ADMIN_EMAIL does not match the existing ADMIN. Refusing to start.");
        }

        existing.setPasswordHash(passwordEncoder.encode(adminPassword));
        userDao.save(existing);
        log.info("Admin bootstrap updated password hash for ADMIN user {}", adminEmail);
    }
}
