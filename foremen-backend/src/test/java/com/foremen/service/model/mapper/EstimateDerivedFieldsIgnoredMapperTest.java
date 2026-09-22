package com.foremen.service.model.mapper;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.service.model.EstimateLineServiceExtendedModel;
import com.foremen.service.model.EstimateServiceExtendedModel;

import jakarta.persistence.EntityManager;

/**
 * Unit tests asserting that the MapStruct {@code @Mapping(target = "...", ignore = true)}
 * annotations on the FOR-05-03 estimate write mappers ({@code EstimateServiceMapper},
 * {@code EstimateLineServiceMapper}) correctly drop the derivation-only columns when mapping an
 * inbound write model onto its entity.
 *
 * <p>Each test feeds a write model with the derived/snapshot field(s) populated with a non-null,
 * non-default sentinel value and asserts the mapped entity does not carry that value through — i.e.
 * setting the field on the inbound DTO has no effect (task 5.3).
 *
 * <p>{@code EstimateLineRoomQtyServiceMapper} is intentionally not covered here: its
 * {@code quantity} is an input value (not derived, R3.3) and is mapped straight through by design.
 *
 * Validates: Requirements 8.4, 2.6, 2.7
 */
class EstimateDerivedFieldsIgnoredMapperTest {

    // Mappers are abstract classes with an injected EntityManager (FK -> managed-reference
    // resolution); the FK reference plumbing is irrelevant here since every model under test uses
    // null FKs, so a mock EntityManager is never actually invoked.
    private final EntityManager entityManager = mock(EntityManager.class);

    @Test
    @DisplayName("EstimateServiceMapper: inbound total* are ignored on create and update")
    void estimateMapper_ignoresInboundTotals() throws Exception {
        EstimateServiceMapper mapper = new EstimateServiceMapperImpl();
        injectEntityManager(mapper, EstimateServiceMapper.class);

        EstimateServiceExtendedModel source = new EstimateServiceExtendedModel();
        source.setStatus(EstimateStatus.DRAFT);
        source.setTotalNet(new BigDecimal("999.99"));
        source.setTotalVat(new BigDecimal("999.99"));
        source.setTotalGross(new BigDecimal("999.99"));

        EstimateEntity created = mapper.toCreateDaoModel(source);

        assertThat(created.getTotalNet()).isEqualTo(BigDecimal.ZERO);
        assertThat(created.getTotalVat()).isEqualTo(BigDecimal.ZERO);
        assertThat(created.getTotalGross()).isEqualTo(BigDecimal.ZERO);

        // updateFields must not overwrite an already-recomputed target with the inbound sentinel.
        EstimateEntity target = new EstimateEntity();
        target.setTotalNet(new BigDecimal("123.45"));
        target.setTotalVat(new BigDecimal("12.35"));
        target.setTotalGross(new BigDecimal("135.80"));

        mapper.updateFields(source, target);

        assertThat(target.getTotalNet()).isEqualTo(new BigDecimal("123.45"));
        assertThat(target.getTotalVat()).isEqualTo(new BigDecimal("12.35"));
        assertThat(target.getTotalGross()).isEqualTo(new BigDecimal("135.80"));
    }

    @Test
    @DisplayName("EstimateLineServiceMapper: inbound quantity/valueNet are ignored on create and update")
    void estimateLineMapper_ignoresInboundQuantityAndValueNet() throws Exception {
        EstimateLineServiceMapper mapper = new EstimateLineServiceMapperImpl();
        injectEntityManager(mapper, EstimateLineServiceMapper.class);

        EstimateLineServiceExtendedModel source = new EstimateLineServiceExtendedModel();
        source.setUnitPrice(new BigDecimal("10.00"));
        source.setQuantity(new BigDecimal("777.7777"));
        source.setValueNet(new BigDecimal("777.77"));

        EstimateLineEntity created = mapper.toCreateDaoModel(source);

        assertThat(created.getQuantity()).isEqualTo(BigDecimal.ZERO);
        assertThat(created.getValueNet()).isEqualTo(BigDecimal.ZERO);
        // Non-derived field maps through normally.
        assertThat(created.getUnitPrice()).isEqualTo(new BigDecimal("10.00"));

        EstimateLineEntity target = new EstimateLineEntity();
        target.setQuantity(new BigDecimal("3.0000"));
        target.setValueNet(new BigDecimal("30.00"));

        mapper.updateFields(source, target);

        assertThat(target.getQuantity()).isEqualTo(new BigDecimal("3.0000"));
        assertThat(target.getValueNet()).isEqualTo(new BigDecimal("30.00"));
    }

    /**
     * Injects the mocked {@link EntityManager} into the mapper's protected {@code entityManager}
     * field via reflection, mirroring {@code AuditServiceMapperTest}'s collaborator-injection
     * pattern for MapStruct abstract-class mappers (no Spring context is started for this unit test).
     */
    private void injectEntityManager(Object mapper, Class<?> declaringClass) throws Exception {
        Field field = declaringClass.getDeclaredField("entityManager");
        field.setAccessible(true);
        field.set(mapper, entityManager);
    }
}
