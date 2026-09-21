package com.foremen.service;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.foremen.dao.CurrencyDao;
import com.foremen.dao.EstimateDao;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.estimate.EstimateRecomputeService;
import com.foremen.service.model.EstimateServiceExtendedModel;
import com.foremen.service.model.mapper.EstimateServiceMapper;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EstimateService}, exercising {@link EstimateService#getOrCreateForProject}
 * and the explicit-{@code create()} rejection with stubbed DAOs/mapper and no Spring context,
 * following the {@code ProjectMemberServiceTest} Mockito convention for a full service class with
 * DAO collaborators (rather than a jqwik property test, which the repo reserves for pure derivation
 * logic exercised without mocks — see {@code EstimateRecomputeServiceLineValuePropertyTest}).
 * {@code EstimateService} is not itself a pure function: its behavior is defined by how it
 * sequences calls to {@code EstimateDao}, so a property over "N calls to getOrCreateForProject"
 * would just be re-verifying Mockito stub wiring under a jqwik wrapper. Repeated calls (b) are
 * still exercised directly to demonstrate idempotency across multiple invocations.
 *
 * <p>Feature: FOR-05-03-estimate-core, Property 1: Single estimate per project
 *
 * <p><b>Validates: Requirements 1.1, 1.5, 1.6</b>
 */
@ExtendWith(MockitoExtension.class)
class EstimateServiceTest {

    @Mock
    private EstimateDao estimateDao;
    @Mock
    private EstimateServiceMapper estimateServiceMapper;
    @Mock
    private ProjectAccessCache projectAccessCache;
    @Mock
    private AuditLogDao auditLogDao;
    @Mock
    private EntityManager entityManager;
    @Mock
    private CurrencyDao currencyDao;
    @Mock
    private EstimateRecomputeService estimateRecomputeService;

    @InjectMocks
    private EstimateService service;

    private static final Long PROJECT_ID = 42L;
    private static final Long PLN_CURRENCY_ID = 1L;

    // ------------------------------------------------------------------------------------------
    // Property 1: Single estimate per project — Validates: Requirements 1.1, 1.5, 1.6
    // ------------------------------------------------------------------------------------------

    // --- (a) no estimate exists yet: creates exactly one and returns it ---

    @Test
    @DisplayName("getOrCreateForProject creates exactly one estimate when none exists yet")
    void getOrCreateForProjectCreatesExactlyOneWhenAbsent() {
        CurrencyEntity pln = new CurrencyEntity();
        pln.setId(PLN_CURRENCY_ID);
        pln.setCode("PLN");

        EstimateEntity savedEntity = new EstimateEntity();
        savedEntity.setId(100L);
        EstimateServiceExtendedModel savedModel = new EstimateServiceExtendedModel();
        savedModel.setId(100L);
        savedModel.setProjectId(PROJECT_ID);

        // First lookup (validateCreate) and the getOrCreateForProject lookup both see "absent".
        when(estimateDao.findByProjectId(PROJECT_ID)).thenReturn(Optional.empty());
        when(currencyDao.findByCode("PLN")).thenReturn(Optional.of(pln));
        when(estimateServiceMapper.toCreateDaoModel(any(EstimateServiceExtendedModel.class)))
                .thenReturn(savedEntity);
        when(estimateDao.save(any(EstimateEntity.class))).thenReturn(savedEntity);
        when(estimateServiceMapper.toServiceExtendedModel(savedEntity)).thenReturn(savedModel);

        EstimateServiceExtendedModel result = service.getOrCreateForProject(PROJECT_ID);

        assertThat(result.getId()).isEqualTo(100L);
        verify(estimateDao, times(1)).save(any(EstimateEntity.class));
        verify(estimateRecomputeService, times(1)).recomputeEstimate(savedEntity);

        // Defaults applied to the model that reached the mapper (R1.2, R1.3).
        ArgumentCaptor<EstimateServiceExtendedModel> captor =
                ArgumentCaptor.forClass(EstimateServiceExtendedModel.class);
        verify(estimateServiceMapper).toCreateDaoModel(captor.capture());
        assertThat(captor.getValue().getCurrencyId()).isEqualTo(PLN_CURRENCY_ID);
        assertThat(captor.getValue().getStatus()).isEqualTo(EstimateStatus.DRAFT);
    }

    // --- (b) called again for the same project: returns the SAME estimate, no second create ---

    @Test
    @DisplayName("getOrCreateForProject called repeatedly resolves the same estimate without creating a second one")
    void getOrCreateForProjectIsIdempotentAcrossRepeatedCalls() {
        EstimateEntity existingEntity = new EstimateEntity();
        existingEntity.setId(200L);
        EstimateServiceExtendedModel existingModel = new EstimateServiceExtendedModel();
        existingModel.setId(200L);
        existingModel.setProjectId(PROJECT_ID);

        when(estimateDao.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(existingEntity));
        when(estimateServiceMapper.toServiceExtendedModel(existingEntity)).thenReturn(existingModel);

        // Call it N >= 1 times (here N = 3): every call must resolve to the same estimate id.
        EstimateServiceExtendedModel first = service.getOrCreateForProject(PROJECT_ID);
        EstimateServiceExtendedModel second = service.getOrCreateForProject(PROJECT_ID);
        EstimateServiceExtendedModel third = service.getOrCreateForProject(PROJECT_ID);

        assertThat(first.getId()).isEqualTo(200L);
        assertThat(second.getId()).isEqualTo(200L);
        assertThat(third.getId()).isEqualTo(200L);

        // No create ever happens on the resolve-existing branch (count stays at exactly 1 estimate).
        verify(estimateDao, never()).save(any(EstimateEntity.class));
        verify(estimateRecomputeService, never()).recomputeEstimate(any(EstimateEntity.class));
    }

    // --- (c) explicit create() when one already exists: rejected with 409 error.estimate.already.exists ---

    @Test
    @DisplayName("explicit create() for an already-estimated project throws 409 error.estimate.already.exists")
    void explicitCreateWhenAlreadyEstimatedThrowsConflict() {
        EstimateEntity existingEntity = new EstimateEntity();
        existingEntity.setId(300L);

        when(estimateDao.findByProjectId(PROJECT_ID)).thenReturn(Optional.of(existingEntity));

        EstimateServiceExtendedModel duplicateAttempt = new EstimateServiceExtendedModel();
        duplicateAttempt.setProjectId(PROJECT_ID);

        assertThatThrownBy(() -> service.create(duplicateAttempt))
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getMessageCode()).isEqualTo("error.estimate.already.exists");
                });

        // Rejected before any persistence or recompute side effect.
        verify(estimateDao, never()).save(any(EstimateEntity.class));
        verify(estimateRecomputeService, never()).recomputeEstimate(any(EstimateEntity.class));
    }
}
