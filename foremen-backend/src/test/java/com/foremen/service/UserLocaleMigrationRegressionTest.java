package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.foremen.config.persistence.LocaleAttributeConverter;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkCategoryDao;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.mapper.UserServiceMapper;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-12 task 10.1 (Requirements 12.6, 13.3) — focused regression coverage for the
 * {@code UserEntity.locale} {@code String → Locale} migration on the user surfaces NOT already
 * pinned by task 2.3 ({@code LocaleAttributeConverterTest}) and task 2.5
 * ({@code EmailLocalePropertyTest} / mail-sender tests).
 *
 * <p>Those two tasks already prove the converter round-trip (lowercase {@code "pl"}/{@code "ru"} ⇄
 * {@link java.util.Locale}, blank/unknown → PL) and the {@code EmailLocaleResolver} PL fallback. The
 * remaining migrated behavior is the inbound locale validation on {@link UserService} create/update
 * ({@code validateLocale}), which after the migration routes PL/RU membership through the shared
 * {@link LocaleAttributeConverter#isSupported(java.util.Locale)} — the single source of truth — and
 * the preserved {@code UserEntity.locale} default (RU). This test pins:
 * <ul>
 *   <li>a {@code null} locale is permitted (the entity default applies);</li>
 *   <li>the supported {@code "pl"}/{@code "ru"} tags (and a {@code "RU"} / whitespace variant) are
 *       accepted;</li>
 *   <li>an unsupported tag ({@code "en"}, {@code "de"}) is rejected 400
 *       {@code error.user.locale.invalid};</li>
 *   <li>the migrated {@code UserEntity.locale} field still defaults to RU, so a user created without
 *       an explicit locale behaves as before the migration (RU → Russian emails, R12.4).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserLocaleMigrationRegressionTest {

    @Mock
    private UserDao dao;
    @Mock
    private RoleDao roleDao;
    @Mock
    private UserServiceMapper mapper;
    @Mock
    private AuditLogDao auditLogDao;
    @Mock
    private EntityManager entityManager;
    @Mock
    private InviteService inviteService;
    @Mock
    private ProjectAccessCache projectAccessCache;
    @Mock
    private WorkCategoryDao workCategoryDao;

    private UserService userService;
    private Method validateLocale;

    private UserService service() {
        if (userService == null) {
            userService = new UserService(dao, roleDao, mapper, auditLogDao, entityManager,
                    inviteService, projectAccessCache, workCategoryDao);
        }
        return userService;
    }

    private void invokeValidateLocale(String locale) throws Exception {
        if (validateLocale == null) {
            validateLocale = UserService.class.getDeclaredMethod("validateLocale", String.class);
            validateLocale.setAccessible(true);
        }
        try {
            validateLocale.invoke(service(), locale);
        } catch (InvocationTargetException e) {
            // unwrap so assertThatThrownBy sees the real ForemenApiException.
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    @Nested
    @DisplayName("validateLocale accepts the supported/empty locales (R12.4, R12.6)")
    class Accepts {

        @Test
        @DisplayName("a null locale is permitted (the entity default applies)")
        void nullPermitted() {
            assertThatCode(() -> invokeValidateLocale(null)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("\"pl\" and \"ru\" are accepted")
        void plRuAccepted() {
            assertThatCode(() -> invokeValidateLocale("pl")).doesNotThrowAnyException();
            assertThatCode(() -> invokeValidateLocale("ru")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a whitespace-padded / upper-case supported tag is accepted")
        void normalizedSupportedAccepted() {
            assertThatCode(() -> invokeValidateLocale("  ru  ")).doesNotThrowAnyException();
            assertThatCode(() -> invokeValidateLocale("RU")).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("validateLocale rejects unsupported locales (R12.4, R12.6)")
    class Rejects {

        @Test
        @DisplayName("\"en\" is rejected 400 error.user.locale.invalid")
        void enRejected() {
            assertApiError(() -> invokeValidateLocale("en"), "error.user.locale.invalid");
        }

        @Test
        @DisplayName("\"de\" is rejected 400 error.user.locale.invalid")
        void deRejected() {
            assertApiError(() -> invokeValidateLocale("de"), "error.user.locale.invalid");
        }
    }

    @Nested
    @DisplayName("UserEntity.locale default preserved after the migration (R12.6)")
    class DefaultPreserved {

        @Test
        @DisplayName("a fresh UserEntity defaults locale to RU (the migrated field keeps the prior default)")
        void defaultIsRu() {
            UserEntity user = new UserEntity();
            assertThat(user.getLocale()).isEqualTo(LocaleAttributeConverter.RU);
            // RU resolves to the Russian email locale exactly as before the String -> Locale migration.
            assertThat(EmailLocaleResolver.resolve(user.getLocale())).isEqualTo(EmailLocaleResolver.RU);
        }
    }

    private static void assertApiError(ThrowingRunnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> assertThat(((ForemenApiException) ex).getMessageCode()).isEqualTo(code));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
