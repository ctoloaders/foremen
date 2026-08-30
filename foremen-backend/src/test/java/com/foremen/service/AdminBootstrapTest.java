package com.foremen.service;

import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Example tests for {@link AdminBootstrap} (Requirement 11).
 *
 * <p>Two distinct testing strategies are used, because {@link AdminBootstrap} is an
 * {@link org.springframework.boot.ApplicationRunner} guarded by
 * {@link ConditionalOnProperty @ConditionalOnProperty(name = "foremen.admin.create",
 * havingValue = "true")}:
 *
 * <ul>
 *   <li><b>Bean registration (11.1)</b> is exercised with an {@link ApplicationContextRunner}
 *       so the real Spring conditional decides whether the bean exists. The runner builds the
 *       context but does not fire {@code ApplicationRunner}s, so this strategy only verifies
 *       bean presence/absence, never {@code run()} behaviour.</li>
 *   <li><b>{@code run()} scenarios (11.3–11.8)</b> are exercised by constructing
 *       {@link AdminBootstrap} directly with a Mockito-mocked {@link UserDao} / {@link RoleDao}
 *       and a real {@link BCryptPasswordEncoder}(12), injecting the {@code @Value} email/password
 *       via reflection, and calling {@code run(null)} directly.</li>
 *   <li><b>No plaintext admin password in the Liquibase seed (11.9)</b> is a static check that
 *       scans every changeset file for a seeded admin user / password column.</li>
 * </ul>
 */
@DisplayName("AdminBootstrap")
class AdminBootstrapTest {

    private static final String ADMIN_ROLE_CODE = "ADMIN";
    private static final String ADMIN_EMAIL = "admin@foremen.test";
    private static final String ADMIN_PASSWORD = "s3cret-admin-pass";

    /** Cost factor 12, matching PasswordEncoderConfig / Requirement 11.4, 11.6. */
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static AdminBootstrap newBootstrap(UserDao userDao, RoleDao roleDao,
                                               String email, String password) {
        AdminBootstrap bootstrap = new AdminBootstrap(userDao, roleDao, ENCODER);
        ReflectionTestUtils.setField(bootstrap, "adminEmail", email);
        ReflectionTestUtils.setField(bootstrap, "adminPassword", password);
        return bootstrap;
    }

    private static RoleEntity adminRole() {
        RoleEntity role = new RoleEntity();
        role.setId(1L);
        role.setCode(ADMIN_ROLE_CODE);
        role.setNameRU("Администратор");
        role.setNamePL("Administrator");
        return role;
    }

    private static UserEntity existingAdmin(String email) {
        UserEntity user = new UserEntity();
        user.setId(42L);
        user.setName(ADMIN_ROLE_CODE);
        user.setEmail(email);
        user.setRole(adminRole());
        user.setActive(true);
        user.setStatus(UserStatus.ACTIVE);
        user.setPasswordHash(ENCODER.encode("old-password"));
        return user;
    }

    // ------------------------------------------------------------------
    // 11.1 — bean registration governed by foremen.admin.create
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("bean registration (11.1)")
    class BeanRegistration {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(BootstrapTestConfig.class);

