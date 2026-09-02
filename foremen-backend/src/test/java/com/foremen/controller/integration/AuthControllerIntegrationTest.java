package com.foremen.controller.integration;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.controller.AuthController;
import com.foremen.controller.advice.ForemenControllerAdvice;
import com.foremen.controller.dto.auth.CurrentUserResponse;
import com.foremen.controller.dto.auth.PermissionView;
import com.foremen.controller.dto.auth.TokenResponse;
import com.foremen.service.AuthService;
import com.foremen.service.InviteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc slice for {@link AuthController} endpoint wiring (task 12.2).
 *
 * <p>This is an example (not property) test. It loads only the web slice — the controller, the
 * global {@link ForemenControllerAdvice}, and message resolution — with a Mockito-mocked
 * {@link AuthService}. It deliberately avoids {@code @SpringBootTest} and Spring Security so the
 * test stays fast and focused on: each endpoint being reachable at its path, {@code /login}
 * returning both tokens for a (stubbed) ACTIVE user, and {@code @Valid} rejecting blank/short
 * fields with HTTP 400.
 *
 * <p>Because Spring Security is not loaded, the {@code @AuthenticationPrincipal Long userId}
 * argument on {@code GET /me} is supplied by a simple {@link HandlerMethodArgumentResolver}
 * registered below.
 *
 * <p>Covers Requirements 3.1, 3.3, 7.2, 8.1, 13.1, 13.4.
 */
@SpringJUnitWebConfig(AuthControllerIntegrationTest.TestWebConfig.class)
class AuthControllerIntegrationTest {

