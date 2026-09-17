package com.foremen.service.audit;

import com.foremen.dao.UserDao;
import com.foremen.dao.model.UserEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuditPerformedByResolver}, the single source of truth for the
 * audit {@code performedBy} id&rarr;name resolution shared by the list endpoint (via
 * {@code AuditServiceMapper}) and the per-entity {@code /{resource}/audit/{id}} endpoint.
 */
class AuditPerformedByResolverTest {

    private final UserDao userDao = mock(UserDao.class);
    private final AuditPerformedByResolver resolver = new AuditPerformedByResolver(userDao);

    private UserEntity user(String name) {
        UserEntity u = new UserEntity();
        u.setName(name);
        return u;
    }

    @Test
    @DisplayName("numeric id resolving to a user returns that user's name")
    void numericId_withUser_resolvesToName() {
        when(userDao.findById(11L)).thenReturn(Optional.of(user("Иван Петров")));

        assertThat(resolver.resolveName("11")).isEqualTo("Иван Петров");
    }

    @Test
    @DisplayName("numeric id with surrounding whitespace is trimmed before lookup")
    void numericId_trimmed() {
        when(userDao.findById(7L)).thenReturn(Optional.of(user("Anna Kowalska")));

        assertThat(resolver.resolveName("  7 ")).isEqualTo("Anna Kowalska");
    }

    @Test
    @DisplayName("numeric id with no matching user returns the id string unchanged")
    void numericId_missingUser_returnsIdUnchanged() {
        when(userDao.findById(99L)).thenReturn(Optional.empty());

        assertThat(resolver.resolveName("99")).isEqualTo("99");
    }

    @Test
    @DisplayName("non-numeric value (SYSTEM) is passed through and never queried")
    void nonNumeric_passthrough() {
        assertThat(resolver.resolveName("SYSTEM")).isEqualTo("SYSTEM");
        verify(userDao, never()).findById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("legacy email value is passed through and never queried")
    void email_passthrough() {
        assertThat(resolver.resolveName("user@example.com")).isEqualTo("user@example.com");
        verify(userDao, never()).findById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("null is passed through")
    void nullValue_passthrough() {
        assertThat(resolver.resolveName(null)).isNull();
    }

    @Test
    @DisplayName("blank value is passed through")
    void blank_passthrough() {
        assertThat(resolver.resolveName("   ")).isEqualTo("   ");
    }
}
