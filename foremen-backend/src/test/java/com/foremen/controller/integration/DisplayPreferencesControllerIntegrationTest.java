package com.foremen.controller.integration;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.config.security.JwtAuthenticationEntryPoint;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.config.security.SecurityConfig;
import com.foremen.controller.DisplayPreferencesController;
import com.foremen.controller.advice.ForemenControllerAdvice;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for DisplayPreferencesController using @WebMvcTest.
 * <p>
 * Validates: Requirements 6.3, 6.4, 6.5
 */
@WebMvcTest(controllers = DisplayPreferencesController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, JwtAuthenticationEntryPoint.class,
        ForemenControllerAdvice.class})
class DisplayPreferencesControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserDao userDao;

    @MockitoBean
    private MessageResolver messageResolver;

    @MockitoBean
    private ForemenPermissionEvaluator permissionEvaluator;

    private UserEntity testUser;

    @BeforeEach
    void setUp() {
        RoleEntity role = new RoleEntity();
        role.setId(1L);
        role.setCode("CLIENT");
        role.setNameRU("Клиент");
        role.setNamePL("Klient");

        testUser = new UserEntity();
        testUser.setId(1L);
        testUser.setName("Test User");
        testUser.setEmail("testuser@foremen.com");
        testUser.setRole(role);
        testUser.setActive(true);
        testUser.setLocale("ru");
        testUser.setDisplayPreferences(null);

        when(userDao.findById(1L)).thenReturn(Optional.of(testUser));
        when(userDao.findByEmail("testuser@foremen.com")).thenReturn(Optional.of(testUser));
        when(userDao.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        when(messageResolver.resolve(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // --- PATCH /api/users/{id}/display-preferences (valid) ---

    @Test
    @DisplayName("PATCH with valid body → 200 with correct stored values")
    void patchPreferences_withValidBody_returns200WithStoredValues() throws Exception {
        mockMvc.perform(patch("/api/users/1/display-preferences")
                        .with(user("testuser@foremen.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "themeMode": "dark",
                                    "colorScheme": "blue",
                                    "fontSize": "lg"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.themeMode").value("dark"))
                .andExpect(jsonPath("$.colorScheme").value("blue"))
                .andExpect(jsonPath("$.fontSize").value("lg"));

        verify(userDao).save(any(UserEntity.class));
    }

    // --- PATCH with invalid themeMode → 400 ---

    @Test
    @DisplayName("PATCH with invalid themeMode → 400 with field error details")
    void patchPreferences_withInvalidThemeMode_returns400() throws Exception {
        mockMvc.perform(patch("/api/users/1/display-preferences")
                        .with(user("testuser@foremen.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "themeMode": "invalid-mode",
                                    "colorScheme": "zinc",
                                    "fontSize": "default"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.themeMode").exists());

        verify(userDao, never()).save(any());
    }

    // --- PATCH with invalid colorScheme → 400 ---

    @Test
    @DisplayName("PATCH with invalid colorScheme → 400 with field error details")
    void patchPreferences_withInvalidColorScheme_returns400() throws Exception {
        mockMvc.perform(patch("/api/users/1/display-preferences")
                        .with(user("testuser@foremen.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "themeMode": "dark",
                                    "colorScheme": "neon-purple",
                                    "fontSize": "default"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.colorScheme").exists());

        verify(userDao, never()).save(any());
    }

    // --- PATCH with invalid fontSize → 400 ---

    @Test
    @DisplayName("PATCH with invalid fontSize → 400 with field error details")
    void patchPreferences_withInvalidFontSize_returns400() throws Exception {
        mockMvc.perform(patch("/api/users/1/display-preferences")
                        .with(user("testuser@foremen.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "themeMode": "light",
                                    "colorScheme": "slate",
                                    "fontSize": "xxxl"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.fontSize").exists());

        verify(userDao, never()).save(any());
    }

    // --- PATCH with mismatched user ID → 403 ---

    @Test
    @DisplayName("PATCH with mismatched user ID → 403 Forbidden")
    void patchPreferences_withMismatchedUserId_returns403() throws Exception {
        when(userDao.findByEmail("other@foremen.com")).thenReturn(Optional.empty());

        mockMvc.perform(patch("/api/users/1/display-preferences")
                        .with(user("other@foremen.com"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "themeMode": "dark",
                                    "colorScheme": "zinc",
                                    "fontSize": "default"
                                }
                                """))
                .andExpect(status().isForbidden());

        verify(userDao, never()).save(any());
    }

    // --- GET /api/users/{id}/display-preferences with null prefs → empty object ---

    @Test
    @DisplayName("GET with null displayPreferences → returns empty object")
    void getPreferences_withNullDisplayPreferences_returnsEmptyObject() throws Exception {
        mockMvc.perform(get("/api/users/1/display-preferences")
                        .with(user("testuser@foremen.com")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{}"));
    }

    // --- GET /api/users/{id}/display-preferences returns stored values ---

    @Test
    @DisplayName("GET returns previously stored display preferences")
    void getPreferences_withStoredPreferences_returnsStoredMap() throws Exception {
        testUser.setDisplayPreferences(Map.of(
                "themeMode", "light",
                "colorScheme", "green",
                "fontSize", "xl"
        ));

        mockMvc.perform(get("/api/users/1/display-preferences")
                        .with(user("testuser@foremen.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.themeMode").value("light"))
                .andExpect(jsonPath("$.colorScheme").value("green"))
                .andExpect(jsonPath("$.fontSize").value("xl"));
    }
}
