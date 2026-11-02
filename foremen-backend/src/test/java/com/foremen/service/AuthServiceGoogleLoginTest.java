package com.foremen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.foremen.config.security.JwtProperties;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.controller.dto.auth.GoogleLoginResponse;
import com.foremen.dao.InviteTokenDao;
import com.foremen.dao.PasswordResetTokenDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.InviteTokenEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.mail.MailSender;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;

/**
 * FOR-03-10 rules for {@code AuthService.loginWithGoogle}: verified email only, exact email match,
 * CLIENT rejected, INVITED auto-activated, ACTIVE signed in, DEACTIVATED rejected.
 */
class AuthServiceGoogleLoginTest {

    private static final String SECRET = "foremen-jwt-test-secret-key-0123456789ABCDEF";
    private static final String EMAIL = "jan@loaders.dev";

    private UserDao userDao;
    private InviteTokenDao inviteTokenDao;
    private RefreshTokenService refreshTokenService;
    private GoogleIdTokenVerifier verifier;
    private AuthService service;

    @BeforeEach
    void setUp() {
        userDao = Mockito.mock(UserDao.class);
        inviteTokenDao = Mockito.mock(InviteTokenDao.class);
        refreshTokenService = Mockito.mock(RefreshTokenService.class);
        verifier = Mockito.mock(GoogleIdTokenVerifier.class);
        when(refreshTokenService.issue(any(UserEntity.class))).thenReturn("refresh");
        when(userDao.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        JwtProperties props = new JwtProperties(30, 7, SECRET);
        service = new AuthService(userDao, new BCryptPasswordEncoder(4), new JwtTokenProvider(props),
                refreshTokenService, props, Mockito.mock(PasswordResetTokenDao.class),
                Mockito.mock(MailSender.class), Mockito.mock(InviteService.class), inviteTokenDao,
                Mockito.mock(OtpService.class), verifier);
    }

    private void stubToken(String email, Boolean emailVerified) throws Exception {
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setEmail(email);
        payload.setEmailVerified(emailVerified);
        GoogleIdToken token = Mockito.mock(GoogleIdToken.class);
        when(token.getPayload()).thenReturn(payload);
        when(verifier.verify(anyString())).thenReturn(token);
    }

    private UserEntity user(String roleCode, UserStatus status) {
        UserEntity user = new UserEntity();
        user.setId(7L);
        user.setName("Jan");
        user.setEmail(EMAIL);
        user.setStatus(status);
        RoleEntity role = new RoleEntity();
        role.setId(3L);
        role.setCode(roleCode);
        user.setRole(role);
        when(userDao.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        return user;
    }

    private static void assertApiError(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ForemenApiException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(status);
            assertThat(ex.getMessageCode()).isEqualTo(code);
        });
    }

    @Test
    @DisplayName("ACTIVE employee -> AUTHENTICATED with tokens")
    void activeEmployeeSignsIn() throws Exception {
        user("MANAGER", UserStatus.ACTIVE);
        stubToken("Jan@Loaders.dev", true);

        GoogleLoginResponse response = service.loginWithGoogle("t");

        assertThat(response.status()).isEqualTo(GoogleLoginResponse.STATUS_AUTHENTICATED);
        assertThat(response.tokens().accessToken()).isNotBlank();
        assertThat(response.setPasswordToken()).isNull();
    }

    @Test
    @DisplayName("INVITED employee -> auto-activated, invite tokens consumed, password untouched")
    void invitedEmployeeIsAutoActivated() throws Exception {
        UserEntity invited = user("FOREMAN", UserStatus.INVITED);
        InviteTokenEntity t1 = new InviteTokenEntity();
        InviteTokenEntity t2 = new InviteTokenEntity();
        when(inviteTokenDao.findByUserIdAndUsedFalse(7L)).thenReturn(List.of(t1, t2));
        stubToken(EMAIL, true);

        GoogleLoginResponse response = service.loginWithGoogle("t");

        assertThat(response.status()).isEqualTo(GoogleLoginResponse.STATUS_AUTHENTICATED);
        assertThat(response.tokens()).isNotNull();
        assertThat(invited.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(invited.getPasswordHash()).isNull();
        assertThat(t1.isUsed()).isTrue();
        assertThat(t2.isUsed()).isTrue();
        verify(userDao).save(invited);
    }

    @Test
    @DisplayName("unverified Google email -> 401 email.unverified, no lookup")
    void unverifiedEmailRejected() throws Exception {
        stubToken(EMAIL, false);
        assertApiError(() -> service.loginWithGoogle("t"),
                HttpStatus.UNAUTHORIZED, "error.auth.google.email.unverified");

        stubToken(EMAIL, null);
        assertApiError(() -> service.loginWithGoogle("t"),
                HttpStatus.UNAUTHORIZED, "error.auth.google.email.unverified");
        verify(userDao, never()).findByEmail(anyString());
    }

    @Test
    @DisplayName("unknown email -> 403 no.account")
    void unknownEmailRejected() throws Exception {
        when(userDao.findByEmail(anyString())).thenReturn(Optional.empty());
        stubToken("someone@gmail.com", true);
        assertApiError(() -> service.loginWithGoogle("t"),
                HttpStatus.FORBIDDEN, "error.auth.google.no.account");
    }

    @Test
    @DisplayName("CLIENT (ACTIVE or INVITED) -> 403 google.client, no session, no activation")
    void clientRejected() throws Exception {
        UserEntity client = user("CLIENT", UserStatus.INVITED);
        stubToken(EMAIL, true);
        assertApiError(() -> service.loginWithGoogle("t"),
                HttpStatus.FORBIDDEN, "error.auth.google.client");
        assertThat(client.getStatus()).isEqualTo(UserStatus.INVITED);

        user("CLIENT", UserStatus.ACTIVE);
        assertApiError(() -> service.loginWithGoogle("t"),
                HttpStatus.FORBIDDEN, "error.auth.google.client");
        verify(refreshTokenService, never()).issue(any(UserEntity.class));
    }

    @Test
    @DisplayName("DEACTIVATED employee -> 403 account.deactivated")
    void deactivatedRejected() throws Exception {
        user("WORKER", UserStatus.DEACTIVATED);
        stubToken(EMAIL, true);
        assertApiError(() -> service.loginWithGoogle("t"),
                HttpStatus.FORBIDDEN, "error.auth.account.deactivated");
    }

    @Test
    @DisplayName("unverifiable token -> 401 token.invalid")
    void invalidTokenRejected() throws Exception {
        when(verifier.verify(anyString())).thenReturn(null);
        assertApiError(() -> service.loginWithGoogle("t"),
                HttpStatus.UNAUTHORIZED, "error.auth.google.token.invalid");
    }
}
