package com.foremen.controller.model.mapper;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.foremen.controller.model.ConstructionMaterialCreateRequest;
import com.foremen.controller.model.ConstructionMaterialCreateResponse;
import com.foremen.controller.model.ConstructionMaterialDtoExtendedModel;
import com.foremen.controller.model.ConstructionMaterialDtoModel;
import com.foremen.controller.model.ConstructionMaterialUpdateRequest;
import com.foremen.controller.model.ConstructionMaterialUpdateResponse;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.model.ConstructionMaterialServiceExtendedModel;
import com.foremen.service.model.ConstructionMaterialServiceModel;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Unit test locking in the construction-material package-dimension collapse on the DTO/request/mapper
 * layer (FOR-05-04-UI-estimate-packages-changes, task 3.2).
 *
 * <p>Task 3.1 removed the {@code @ManyToMany Set<OfferPackageEntity> packages} from
 * {@code ConstructionMaterialEntity} and dropped the {@code packages} / {@code offerPackageIds}
 * carriers from every construction-material DTO and request; the {@code @NotEmpty Set<Long>
 * offerPackageIds} on the create/update requests is gone. This test guards that removal so a
 * regression re-introducing a package binding on the material write/read path fails fast, without
 * needing the ~20-minute integration suite.
 *
 * <p>It asserts the collapse in three complementary ways:
 * <ol>
 *   <li><b>Shape.</b> None of the construction-material records (the two requests, the list/extended
 *       read DTOs, and the create/update responses) exposes a {@code packages} or
 *       {@code offerPackageIds} record component; neither write/read service model exposes such a
 *       declared field (Requirements 5.1, 5.2).</li>
 *   <li><b>Bean validation.</b> A create/update request built with every genuine required field but
 *       WITHOUT any package binding passes bean validation with zero violations — proving no
 *       {@code @NotEmpty offerPackageIds} constraint remains to reject it (Requirement 5.2).</li>
 *   <li><b>Mapper behavior.</b> The MapStruct controller mapper round-trips
 *       request→service-model→DTO carrying no package data anywhere, and the produced DTOs likewise
 *       expose no package component (Requirements 5.1, 5.2).</li>
 * </ol>
 *
 * <p>Validates: Requirements 5.1, 5.2
 */
class ConstructionMaterialPackageRemovalMapperTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    /** Name fragments that would indicate a lingering package binding on the material side. */
    private static final Set<String> PACKAGE_NAME_FRAGMENTS = Set.of("package", "offerpackage", "offerpackageids");

    private final ImageStorage imageStorage = mock(ImageStorage.class);
    private ConstructionMaterialControllerMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        // The mapper is a MapStruct abstract class holding a protected ImageStorage seam; inject a
        // mock via reflection (mirrors AuditServiceMapperTest's collaborator-injection style). The
        // CDN resolver just echoes the object key so imageUrl round-trips predictably.
        lenient().when(imageStorage.toCdnUrl(anyString())).thenAnswer(inv -> inv.getArgument(0));
        mapper = new ConstructionMaterialControllerMapperImpl();
        Field field = ConstructionMaterialControllerMapper.class.getDeclaredField("imageStorage");
        field.setAccessible(true);
        field.set(mapper, imageStorage);
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Shape: no record carries a packages / offerPackageIds component.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("create request has no packages/offerPackageIds component")
    void createRequestHasNoPackageComponent() {
        assertNoPackageComponent(ConstructionMaterialCreateRequest.class);
    }

    @Test
    @DisplayName("update request has no packages/offerPackageIds component")
    void updateRequestHasNoPackageComponent() {
        assertNoPackageComponent(ConstructionMaterialUpdateRequest.class);
    }

    @Test
    @DisplayName("list DTO has no packages/offerPackageIds component")
    void listDtoHasNoPackageComponent() {
        assertNoPackageComponent(ConstructionMaterialDtoModel.class);
    }

    @Test
    @DisplayName("extended DTO has no packages/offerPackageIds component")
    void extendedDtoHasNoPackageComponent() {
        assertNoPackageComponent(ConstructionMaterialDtoExtendedModel.class);
    }

    @Test
    @DisplayName("create/update responses have no packages/offerPackageIds component")
    void responsesHaveNoPackageComponent() {
        assertNoPackageComponent(ConstructionMaterialCreateResponse.class);
        assertNoPackageComponent(ConstructionMaterialUpdateResponse.class);
    }

    @Test
    @DisplayName("read/write service models expose no package field")
    void serviceModelsHaveNoPackageField() {
        assertNoPackageField(ConstructionMaterialServiceModel.class);
        assertNoPackageField(ConstructionMaterialServiceExtendedModel.class);
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Bean validation: a request without any package binding is valid (no @NotEmpty offerPackageIds).
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("create request without any package binding passes bean validation")
    void createRequestWithoutPackagesIsBeanValid() {
        Set<ConstraintViolation<ConstructionMaterialCreateRequest>> violations =
                VALIDATOR.validate(validCreateRequest());
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("update request without any package binding passes bean validation")
    void updateRequestWithoutPackagesIsBeanValid() {
        Set<ConstraintViolation<ConstructionMaterialUpdateRequest>> violations =
                VALIDATOR.validate(validUpdateRequest());
        assertThat(violations).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Mapper behavior: request -> service model -> DTO carries no package data.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("mapper maps create request to a write model without any package binding")
    void mapperMapsCreateRequestWithoutPackages() {
        ConstructionMaterialServiceExtendedModel model =
                mapper.toServiceExtendedModel(validCreateRequest());

        assertThat(model.getNameRU()).isEqualTo("Краска");
        assertThat(model.getNamePL()).isEqualTo("Farba");
        assertThat(model.getTypeId()).isEqualTo(4L);
        assertThat(model.getUnitId()).isEqualTo(3L);
        assertThat(model.getCurrencyId()).isEqualTo(2L);
        assertThat(model.getRetailNet()).isEqualByComparingTo("150.00");
        assertThat(model.isActive()).isTrue();
    }

    @Test
    @DisplayName("mapper maps update request to a write model without any package binding")
    void mapperMapsUpdateRequestWithoutPackages() {
        ConstructionMaterialServiceExtendedModel model =
                mapper.toUpdateServiceExtendedModel(validUpdateRequest());

        assertThat(model.getNameRU()).isEqualTo("Краска");
        assertThat(model.getTypeId()).isEqualTo(4L);
        // active omitted (null) on the request defaults to true on the write path.
        assertThat(model.isActive()).isTrue();
    }

    @Test
    @DisplayName("mapper maps the read service model to a list DTO with type-keyed prices and no packages")
    void mapperMapsReadModelToDtoWithoutPackages() {
        ConstructionMaterialServiceModel source = new ConstructionMaterialServiceModel();
        source.setId(7L);
        source.setName("Краска");
        source.setRetailNet(new BigDecimal("150.00"));
        source.setActive(true);

        ConstructionMaterialDtoModel dto = mapper.toDto(source);

        assertThat(dto.id()).isEqualTo(7L);
        assertThat(dto.name()).isEqualTo("Краска");
        assertThat(dto.retailNet()).isEqualByComparingTo("150.00");
        assertThat(dto.active()).isTrue();
        // priceRanges (keyed by type only) is the sole price-range carrier; the record simply has no
        // package component to populate -- guarded structurally above.
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers.
    // ---------------------------------------------------------------------------------------------

    private static void assertNoPackageComponent(Class<?> recordType) {
        RecordComponent[] components = recordType.getRecordComponents();
        assertThat(components).as("%s must be a record", recordType.getSimpleName()).isNotNull();

        Set<String> names = Arrays.stream(components)
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());

        // Sanity: confirm we actually inspected a populated component set (not vacuously true).
        assertThat(names).as("%s should expose its non-package fields", recordType.getSimpleName())
                .isNotEmpty();

        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            boolean packageLike = PACKAGE_NAME_FRAGMENTS.stream().anyMatch(lower::contains);
            assertThat(packageLike)
                    .as("component '%s' on %s looks package-related", name, recordType.getSimpleName())
                    .isFalse();
        }
    }

    private static void assertNoPackageField(Class<?> type) {
        Field[] fields = type.getDeclaredFields();
        assertThat(fields).as("%s should expose its non-package fields", type.getSimpleName()).isNotEmpty();

        for (Field field : fields) {
            String lower = field.getName().toLowerCase(Locale.ROOT);
            boolean packageLike = PACKAGE_NAME_FRAGMENTS.stream().anyMatch(lower::contains);
            assertThat(packageLike)
                    .as("field '%s' on %s looks package-related", field.getName(), type.getSimpleName())
                    .isFalse();
        }
    }

    private static ConstructionMaterialCreateRequest validCreateRequest() {
        return new ConstructionMaterialCreateRequest(
                "Краска",
                "Farba",
                4L,      // typeId
                null,    // producerId (optional)
                null,    // sellerId (optional)
                3L,      // unitId
                2L,      // currencyId
                null,    // purchasePrice
                null,    // retailGross
                new BigDecimal("150.00"),
                null,    // website
                null,    // image
                null);   // active -> defaults to true
    }

    private static ConstructionMaterialUpdateRequest validUpdateRequest() {
        return new ConstructionMaterialUpdateRequest(
                "Краска",
                "Farba",
                4L,
                null,
                null,
                3L,
                2L,
                null,
                null,
                new BigDecimal("150.00"),
                null,
                null,
                null);
    }
}
