package com.foremen.service.model.mapper;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.foremen.dao.model.AssortmentLineItemEntity;
import com.foremen.dao.model.MaterialEntity;
import com.foremen.dao.model.WorkVolumeFormulaEntity;
import com.foremen.service.model.AssortmentLineItemServiceExtendedModel;
import com.foremen.service.model.WorkVolumeFormulaServiceExtendedModel;

import jakarta.persistence.EntityManager;

/**
 * Unit tests asserting the FOR-05-04 mapper contract for the two derived/assistive fields called
 * out in task 15.3:
 *
 * <ul>
 *   <li>{@code WorkVolumeFormulaEntity.parsedAst} is never client-settable via the DTO/mapper
 *       layer — {@code WorkVolumeFormulaServiceExtendedModel} does not even expose a
 *       {@code parsedAst} field (the write model was designed without one, task 15.1/15.2), and
 *       {@code WorkVolumeFormulaServiceMapper} leaves the entity's {@code parsedAst} untouched
 *       (ignored on both create and update). Deriving it from {@code sourceText} is
 *       {@code WorkVolumeFormulaService}'s job (task 18.3), not the mapper's.</li>
 *   <li>{@code AssortmentLineItemEntity.typicalProduct} is a provenance-only FK that never sources
 *       {@code minPrice}/{@code avgPrice}/{@code maxPrice} — those three fields come straight from
 *       the write model regardless of whether {@code typicalProductId} is null or set.</li>
 * </ul>
 *
 * Validates: Requirements 2.6, 6.6, 6.7
 */
class WorkVolumeFormulaAndAssortmentLineItemDerivedFieldsMapperTest {

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

    @Test
    @DisplayName("AssortmentLineItemServiceMapper: typicalProductId never alters min/avg/max on create")
    void assortmentLineItemMapper_typicalProductNeverAltersPrices_onCreate() throws Exception {
        AssortmentLineItemServiceMapper mapper = new AssortmentLineItemServiceMapperImpl();
        injectEntityManager(mapper, AssortmentLineItemServiceMapper.class);

        MaterialEntity materialRef = mock(MaterialEntity.class);
        when(entityManager.getReference(MaterialEntity.class, 99L)).thenReturn(materialRef);

        AssortmentLineItemServiceExtendedModel withoutTypicalProduct = new AssortmentLineItemServiceExtendedModel(
                null, null, null, "Miska WC", "Miska WC",
                new BigDecimal("100.00"), new BigDecimal("150.00"), new BigDecimal("200.00"),
                new BigDecimal("1"), null);

        AssortmentLineItemServiceExtendedModel withTypicalProduct = new AssortmentLineItemServiceExtendedModel(
                null, null, null, "Miska WC", "Miska WC",
                new BigDecimal("100.00"), new BigDecimal("150.00"), new BigDecimal("200.00"),
                new BigDecimal("1"), 99L);

        AssortmentLineItemEntity createdWithout = mapper.toCreateDaoModel(withoutTypicalProduct);
        AssortmentLineItemEntity createdWith = mapper.toCreateDaoModel(withTypicalProduct);

        assertThat(createdWithout.getTypicalProduct()).isNull();
        assertThat(createdWith.getTypicalProduct()).isSameAs(materialRef);

        // The presence/absence of typicalProduct must not change the price fields at all.
        assertThat(createdWith.getMinPrice()).isEqualTo(createdWithout.getMinPrice()).isEqualTo(new BigDecimal("100.00"));
        assertThat(createdWith.getAvgPrice()).isEqualTo(createdWithout.getAvgPrice()).isEqualTo(new BigDecimal("150.00"));
        assertThat(createdWith.getMaxPrice()).isEqualTo(createdWithout.getMaxPrice()).isEqualTo(new BigDecimal("200.00"));
    }

    @Test
    @DisplayName("AssortmentLineItemServiceMapper: typicalProductId never alters min/avg/max on update")
    void assortmentLineItemMapper_typicalProductNeverAltersPrices_onUpdate() throws Exception {
        AssortmentLineItemServiceMapper mapper = new AssortmentLineItemServiceMapperImpl();
        injectEntityManager(mapper, AssortmentLineItemServiceMapper.class);

        MaterialEntity materialRef = mock(MaterialEntity.class);
        when(entityManager.getReference(MaterialEntity.class, 99L)).thenReturn(materialRef);

        AssortmentLineItemServiceExtendedModel withTypicalProduct = new AssortmentLineItemServiceExtendedModel(
                null, null, null, "Miska WC", "Miska WC",
                new BigDecimal("300.00"), new BigDecimal("350.00"), new BigDecimal("400.00"),
                new BigDecimal("2"), 99L);

        AssortmentLineItemEntity targetWithoutTypicalProduct = new AssortmentLineItemEntity();
        AssortmentLineItemEntity targetWithTypicalProduct = new AssortmentLineItemEntity();

        mapper.updateFields(withTypicalProduct, targetWithoutTypicalProduct);
        mapper.updateFields(withTypicalProduct, targetWithTypicalProduct);

        assertThat(targetWithoutTypicalProduct.getMinPrice())
                .isEqualTo(targetWithTypicalProduct.getMinPrice())
                .isEqualTo(new BigDecimal("300.00"));
        assertThat(targetWithoutTypicalProduct.getAvgPrice())
                .isEqualTo(targetWithTypicalProduct.getAvgPrice())
                .isEqualTo(new BigDecimal("350.00"));
        assertThat(targetWithoutTypicalProduct.getMaxPrice())
                .isEqualTo(targetWithTypicalProduct.getMaxPrice())
                .isEqualTo(new BigDecimal("400.00"));
        assertThat(targetWithTypicalProduct.getTypicalProduct()).isSameAs(materialRef);
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
