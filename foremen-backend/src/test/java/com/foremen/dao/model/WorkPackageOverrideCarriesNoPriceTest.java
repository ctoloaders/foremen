package com.foremen.dao.model;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.foremen.dao.model.formula.FormulaAst;

/**
 * Feature: FOR-05-04-estimate-packages-changes, Property 8: Override carries no price.
 *
 * <p>Structural + behavioral unit test asserting {@link WorkPackageOverrideEntity} carries no
 * price field or accessor. This is not a property-based test: the entity's declared shape
 * (workItem, offerPackage, member, overrideSourceText, overrideParsedAst) has no varying input
 * space to explore beyond "does this class expose a price" — a plain reflective/structural
 * assertion is the appropriate test shape (mirrors {@code OtpTokenEntityTest}'s plain-unit-test
 * style for entity field-shape assertions).
 *
 * <p>Validates: Requirements 4.5
 */
class WorkPackageOverrideCarriesNoPriceTest {

    /** Field/method name fragments that would indicate a price-carrying member. */
    private static final List<String> PRICE_NAME_FRAGMENTS =
            List.of("price", "netprice", "unitprice", "amount");

    @Test
    void declaredFieldsContainNoPriceLikeNameOrMonetaryType() {
        Field[] declaredFields = WorkPackageOverrideEntity.class.getDeclaredFields();

        assertThat(declaredFields).isNotEmpty();

        for (Field field : declaredFields) {
            String lowerName = field.getName().toLowerCase(Locale.ROOT);
            boolean namedLikePrice = PRICE_NAME_FRAGMENTS.stream().anyMatch(lowerName::contains);

            assertThat(namedLikePrice)
                    .as("field '%s' on WorkPackageOverrideEntity looks price-like", field.getName())
                    .isFalse();

            // overrideSourceText/overrideParsedAst are formula fields, not price — a BigDecimal
            // field would be the money-typed shape a price row (e.g. WorkPriceEntity.netPrice) uses.
            assertThat(field.getType())
                    .as("field '%s' should not be BigDecimal (money-typed)", field.getName())
                    .isNotEqualTo(BigDecimal.class);
        }

        // Sanity: confirm we actually inspected the expected non-price fields, so the assertions
        // above aren't vacuously true against an empty/wrong field set.
        assertThat(declaredFields)
                .extracting(Field::getName)
                .containsExactlyInAnyOrder(
                        "workItem", "offerPackage", "member", "overrideSourceText", "overrideParsedAst");
    }

    @Test
    void noGetterMethodReturnsAPriceLikeValue() {
        Method[] methods = WorkPackageOverrideEntity.class.getMethods();

        List<String> priceLikeGetterNames =
                List.of("getprice", "getnetprice", "getunitprice", "getamount");

        for (Method method : methods) {
            String lowerName = method.getName().toLowerCase(Locale.ROOT);
            assertThat(priceLikeGetterNames.contains(lowerName))
                    .as("method '%s' looks like a price getter", method.getName())
                    .isFalse();
        }
    }

    @Test
    void behavioralSanityCheckConstructedInstanceHasNoPriceValue() {
        WorkPackageOverrideEntity override = new WorkPackageOverrideEntity();
        override.setMember(true);
        override.setOverrideSourceText("=X28*1+X42");
        override.setOverrideParsedAst(new FormulaAst.Const(BigDecimal.ONE));

        assertThat(override.getMember()).isTrue();
        assertThat(override.getOverrideSourceText()).isEqualTo("=X28*1+X42");
        assertThat(override.getOverrideParsedAst()).isEqualTo(new FormulaAst.Const(BigDecimal.ONE));

        // No getPrice/getNetPrice/getUnitPrice/getAmount exists to even call — confirmed above by
        // reflection; this instance simply has nothing price-related to assert zero/null against.
    }
}
