package com.foremen.controller;

import com.foremen.controller.model.DisplayPreferencesRequest;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class DisplayPreferencesController {

    private final UserDao userDao;

    @GetMapping("/{id}/display-preferences")
    public ResponseEntity<Map<String, Object>> getPreferences(@PathVariable Long id) {
        verifyAuthorization(id);
        UserEntity user = findUserById(id);
        Map<String, Object> prefs = user.getDisplayPreferences();
        return ResponseEntity.ok(prefs != null ? prefs : Map.of());
    }

    @PatchMapping("/{id}/display-preferences")
    public ResponseEntity<Map<String, Object>> updatePreferences(
            @PathVariable Long id,
            @RequestBody @Valid DisplayPreferencesRequest request) {
        verifyAuthorization(id);
        UserEntity user = findUserById(id);

        Map<String, Object> prefs = Map.of(
                "themeMode", request.themeMode(),
                "colorScheme", request.colorScheme(),
                "fontSize", request.fontSize()
        );
        user.setDisplayPreferences(prefs);
        userDao.save(user);

        return ResponseEntity.ok(prefs);
    }

    private UserEntity findUserById(Long id) {
        return userDao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", id));
    }

    private void verifyAuthorization(Long requestedUserId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.access.denied");
        }

        String principalName = auth.getName();

        // If the principal name is the user ID (numeric), compare directly
        try {
            Long authenticatedUserId = Long.parseLong(principalName);
            if (!authenticatedUserId.equals(requestedUserId)) {
                throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.access.denied");
            }
            return;
        } catch (NumberFormatException ignored) {
            // Principal name is not numeric — try resolving by email
        }

        // If the principal name is an email, look up the user and compare IDs
        userDao.findByEmail(principalName).ifPresentOrElse(
                user -> {
                    if (!user.getId().equals(requestedUserId)) {
                        throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.access.denied");
                    }
                },
                () -> {
                    throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.access.denied");
                }
        );
    }
}