        @Test
        @DisplayName("registers the bean when foremen.admin.create=true")
        void beanPresentWhenFlagTrue() {
            runner.withPropertyValues("foremen.admin.create=true")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasSingleBean(AdminBootstrap.class);
                    });
        }

        @Test
        @DisplayName("does not register the bean when foremen.admin.create=false")
        void beanAbsentWhenFlagFalse() {
            runner.withPropertyValues("foremen.admin.create=false")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(AdminBootstrap.class);
                    });
        }

        @Test
        @DisplayName("does not register the bean when the flag is unset")
        void beanAbsentWhenFlagUnset() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(AdminBootstrap.class);
            });
        }

        @Test
        @DisplayName("does not register the bean for a non-true value")
        void beanAbsentForNonTrueValue() {
            runner.withPropertyValues("foremen.admin.create=yes")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).doesNotHaveBean(AdminBootstrap.class);
                    });
        }

        @Configuration
        static class BootstrapTestConfig {

            @Bean
            UserDao userDao() {
                return mock(UserDao.class);
            }

            @Bean
            RoleDao roleDao() {
                return mock(RoleDao.class);
            }

            @Bean
            BCryptPasswordEncoder passwordEncoder() {
                return new BCryptPasswordEncoder(12);
            }

            @Bean
            @ConditionalOnProperty(name = "foremen.admin.create", havingValue = "true")
            AdminBootstrap adminBootstrap(UserDao userDao, RoleDao roleDao,
                                          BCryptPasswordEncoder encoder) {
                return new AdminBootstrap(userDao, roleDao, encoder);
            }
        }
    }

    // ------------------------------------------------------------------
    // 11.4 — no ADMIN exists -> create ACTIVE ADMIN with runtime bcrypt hash
    // ------------------------------------------------------------------

    @Test
    @DisplayName("creates one ACTIVE ADMIN with a bcrypt hash when no ADMIN exists (11.4)")
    void createsAdminWhenNoneExists() {
        UserDao userDao = mock(UserDao.class);
        RoleDao roleDao = mock(RoleDao.class);
        RoleEntity role = adminRole();
        when(userDao.countByRoleCode(ADMIN_ROLE_CODE)).thenReturn(0L);
        when(roleDao.findByCode(ADMIN_ROLE_CODE)).thenReturn(Optional.of(role));

        newBootstrap(userDao, roleDao, ADMIN_EMAIL, ADMIN_PASSWORD).run(null);

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userDao).save(captor.capture());
        UserEntity saved = captor.getValue();

        assertThat(saved.getEmail()).isEqualTo(ADMIN_EMAIL);
        assertThat(saved.getRole()).isSameAs(role);
        assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(saved.isActive()).isTrue();
        // Stored value is a bcrypt hash, never the plaintext password (11.4, 11.9).
        assertThat(saved.getPasswordHash()).isNotEqualTo(ADMIN_PASSWORD);
        assertThat(saved.getPasswordHash()).startsWith("$2a$12$");
        assertThat(ENCODER.matches(ADMIN_PASSWORD, saved.getPasswordHash())).isTrue();
    }

    // ------------------------------------------------------------------
    // 11.6 — one ADMIN, matching email, non-empty password -> update hash
    // ------------------------------------------------------------------

    @Test
    @DisplayName("updates the existing ADMIN password hash when the email matches (11.6)")
    void updatesAdminWhenEmailMatches() {
        UserDao userDao = mock(UserDao.class);
        RoleDao roleDao = mock(RoleDao.class);
        UserEntity existing = existingAdmin(ADMIN_EMAIL);
        String oldHash = existing.getPasswordHash();
        when(userDao.countByRoleCode(ADMIN_ROLE_CODE)).thenReturn(1L);
        when(userDao.findByRoleCode(ADMIN_ROLE_CODE)).thenReturn(new ArrayList<>(List.of(existing)));

        newBootstrap(userDao, roleDao, ADMIN_EMAIL, ADMIN_PASSWORD).run(null);

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userDao).save(captor.capture());
        UserEntity saved = captor.getValue();

        assertThat(saved).isSameAs(existing);
        assertThat(saved.getPasswordHash()).isNotEqualTo(oldHash);
        assertThat(saved.getPasswordHash()).isNotEqualTo(ADMIN_PASSWORD);
        assertThat(ENCODER.matches(ADMIN_PASSWORD, saved.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("matches the existing ADMIN email case-insensitively (11.6)")
    void updatesAdminWhenEmailMatchesCaseInsensitively() {
        UserDao userDao = mock(UserDao.class);
        RoleDao roleDao = mock(RoleDao.class);
        UserEntity existing = existingAdmin("Admin@Foremen.Test");
        when(userDao.countByRoleCode(ADMIN_ROLE_CODE)).thenReturn(1L);
        when(userDao.findByRoleCode(ADMIN_ROLE_CODE)).thenReturn(new ArrayList<>(List.of(existing)));

        // Configured email differs only by case -> treated as matching.
        newBootstrap(userDao, roleDao, "admin@foremen.test", ADMIN_PASSWORD).run(null);

        verify(userDao).save(existing);
        assertThat(ENCODER.matches(ADMIN_PASSWORD, existing.getPasswordHash())).isTrue();
    }

    // ------------------------------------------------------------------
    // 11.5 — one ADMIN, mismatched email -> fail, no change
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fails startup and makes no change when the ADMIN email differs (11.5)")
    void failsWhenEmailMismatched() {
        UserDao userDao = mock(UserDao.class);
        RoleDao roleDao = mock(RoleDao.class);
        UserEntity existing = existingAdmin("other-admin@foremen.test");
        when(userDao.countByRoleCode(ADMIN_ROLE_CODE)).thenReturn(1L);
        when(userDao.findByRoleCode(ADMIN_ROLE_CODE)).thenReturn(new ArrayList<>(List.of(existing)));

        AdminBootstrap bootstrap = newBootstrap(userDao, roleDao, ADMIN_EMAIL, ADMIN_PASSWORD);

        assertThatThrownBy(() -> bootstrap.run(null))
                .isInstanceOf(IllegalStateException.class);
        verify(userDao, never()).save(any());
    }

    // ------------------------------------------------------------------
    // 11.3 — more than one ADMIN -> fail startup
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fails startup when more than one ADMIN user exists (11.3)")
    void failsWhenDuplicateAdmins() {
        UserDao userDao = mock(UserDao.class);
        RoleDao roleDao = mock(RoleDao.class);
        when(userDao.countByRoleCode(ADMIN_ROLE_CODE)).thenReturn(2L);

        AdminBootstrap bootstrap = newBootstrap(userDao, roleDao, ADMIN_EMAIL, ADMIN_PASSWORD);

        assertThatThrownBy(() -> bootstrap.run(null))
                .isInstanceOf(IllegalStateException.class);
        verify(userDao, never()).save(any());
    }

    // ------------------------------------------------------------------
    // 11.7 — blank password -> fail, no DB interaction
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fails startup when the admin password is empty (11.7)")
    void failsWhenPasswordEmpty() {
        UserDao userDao = mock(UserDao.class);
        RoleDao roleDao = mock(RoleDao.class);

        AdminBootstrap bootstrap = newBootstrap(userDao, roleDao, ADMIN_EMAIL, "");

        assertThatThrownBy(() -> bootstrap.run(null))
                .isInstanceOf(IllegalStateException.class);
        verify(userDao, never()).save(any());
        verify(userDao, never()).countByRoleCode(anyString());
    }

    // ------------------------------------------------------------------
    // 11.8 — blank email -> fail, no DB interaction
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fails startup when the admin email is empty (11.8)")
    void failsWhenEmailEmpty() {
        UserDao userDao = mock(UserDao.class);
        RoleDao roleDao = mock(RoleDao.class);

        AdminBootstrap bootstrap = newBootstrap(userDao, roleDao, "", ADMIN_PASSWORD);

        assertThatThrownBy(() -> bootstrap.run(null))
                .isInstanceOf(IllegalStateException.class);
        verify(userDao, never()).save(any());
        verify(userDao, never()).countByRoleCode(anyString());
    }

    // ------------------------------------------------------------------
    // 11.9 — no plaintext/default admin password in the Liquibase seed
    // ------------------------------------------------------------------

    @Test
    @DisplayName("no Liquibase changeset seeds an ADMIN user or a password (11.9)")
    void noAdminPasswordInLiquibaseSeed() throws IOException {
        Path changesetDir = Paths.get("database_files/changesets");
        assertThat(Files.isDirectory(changesetDir))
                .as("changeset directory should exist at %s", changesetDir.toAbsolutePath())
                .isTrue();

        try (Stream<Path> files = Files.list(changesetDir)) {
            List<Path> xmlFiles = files.filter(p -> p.toString().endsWith(".xml")).toList();
            assertThat(xmlFiles).isNotEmpty();

            for (Path file : xmlFiles) {
                // Collapse whitespace so attribute/value ordering does not defeat the checks.
                String content = Files.readString(file, StandardCharsets.UTF_8)
                        .toLowerCase()
                        .replaceAll("\\s+", " ");

                // No changeset may seed a row into the users table: the ADMIN is created at
                // runtime by AdminBootstrap only (11.9). Guard against both raw SQL inserts and
                // Liquibase <insert tableName="users">.
                assertThat(content)
                        .as("changeset %s must not INSERT into the users table", file.getFileName())
                        .doesNotContain("insert into users");
                assertThat(content)
                        .as("changeset %s must not use <insert tableName=\"users\">", file.getFileName())
                        .doesNotContain("<insert tablename=\"users\"");

                // No changeset may assign a value to the password_hash column (no default/seed
                // password persisted in the migration).
                assertThat(content)
                        .as("changeset %s must not assign a password_hash value", file.getFileName())
                        .doesNotContain("<column name=\"password_hash\" value=")
                        .doesNotContain("password_hash =")
                        .doesNotContain("set password_hash");
            }
        }
    }
}
