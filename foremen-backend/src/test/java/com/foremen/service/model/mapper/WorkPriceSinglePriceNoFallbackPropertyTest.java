package com.foremen.service.model.mapper;

import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.dao.model.WorkPriceEntity;
import com.foremen.service.model.WorkPriceServiceExtendedModel;
import com.foremen.service.model.WorkPriceServiceModel;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based (and focused unit) tests for {@link WorkPriceServiceMapper} (FOR-05-04, Property
 * 2 — "Single price read has no fallback").
 *
 * <p>{@code WorkPriceEntity} is now the collapsed {@code (workItem, currency, netPrice)} shape
 * (task 11.1); the read path ({@code WorkPriceService}/{@code WorkPriceServiceMapper}, task 11.2)
 * maps {@code netPrice} straight through with no {@code EffectivePriceResolver}/MAX-fallback step
 * (that class is no longer on the read path). There is no per-package collection left to "fall
 * back" over, so the property under test reduces to: reading a {@code WorkPriceEntity} always
 * returns exactly that row's own stored {@code netPrice} — never a computed/derived/maximum value
 * over some other collection.
 *
 * <p>{@code WorkPriceServiceMapper} is an abstract MapStruct class holding an injected {@code
 * EntityManager} used only on the write side ({@code toCreateDaoModel}/{@code updateFields}); the
 * read methods under test ({@code toServiceModel}/{@code toServiceExtendedModel}) never touch it,
 * so the generated implementation is instantiated directly (mirroring {@code
 * AuditServiceMapperTest}/{@code EstimateDerivedFieldsIgnoredMapperTest}'s pattern for abstract
 * MapStruct mappers) — no Spring context, no database.
 *
 * <p>Feature: FOR-05-04-estimate-packages-changes, Property 2
 *
 * <p><b>Validates: Requirements 1.1, 1.3</b>
 */
@Tag("Feature: FOR-05-04-estimate-packages-changes, Property 2")
class WorkPriceSinglePriceNoFallbackPropertyTest {

    private final WorkPriceServiceMapper mapper = new WorkPriceServiceMapperImpl();

    // ------------------------------------------------------------------------------------------
    // Property 2a: for any WorkPriceEntity, the mapped WorkPriceServiceModel's netPrice equals
    // exactly the entity's own stored netPrice, regardless of currency.
    // Validates: Requirements 1.1, 1.3
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 2")
    void toServiceModel_netPriceIsExactlyTheEntitysOwnStoredValue(
            @ForAll("workPriceEntities") WorkPriceEntity entity) {
        WorkPriceServiceModel result = mapper.toServiceModel(entity);

        assertThat(result.getNetPrice()).isEqualByComparingTo(entity.getNetPrice());
        assertThat(result.getCurrencyId()).isEqualTo(entity.getCurrency().getId());
        assertThat(result.getCurrencyCode()).isEqualTo(entity.getCurrency().getCode());
        assertThat(result.getWorkItemId()).isEqualTo(entity.getWorkItem().getId());
    }

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 2")
    void toServiceExtendedModel_netPriceIsExactlyTheEntitysOwnStoredValue(
            @ForAll("workPriceEntities") WorkPriceEntity entity) {
        WorkPriceServiceExtendedModel result = mapper.toServiceExtendedModel(entity);

        assertThat(result.netPrice()).isEqualByComparingTo(entity.getNetPrice());
        assertThat(result.currencyId()).isEqualTo(entity.getCurrency().getId());
        assertThat(result.workItemId()).isEqualTo(entity.getWorkItem().getId());
    }

    // ------------------------------------------------------------------------------------------
    // Property 2b: the mapped netPrice never equals a value derived from some *other* entity's
    // price (no cross-entity substitution / no MAX-fallback across a collection). We map a pair
    // of independently generated entities and assert each result tracks only its own source.
    // Validates: Requirement 1.3
    // ------------------------------------------------------------------------------------------

