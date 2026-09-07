package com.foremen.service.property;

// Feature: FOR-04-13-project, Property 4: Invalid base fields are rejected and nothing persists

import com.foremen.controller.model.CreateProjectRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test for the bean-validation constraints declared on {@link CreateProjectRequest}'s base
 * fields (FOR-04-13, Requirement 3.5).
 *
 * <p><b>Property 4: Invalid base fields are rejected and nothing persists.</b> The base-field
 * constraints on the request record are {@code name} {@code @NotBlank}{@code @Size(max = 255)} and
 * {@code area} {@code @DecimalMin("0.01")}{@code @DecimalMax("999999999.99")}. For any generated
 * request with an invalid base field (blank or too-long {@code name}, or {@code area} below
 * {@code 0.01} or above {@code 999999999.99}), a JSR-380 {@link Validator} MUST report at least one
 * constraint violation on that field &mdash; so bean validation rejects the request before the
 * service ever persists anything. Conversely, for any generated request whose base fields are all
 * valid, the validator MUST report zero base-field violations. <b>Validates: Requirements 3.5</b>
 *
 * <p>The test drives a real {@code jakarta.validation.Validator}
 * (from {@link Validation#buildDefaultValidatorFactory()}), the same engine Spring MVC applies to a
 * {@code @Valid @RequestBody CreateProjectRequest} on {@code POST /api/projects}. It asserts only on
 * the two base fields under test ({@code name}, {@code area}); {@code members}/{@code client} are
 * left null so nested validation contributes no violations.
 */
class ProjectFieldValidationPropertyTest {

    /** Constraint boundaries declared on the record, mirrored here for generator construction. */
    private static final int NAME_MAX = 255;
    private static final BigDecimal AREA_MIN = new BigDecimal("0.01");
    private static final BigDecimal AREA_MAX = new BigDecimal("999999999.99");

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    // ---- Invalid base fields produce at least one violation on the offending field ----

    @Property(tries = 100)
    void invalidBaseFieldsAreRejected(@ForAll("invalidRequests") InvalidCase testCase) {
        Set<ConstraintViolation<CreateProjectRequest>> violations = VALIDATOR.validate(testCase.request());

        // At least one violation exists, and the offending base field is among the violated paths.
        assertThat(violations)
                .as("expected a constraint violation for invalid field '%s'", testCase.offendingField())
                .isNotEmpty();
        assertThat(violations)
                .as("expected a violation on field '%s' for request %s", testCase.offendingField(), testCase.request())
                .anyMatch(v -> v.getPropertyPath().toString().equals(testCase.offendingField()));
    }

    // ---- Valid base fields produce zero base-field violations ----

    @Property(tries = 100)
    void validBaseFieldsAreAccepted(@ForAll("validRequests") CreateProjectRequest request) {
        Set<ConstraintViolation<CreateProjectRequest>> violations = VALIDATOR.validate(request);

        // No violations on either base field under test.
        assertThat(violations)
                .as("expected no base-field violations for a valid request %s", request)
                .noneMatch(v -> {
                    String path = v.getPropertyPath().toString();
                    return path.equals("name") || path.equals("area");
                });
    }

    // ---- Generators ----

    /**
     * An invalid request together with the base-field path expected to be violated. Exactly one base
     * field is made invalid per case (the other base field is valid) so the expected violated path
     * is unambiguous.
     */
    record InvalidCase(CreateProjectRequest request, String offendingField) {}

    @Provide
    Arbitrary<InvalidCase> invalidRequests() {
        Arbitrary<InvalidCase> invalidName = Combinators.combine(invalidNames(), validAreasIncludingNull())
                .as((name, area) -> new InvalidCase(request(name, area), "name"));
        Arbitrary<InvalidCase> invalidArea = Combinators.combine(validNames(), invalidAreas())
                .as((name, area) -> new InvalidCase(request(name, area), "area"));
        return Arbitraries.oneOf(invalidName, invalidArea);
    }

    @Provide
    Arbitrary<CreateProjectRequest> validRequests() {
        return Combinators.combine(validNames(), validAreasIncludingNull())
                .as(this::request);
    }

    private CreateProjectRequest request(String name, BigDecimal area) {
        return new CreateProjectRequest(
                name,        // name (under test)
                null,        // address
                null,        // googlePlaceId
                null,        // formattedAddress
                null,        // latitude
                null,        // longitude
                area,        // area (under test)
                null,        // startDate
                null,        // endDate
                null,        // status
                null,        // members
                null         // client
        );
    }

    /** Valid names: non-blank after trim, length within 1..255. */
    @Provide
    Arbitrary<String> validNames() {
        return Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")
                .ofMinLength(1)
                .ofMaxLength(NAME_MAX)
                .filter(s -> !s.isBlank());
    }

    /** Invalid names: either blank ({@code @NotBlank}) or longer than 255 ({@code @Size}). */
    @Provide
    Arbitrary<String> invalidNames() {
        Arbitrary<String> blank = Arbitraries.of("", " ", "   ", "\t", "\n", "  \t \n ");
        Arbitrary<String> tooLong = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz")
                .ofMinLength(NAME_MAX + 1)
                .ofMaxLength(NAME_MAX + 100);
        return Arbitraries.oneOf(blank, tooLong);
    }

    /** Valid areas within 0.01..999999999.99, plus null (area is optional). */
    @Provide
    Arbitrary<BigDecimal> validAreasIncludingNull() {
        Arbitrary<BigDecimal> inRange = Arbitraries.bigDecimals()
                .between(AREA_MIN, AREA_MAX)
                .ofScale(2)
                .map(d -> d.setScale(2, RoundingMode.HALF_UP))
                // Guard against rounding pushing a boundary value out of range.
                .filter(d -> d.compareTo(AREA_MIN) >= 0 && d.compareTo(AREA_MAX) <= 0);
        return Arbitraries.oneOf(inRange, Arbitraries.just(null));
    }

    /** Invalid areas: strictly below 0.01 or strictly above 999999999.99. */
    @Provide
    Arbitrary<BigDecimal> invalidAreas() {
        Arbitrary<BigDecimal> tooSmall = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.00"), new BigDecimal("0.009"))
                .ofScale(3)
                .filter(d -> d.compareTo(AREA_MIN) < 0);
        Arbitrary<BigDecimal> tooLarge = Arbitraries.bigDecimals()
                .between(new BigDecimal("1000000000.00"), new BigDecimal("9999999999.99"))
                .ofScale(2)
                .filter(d -> d.compareTo(AREA_MAX) > 0);
        return Arbitraries.oneOf(tooSmall, tooLarge);
    }
}
