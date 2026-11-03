package com.foremen.service;

import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.UserServiceExtendedModel;
import com.foremen.service.model.mapper.UserServiceMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FOR-07-06 (task 4.2) — {@code seniorityPct} on the user API: {@link UserService#resolveSeniority}
 * gates the value on the WORKER role, defaults a missing value to 0, rejects a value outside
 * {@code [0, 1]} with 400 {@code error.user.seniorityPct.invalid}, and the validate / update paths
 * apply it.
 *
 * <p>Validates: Requirements 3.1, 3.2, 3.3, 3.5
 */
class UserServiceSeniorityTest {

    private static final long WORKER_ROLE_ID = 10L;
    private static final long MANAGER_ROLE_ID = 20L;
    private static final String INVALID_CODE = "error.user.seniorityPct.invalid";

    private UserDao dao;
    private RoleDao roleDao;
    private UserService service;

    @BeforeEach
    void setUp() {
        dao = mock(UserDao.class);
        roleDao = mock(RoleDao.class);
        when(dao.findByEmail(any())).thenReturn(Optional.empty());
        when(roleDao.findById(WORKER_ROLE_ID)).thenReturn(Optional.of(role(WORKER_ROLE_ID, "WORKER")));
        when(roleDao.findById(MANAGER_ROLE_ID)).thenReturn(Optional.of(role(MANAGER_ROLE_ID, "MANAGER")));
        service = new UserService(dao, roleDao, mock(UserServiceMapper.class), mock(AuditLogDao.class),
                mock(EntityManager.class), null, mock(ProjectAccessCache.class), null, null);
    }

    // --- resolveSeniority ---

    @Test
    @DisplayName("WORKER: an in-range value is stored as submitted, including the 0 and 1 bounds (R3.1)")
    void workerInRangeValueIsKept() {
        RoleEntity worker = role(WORKER_ROLE_ID, "WORKER");
        assertThat(service.resolveSeniority(worker, new BigDecimal("0.02"))).isEqualByComparingTo("0.02");
        assertThat(service.resolveSeniority(worker, BigDecimal.ZERO)).isEqualByComparingTo("0");
        assertThat(service.resolveSeniority(worker, BigDecimal.ONE)).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("WORKER: a missing value resolves to 0 (R3.1)")
    void workerNullResolvesToZero() {
        assertThat(service.resolveSeniority(role(WORKER_ROLE_ID, "WORKER"), null)).isEqualByComparingTo("0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANAGER", "FOREMAN", "FINANCIER", "CLIENT", "ADMIN"})
    @DisplayName("Non-WORKER: any value — even an out-of-range one — is stored as 0 (R3.2)")
    void nonWorkerAlwaysZero(String code) {
        RoleEntity role = role(1L, code);
        assertThat(service.resolveSeniority(role, new BigDecimal("0.05"))).isEqualByComparingTo("0");
        assertThat(service.resolveSeniority(role, new BigDecimal("5"))).isEqualByComparingTo("0");
        assertThat(service.resolveSeniority(null, new BigDecimal("0.05"))).isEqualByComparingTo("0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.0001", "-1", "1.0001", "2"})
    @DisplayName("WORKER: a value below 0 or above 1 is rejected with 400 error.user.seniorityPct.invalid (R3.3)")
    void workerOutOfRangeRejected(String value) {
        assertThatThrownBy(() -> service.resolveSeniority(role(WORKER_ROLE_ID, "WORKER"), new BigDecimal(value)))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessageCode()).isEqualTo(INVALID_CODE);
                });
    }

    // --- validate hooks ---

    @Test
    @DisplayName("validateCreate rejects an out-of-range WORKER seniority before anything is saved (R3.3)")
    void validateCreateRejects() {
        assertThatThrownBy(() -> service.validateCreate(model(null, WORKER_ROLE_ID, new BigDecimal("1.5"))))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        ex -> assertThat(ex.getMessageCode()).isEqualTo(INVALID_CODE));
        verify(dao, never()).save(any());
    }

    @Test
    @DisplayName("validateUpdate rejects an out-of-range WORKER seniority, changing nothing (R3.3)")
    void validateUpdateRejects() {
        UserEntity existing = worker(5L, new BigDecimal("0.03"));
        assertThatThrownBy(() -> service.validateUpdate(existing, model(5L, WORKER_ROLE_ID, new BigDecimal("-0.1"))))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        ex -> assertThat(ex.getMessageCode()).isEqualTo(INVALID_CODE));
        assertThat(existing.getSeniorityPct()).isEqualByComparingTo("0.03");
        verify(dao, never()).save(any());
    }

    // --- update path ---

    @Test
    @DisplayName("update stores the submitted WORKER seniority on the user (R3.1)")
    void updateAppliesWorkerSeniority() {
        UserEntity existing = worker(5L, BigDecimal.ZERO);
        when(dao.findById(5L)).thenReturn(Optional.of(existing));

        service.update(5L, model(5L, WORKER_ROLE_ID, new BigDecimal("0.04")));

        assertThat(existing.getSeniorityPct()).isEqualByComparingTo("0.04");
    }

    @Test
    @DisplayName("update with no seniority on a WORKER clears it to 0 (R3.1)")
    void updateNullClearsToZero() {
        UserEntity existing = worker(5L, new BigDecimal("0.04"));
        when(dao.findById(5L)).thenReturn(Optional.of(existing));

        service.update(5L, model(5L, WORKER_ROLE_ID, null));

        assertThat(existing.getSeniorityPct()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("update of a non-WORKER user stores 0 regardless of the submitted value (R3.2)")
    void updateNonWorkerStoresZero() {
        UserEntity existing = new UserEntity();
        existing.setId(6L);
        existing.setEmail("mgr@example.com");
        existing.setRole(role(MANAGER_ROLE_ID, "MANAGER"));
        existing.setSeniorityPct(new BigDecimal("0.04"));
        when(dao.findById(6L)).thenReturn(Optional.of(existing));

        service.update(6L, model(6L, MANAGER_ROLE_ID, new BigDecimal("0.05")));

        assertThat(existing.getSeniorityPct()).isEqualByComparingTo("0");
    }

    // --- fixtures ---

    private static RoleEntity role(long id, String code) {
        RoleEntity role = new RoleEntity();
        role.setId(id);
        role.setCode(code);
        return role;
    }

    private static UserEntity worker(long id, BigDecimal seniority) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setEmail("worker@example.com");
        user.setRole(role(WORKER_ROLE_ID, "WORKER"));
        user.setSeniorityPct(seniority);
        return user;
    }

    private static UserServiceExtendedModel model(Long id, long roleId, BigDecimal seniorityPct) {
        return new UserServiceExtendedModel(
                id, "Test User", "unique@example.com", null, roleId, null, true, "ru",
                null, null, null, null, null, seniorityPct);
    }
}
