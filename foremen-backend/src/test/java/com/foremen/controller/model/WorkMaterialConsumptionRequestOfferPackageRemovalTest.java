package com.foremen.controller.model;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.foremen.dao.ConstructionMaterialTypeDao;
import com.foremen.dao.MaterialTypeDao;
import com.foremen.dao.MeasurementUnitDao;
import com.foremen.dao.WorkItemDao;
import com.foremen.dao.WorkMaterialConsumptionDao;
import com.foremen.dao.model.ConstructionMaterialTypeEntity;
import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.service.WorkMaterialConsumptionService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.WorkMaterialConsumptionServiceExtendedModel;
import com.foremen.service.model.mapper.WorkMaterialConsumptionServiceMapper;

import jakarta.persistence.EntityManager;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Unit test for the consumption write-path after the package-dimension collapse
 * (FOR-05-04-UI-estimate-packages-changes, task 4.2, Requirement 5.6).
 *
 * <p>FOR-05-04 dropped the {@code offer_package_id} column from {@code work_material_consumptions}
 * (changeset {@code 084}); this spec removes the last stale trace of it on the write path — the
 * dead {@code @NotNull Long offerPackageId} that still sat on
 * {@link WorkMaterialConsumptionCreateRequest}/{@link WorkMaterialConsumptionUpdateRequest} and its
 * {@code requirePresent}/{@code requireExisting} resolution (plus the {@code OfferPackageDao}
 * dependency) in {@link WorkMaterialConsumptionService}.
 *
 * <p>This test locks that removal in three ways:
 * <ol>
 *   <li><b>Shape.</b> Neither create nor update request carries an {@code offerPackageId} record
 *       component (Requirement 5.6 — "no longer bound to packages" on the write path).</li>
 *   <li><b>Bean validation.</b> A create/update request built with every genuine required field but
 *       WITHOUT any package binding passes bean validation with zero violations — proving no
 *       {@code @NotNull offerPackageId} constraint remains to reject it.</li>
 *   <li><b>Service write path.</b> {@code validateCreate}/{@code validateUpdate} accept a
 *       fully-valid model that carries no package binding and nothing is persisted (normalize is a
 *       pure pre-persist check) — proving the dropped {@code offerPackageId}
 *       {@code requirePresent}/{@code requireExisting} checks are gone.</li>
 * </ol>
 */