    /** Fixed principal injected for {@code GET /me} in place of Spring Security. */
    static final long PRINCIPAL_USER_ID = 42L;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private AuthService authService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        Mockito.reset(authService);
    }

    // --- LOGIN ---

    @Test
    @DisplayName("POST /login with valid body → 200 + access/refresh tokens (3.3)")
    void loginValidReturnsTokens() throws Exception {
        when(authService.login("user@example.com", "password123"))
                .thenReturn(new TokenResponse("access-abc", "refresh-xyz", 1800L));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "user@example.com", "password": "password123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accessToken").value("access-abc"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-xyz"))
                .andExpect(jsonPath("$.expiresIn").value(1800));
    }

    @Test
    @DisplayName("POST /login with blank email → 400, no service call")
    void loginBlankEmailReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "   ", "password": "password123"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.email").exists());

        verify(authService, never()).login(any(), any());
    }

    @Test
    @DisplayName("POST /login with blank password → 400, no service call")
    void loginBlankPasswordReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "user@example.com", "password": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.password").exists());

        verify(authService, never()).login(any(), any());
    }

    // --- REFRESH ---

    @Test
    @DisplayName("POST /refresh with valid token → 200 + new token pair (7.2)")
    void refreshValidReturnsTokens() throws Exception {
        when(authService.refresh("refresh-old"))
                .thenReturn(new TokenResponse("access-new", "refresh-new", 1800L));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "refresh-old"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-new"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-new"))
                .andExpect(jsonPath("$.expiresIn").value(1800));
    }

    @Test
    @DisplayName("POST /refresh with blank token → 400, no service call")
    void refreshBlankReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.refreshToken").exists());

        verify(authService, never()).refresh(any());
    }

    // --- LOGOUT ---

    @Test
    @DisplayName("POST /logout with valid token → 204 (8.1)")
    void logoutValidReturns204() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "refresh-abc"}
                                """))
                .andExpect(status().isNoContent());

        verify(authService).logout("refresh-abc");
    }

    @Test
    @DisplayName("POST /logout with blank token → 400, no service call")
    void logoutBlankReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "  "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.refreshToken").exists());

        verify(authService, never()).logout(any());
    }

    // --- CURRENT USER (/me) ---

    @Test
    @DisplayName("GET /me → 200 + current user response (argument resolver supplies principal)")
    void meReturnsCurrentUser() throws Exception {
        var response = new CurrentUserResponse(
                PRINCIPAL_USER_ID, "Jane Doe", "jane@example.com", "MANAGER",
                Set.of(new PermissionView("PROJECT", Set.of("READ", "WRITE"))));
        when(authService.currentUser(PRINCIPAL_USER_ID)).thenReturn(response);

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(PRINCIPAL_USER_ID))
                .andExpect(jsonPath("$.name").value("Jane Doe"))
                .andExpect(jsonPath("$.email").value("jane@example.com"))
                .andExpect(jsonPath("$.roleCode").value("MANAGER"))
                .andExpect(jsonPath("$.permissions[0].resource").value("PROJECT"));

        verify(authService).currentUser(PRINCIPAL_USER_ID);
    }

    // --- PASSWORD RESET REQUEST ---

    @Test
    @DisplayName("POST /password-reset/request with valid email → 200 (13.1)")
    void passwordResetRequestValidReturns200() throws Exception {
        mockMvc.perform(post("/api/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "user@example.com"}
                                """))
                .andExpect(status().isOk());

        verify(authService).requestPasswordReset("user@example.com");
    }

    @Test
    @DisplayName("POST /password-reset/request with blank email → 400, no service call")
    void passwordResetRequestBlankReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists());

        verify(authService, never()).requestPasswordReset(any());
    }

    @Test
    @DisplayName("POST /password-reset/request with malformed email → 400 (@Email)")
    void passwordResetRequestMalformedEmailReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "not-an-email"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists());

        verify(authService, never()).requestPasswordReset(any());
    }

    // --- PASSWORD RESET CONFIRM ---

    @Test
    @DisplayName("POST /password-reset/confirm with valid body → 200 (13.4)")
    void passwordResetConfirmValidReturns200() throws Exception {
        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "reset-token", "newPassword": "newSecret123"}
                                """))
                .andExpect(status().isOk());

        verify(authService).confirmPasswordReset("reset-token", "newSecret123");
    }

    @Test
    @DisplayName("POST /password-reset/confirm with short password (<8) → 400, no service call")
    void passwordResetConfirmShortPasswordReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "reset-token", "newPassword": "short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.newPassword").exists());

        verify(authService, never()).confirmPasswordReset(any(), any());
    }

    @Test
    @DisplayName("POST /password-reset/confirm with blank token → 400, no service call")
    void passwordResetConfirmBlankTokenReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token": "", "newPassword": "newSecret123"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.token").exists());

        verify(authService, never()).confirmPasswordReset(any(), any());
    }

    // --- Configuration ---

    @Configuration
    @EnableWebMvc
    @Import({ForemenControllerAdvice.class, AuthController.class})
    static class TestWebConfig implements WebMvcConfigurer {

        /**
         * Supplies a fixed {@link Long} for the {@code @AuthenticationPrincipal Long userId}
         * parameter on {@code GET /me}, standing in for Spring Security in this slice test.
         */
        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            resolvers.add(new HandlerMethodArgumentResolver() {
                @Override
                public boolean supportsParameter(MethodParameter parameter) {
                    return parameter.hasParameterAnnotation(AuthenticationPrincipal.class)
                            && Long.class.equals(parameter.getParameterType());
                }

                @Override
                public Object resolveArgument(MethodParameter parameter,
                                              ModelAndViewContainer mavContainer,
                                              NativeWebRequest webRequest,
                                              WebDataBinderFactory binderFactory) {
                    return PRINCIPAL_USER_ID;
                }
            });
        }

        @Bean
        public ResourceBundleMessageSource messageSource() {
            ResourceBundleMessageSource source = new ResourceBundleMessageSource();
            source.setBasename("messages");
            source.setDefaultEncoding("UTF-8");
            source.setUseCodeAsDefaultMessage(true);
            return source;
        }

        @Bean
        public MessageResolver messageResolver(ResourceBundleMessageSource messageSource) {
            return new MessageResolver(messageSource);
        }

        @Bean
        public LocaleResolver localeResolver() {
            AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
            resolver.setDefaultLocale(Locale.of("pl"));
            return resolver;
        }

        @Bean
        public AuthService authService() {
            return Mockito.mock(AuthService.class);
        }

        @Bean
        public InviteService inviteService() {
            return Mockito.mock(InviteService.class);
        }
    }
}