    @Property(tries = 100)
    @Tag("Feature: FOR-05-04-estimate-packages-changes, Property 2")
    void mappingIsIndependentPerEntity_noCrossSubstitution(
            @ForAll("workPriceEntities") WorkPriceEntity first,
            @ForAll("workPriceEntities") WorkPriceEntity second) {
        WorkPriceServiceModel firstResult = mapper.toServiceModel(first);
        WorkPriceServiceModel secondResult = mapper.toServiceModel(second);

        assertThat(firstResult.getNetPrice()).isEqualByComparingTo(first.getNetPrice());
        assertThat(secondResult.getNetPrice()).isEqualByComparingTo(second.getNetPrice());

        // Mapping `second` after `first` must not have mutated/affected the already-produced
        // `firstResult` (no shared mutable "resolved max so far" state carried across calls).
        assertThat(firstResult.getNetPrice()).isEqualByComparingTo(first.getNetPrice());
    }

    // ------------------------------------------------------------------------------------------
    // Edge case: netPrice = BigDecimal.ZERO is read back as zero, not treated as "unpriced"/null.
    // Validates: Requirement 1.1
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("netPrice = ZERO is read back as zero, not null / not substituted")
    void zeroNetPrice_isReadBackAsZero_notNullOrSubstituted() {
        WorkPriceEntity entity = workPriceEntity(1L, 10L, "PLN", BigDecimal.ZERO);

        WorkPriceServiceModel model = mapper.toServiceModel(entity);
        WorkPriceServiceExtendedModel extended = mapper.toServiceExtendedModel(entity);

        assertThat(model.getNetPrice()).isNotNull();
        assertThat(model.getNetPrice()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(extended.netPrice()).isNotNull();
        assertThat(extended.netPrice()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------------------------------
    // Structural check: the mapper has no collaborator field referencing a resolver/fallback
    // concept (e.g. an injected EffectivePriceResolver). The only declared field is the
    // EntityManager used for FK -> managed-reference resolution on the write side.
    // Validates: Requirement 1.3 (no read-path fallback mechanism wired in)
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("mapper declares no resolver/fallback collaborator field")
    void mapperHasNoResolverOrFallbackCollaborator() {
        Field[] fields = WorkPriceServiceMapper.class.getDeclaredFields();

        assertThat(fields)
                .extracting(Field::getName)
                .noneMatch(name -> name.toLowerCase().contains("resolver")
                        || name.toLowerCase().contains("fallback"));
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    @Provide
    Arbitrary<WorkPriceEntity> workPriceEntities() {
        Arbitrary<Long> ids = Arbitraries.longs().between(1, 1_000_000);
        Arbitrary<Long> workItemIds = Arbitraries.longs().between(1, 1_000_000);
        Arbitrary<String> currencyCodes = Arbitraries.of("PLN", "USD", "EUR", "RUB");
        Arbitrary<BigDecimal> netPrices = Arbitraries.longs().between(0, 99_999_999)
                .map(cents -> BigDecimal.valueOf(cents, 2));

        return Combinators.combine(ids, workItemIds, currencyCodes, netPrices)
                .as(this::workPriceEntity);
    }

    private WorkPriceEntity workPriceEntity(Long id, Long workItemId, String currencyCode, BigDecimal netPrice) {
        WorkItemEntity workItem = new WorkItemEntity();
        workItem.setId(workItemId);

        CurrencyEntity currency = new CurrencyEntity();
        currency.setId(workItemId + 500_000L);
        currency.setCode(currencyCode);
        currency.setSymbol(currencyCode);
        currency.setNameRU(currencyCode);
        currency.setNamePL(currencyCode);

        WorkPriceEntity entity = new WorkPriceEntity();
        entity.setId(id);
        entity.setWorkItem(workItem);
        entity.setCurrency(currency);
        entity.setNetPrice(netPrice);
        return entity;
    }
}