class WorkMaterialConsumptionRequestOfferPackageRemovalTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    /** Reference ids the mocked DAOs treat as existing rows. */
    private static final long VALID_WORK_ITEM_ID = 1L;
    private static final long VALID_MATERIAL_UNIT_ID = 3L;
    private static final long VALID_CONSTRUCTION_TYPE_ID = 4L;

    // ---------------------------------------------------------------------------------------------
    // 1. Shape: the requests no longer carry offerPackageId.
    // ---------------------------------------------------------------------------------------------

    @Test
    void createRequestHasNoOfferPackageIdComponent() {
        assertThat(componentNames(WorkMaterialConsumptionCreateRequest.class))
                .doesNotContain("offerPackageId");
    }

    @Test
    void updateRequestHasNoOfferPackageIdComponent() {
        assertThat(componentNames(WorkMaterialConsumptionUpdateRequest.class))
                .doesNotContain("offerPackageId");
    }

    private static Set<String> componentNames(Class<?> recordType) {
        RecordComponent[] components = recordType.getRecordComponents();
        assertThat(components).as("%s must be a record", recordType.getSimpleName()).isNotNull();
        return Arrays.stream(components).map(RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Bean validation: a request without any package binding is valid.
    // ---------------------------------------------------------------------------------------------

    @Test
    void createRequestWithoutOfferPackageIsBeanValid() {
        WorkMaterialConsumptionCreateRequest request = new WorkMaterialConsumptionCreateRequest(
                VALID_WORK_ITEM_ID,
                ConsumptionBranch.construction,
                VALID_MATERIAL_UNIT_ID,
                VALID_CONSTRUCTION_TYPE_ID,
                null,
                new BigDecimal("1.2500"),
                null,
                null,
                null,
                "expert",
                "doc",
                null,
                "ref");

        Set<ConstraintViolation<WorkMaterialConsumptionCreateRequest>> violations =
                VALIDATOR.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void updateRequestWithoutOfferPackageIsBeanValid() {
        WorkMaterialConsumptionUpdateRequest request = new WorkMaterialConsumptionUpdateRequest(
                VALID_WORK_ITEM_ID,
                ConsumptionBranch.construction,
                VALID_MATERIAL_UNIT_ID,
                VALID_CONSTRUCTION_TYPE_ID,
                null,
                new BigDecimal("1.2500"),
                null,
                null,
                null,
                "expert",
                "doc",
                null,
                "ref");

        Set<ConstraintViolation<WorkMaterialConsumptionUpdateRequest>> violations =
                VALIDATOR.validate(request);

        assertThat(violations).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Service write path: create/update succeed with no package binding, nothing persisted.
    // ---------------------------------------------------------------------------------------------

    @Test
    void serviceCreateAndUpdateSucceedWithoutOfferPackage() {
        Fixture fixture = new Fixture();
        WorkMaterialConsumptionServiceExtendedModel model = validModelWithoutPackage();

        assertThatCode(() -> fixture.service.validateCreate(model)).doesNotThrowAnyException();
        assertThatCode(() -> fixture.service.validateUpdate(null, model)).doesNotThrowAnyException();

        fixture.verifyNothingPersisted();
    }

    /** A fully-valid write model that carries no package binding of any kind. */
    private static WorkMaterialConsumptionServiceExtendedModel validModelWithoutPackage() {
        WorkMaterialConsumptionServiceExtendedModel model =
                new WorkMaterialConsumptionServiceExtendedModel();
        model.setWorkItemId(VALID_WORK_ITEM_ID);
        model.setMaterialUnitId(VALID_MATERIAL_UNIT_ID);
        model.setBranch(ConsumptionBranch.construction);
        model.setConstructionMaterialTypeId(VALID_CONSTRUCTION_TYPE_ID);
        model.setNormQty(new BigDecimal("1.2500"));
        model.setSourceType("expert");
        model.setSourceDoc("doc");
        model.setSourceRef("ref");
        return model;
    }

    /**
     * A {@link WorkMaterialConsumptionService} wired with mocked reference DAOs that resolve only the
     * known-valid ids. There is no {@code OfferPackageDao} constructor argument any more — the
     * compilation of this fixture is itself part of the assertion that the dependency was removed.
     */
    private static final class Fixture {
        final WorkMaterialConsumptionDao dao = Mockito.mock(WorkMaterialConsumptionDao.class);
        final WorkMaterialConsumptionService service;

        Fixture() {
            WorkItemDao workItemDao = Mockito.mock(WorkItemDao.class);
            MeasurementUnitDao measurementUnitDao = Mockito.mock(MeasurementUnitDao.class);
            ConstructionMaterialTypeDao constructionMaterialTypeDao =
                    Mockito.mock(ConstructionMaterialTypeDao.class);
            MaterialTypeDao materialTypeDao = Mockito.mock(MaterialTypeDao.class);

            Mockito.when(workItemDao.findById(VALID_WORK_ITEM_ID))
                    .thenReturn(Optional.of(new WorkItemEntity()));
            Mockito.when(measurementUnitDao.findById(VALID_MATERIAL_UNIT_ID))
                    .thenReturn(Optional.of(new MeasurementUnitEntity()));
            Mockito.when(constructionMaterialTypeDao.findById(VALID_CONSTRUCTION_TYPE_ID))
                    .thenReturn(Optional.of(new ConstructionMaterialTypeEntity()));

            service = new WorkMaterialConsumptionService(
                    dao,
                    Mockito.mock(WorkMaterialConsumptionServiceMapper.class),
                    Mockito.mock(AuditLogDao.class),
                    Mockito.mock(EntityManager.class),
                    workItemDao, measurementUnitDao,
                    constructionMaterialTypeDao, materialTypeDao);
        }

        void verifyNothingPersisted() {
            Mockito.verify(dao, Mockito.never()).save(Mockito.any());
            Mockito.verify(dao, Mockito.never()).saveAll(Mockito.any());
        }
    }
}
