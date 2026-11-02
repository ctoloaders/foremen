package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.foremen.config.security.JwtProperties;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.controller.dto.auth.PasswordResetConfirm;
import com.foremen.dao.PasswordResetTokenDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.PasswordResetTokenEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.service.mail.MailSender;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * FOR-03-09: reset request is issued only to ACTIVE non-CLIENT password accounts; confirm enforces
 * the 8..72 password policy.
 */
class AuthServicePasswordResetTest {

    private static final String SECRET = "foremen-jwt-test-secret-key-0123456789ABCDEF";

    private UserDao userDao;
    private PasswordResetTokenDao tokenDao;
    private MailSender mailSender;
    private AuthService service;

    @BeforeEach
    void setUp() {
        userDao = Mockito.mock(UserDao.class);
        tokenDao = Mockito.mock(PasswordResetTokenDao.class);
        mailSender = Mockito.mock(MailSender.class);
        RefreshTokenService refreshTokenService = Mockito.mock(RefreshTokenService.class);
        when(tokenDao.save(any(PasswordResetTokenEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        JwtProperties props = new JwtProperties(30, 7, SECRET);
        service = new AuthService(userDao, new BCryptPasswordEncoder(4), new JwtTokenProvider(props),
                refreshTokenService, props, tokenDao, mailSender, null, null, null, null);
    }

    private static UserEntity user(String roleCode, UserStatus status) {
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setName("U");
        user.setEmail("u@example.com");
        user.setStatus(status);
        RoleEntity role = new RoleEntity();
        role.setId(1L);
        role.setCode(roleCode);
        user.setRole(role);
        return user;
    }

    @Test
    @DisplayName("ACTIVE employee gets a token and a mail addressed to the user entity")
    void activeEmployeeGetsTokenAndMail() {
        UserEntity manager = user("MANAGER", UserStatus.ACTIVE);
        when(userDao.findByEmail("u@example.com")).thenReturn(Optional.of(manager));

        service.requestPasswordReset("U@example.com");

        verify(tokenDao).save(any(PasswordResetTokenEntity.class));
        verify(mailSender).sendPasswordReset(eq(manager), anyString());
    }

    @Test
    @DisplayName("mail transport failure is swallowed so the request stays neutral (no 500)")
    void mailFailureIsNotPropagated() {
        UserEntity manager = user("MANAGER", UserStatus.ACTIVE);
        when(userDao.findByEmail("u@example.com")).thenReturn(Optional.of(manager));
        Mockito.doThrow(new IllegalStateException("smtp down"))
                .when(mailSender).sendPasswordReset(any(UserEntity.class), anyString());

        org.assertj.core.api.Assertions.assertThatCode(() -> service.requestPasswordReset("u@example.com"))
                .doesNotThrowAnyException();
        verify(tokenDao).save(any(PasswordResetTokenEntity.class));
    }

    @Test
    @DisplayName("ACTIVE CLIENT gets a neutral outcome: no token, no mail")
    void clientIsExcluded() {
        when(userDao.findByEmail("u@example.com")).thenReturn(Optional.of(user("CLIENT", UserStatus.ACTIVE)));

        service.requestPasswordReset("u@example.com");

        verify(tokenDao, never()).save(any(PasswordResetTokenEntity.class));
        verify(mailSender, never()).sendPasswordReset(any(UserEntity.class), anyString());
    }

    @Test
    @DisplayName("INVITED employee and unknown email: no token, no mail")
    void invitedAndUnknownAreNeutral() {
        when(userDao.findByEmail("u@example.com")).thenReturn(Optional.of(user("MANAGER", UserStatus.INVITED)));
        when(userDao.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        service.requestPasswordReset("u@example.com");
        service.requestPasswordReset("nobody@example.com");

        verify(tokenDao, never()).save(any(PasswordResetTokenEntity.class));
        verify(mailSender, never()).sendPasswordReset(any(UserEntity.class), anyString());
    }

    @Test
    @DisplayName("confirm DTO accepts 8 and 72 chars, rejects 7 and 73")
    void confirmPasswordLengthPolicy() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(new PasswordResetConfirm("t", "a".repeat(8)))).isEmpty();
            assertThat(validator.validate(new PasswordResetConfirm("t", "a".repeat(72)))).isEmpty();
            assertThat(validator.validate(new PasswordResetConfirm("t", "a".repeat(7))))
                    .anyMatch(v -> v.getPropertyPath().toString().equals("newPassword"));
            assertThat(validator.validate(new PasswordResetConfirm("t", "a".repeat(73))))
                    .anyMatch(v -> v.getPropertyPath().toString().equals("newPassword"));
        }
    }
}
