package com.foremen.service.model.mapper;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.foremen.dao.model.WorkVolumeFormulaEntity;
import com.foremen.service.model.WorkVolumeFormulaServiceExtendedModel;

import jakarta.persistence.EntityManager;

/**
 * Unit test asserting the FOR-05-04 mapper contract for the derived {@code parsedAst} field
 * (task 15.3):
 *
 * <ul>
 *   <li>{@code WorkVolumeFormulaEntity.parsedAst} is never client-settable via the DTO/mapper
 *       layer — {@code WorkVolumeFormulaServiceExtendedModel} does not even expose a
 *       {@code parsedAst} field (the write model was designed without one, task 15.1/15.2), and
 *       {@code WorkVolumeFormulaServiceMapper} leaves the entity's {@code parsedAst} untouched
 *       (ignored on both create and update). Deriving it from {@code sourceText} is
 *       {@code WorkVolumeFormulaService}'s job (task 18.3), not the mapper's.</li>
 * </ul>
 *
 * <p>Note: the sibling assertion that previously covered
 * {@code AssortmentLineItemEntity.typicalProduct} was removed with the FOR-05-04-UI assortment
 * rework — the reworked positions/prices model has no free-text line item and no typical-product
 * provenance FK, so that provenance contract no longer exists.
 *
 * Validates: Requirement 2.6
 */
class WorkVolumeFormulaDerivedFieldsMapperTest {

    private final EntityManager entityManager = mock(EntityManager.class);

    @Test
    @DisplayName("WorkVolumeFormulaServiceMapper: parsedAst is never set by the mapper on create or update")
    void workVolumeFormulaMapper_neverSetsParsedAstFromMapper() throws Exception {
        WorkVolumeFormulaServiceMapper mapper = new WorkVolumeFormulaServiceMapperImpl();
        injectEntityManager(mapper, WorkVolumeFormulaServiceMapper.class);

        // The write model has no parsedAst field at all -- there is nothing to "spoof" from the
        // client. Feed a payload with only sourceText set (the only client-controllable input).
        WorkVolumeFormulaServiceExtendedModel source =
                new WorkVolumeFormulaServiceExtendedModel(null, 1L, "=X26*2");

        WorkVolumeFormulaEntity created = mapper.toCreateDaoModel(source);

        // The mapper alone never derives/sets parsedAst -- proving it is not sourced from the
        // client payload. Deriving it from sourceText is WorkVolumeFormulaService's job (task 18.3).
        assertThat(created.getParsedAst()).isNull();
        assertThat(created.getSourceText()).isEqualTo("=X26*2");

        // updateFields must likewise never touch an already-derived parsedAst on the target.
        WorkVolumeFormulaEntity target = new WorkVolumeFormulaEntity();
        com.foremen.dao.model.formula.FormulaAst.Const existingAst =
                new com.foremen.dao.model.formula.FormulaAst.Const(new BigDecimal("42"));
        target.setParsedAst(existingAst);
        target.setSourceText("=X1");

        WorkVolumeFormulaServiceExtendedModel updateSource =
                new WorkVolumeFormulaServiceExtendedModel(null, 1L, "=X26*3");

        mapper.updateFields(updateSource, target);

        assertThat(target.getParsedAst()).isSameAs(existingAst);
        assertThat(target.getSourceText()).isEqualTo("=X26*3");
    }

    /**
     * Injects the mocked {@link EntityManager} into the mapper's protected {@code entityManager}
     * field via reflection, mirroring {@code EstimateDerivedFieldsIgnoredMapperTest}'s
     * collaborator-injection pattern for MapStruct abstract-class mappers (no Spring context is
     * started for this unit test).
     */
    private void injectEntityManager(Object mapper, Class<?> declaringClass) throws Exception {
        Field field = declaringClass.getDeclaredField("entityManager");
        field.setAccessible(true);
        field.set(mapper, entityManager);
    }
}
