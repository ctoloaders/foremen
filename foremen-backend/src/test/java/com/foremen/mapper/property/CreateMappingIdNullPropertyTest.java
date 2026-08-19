package com.foremen.mapper.property;

import com.foremen.controller.model.mapper.TestEntityControllerMapper;
import com.foremen.controller.model.mapper.TestEntityControllerMapperImpl;
import com.foremen.mapper.fixture.TestCreateRequest;
import com.foremen.mapper.fixture.TestServiceExtendedModel;
import com.foremen.service.model.mapper.TestEntityServiceMapper;
import com.foremen.service.model.mapper.TestEntityServiceMapperImpl;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 1: Create Mapping Always Produces Null ID
 *
 * For any ServiceExtendedModel or CreateRequestModel instance (regardless of whether it
 * contains a non-null id field), calling toCreateDaoModel (on ServiceToDaoMapper) or
 * toServiceExtendedModel(CreateRequestModel) (on ControllerToServiceMapper) SHALL produce
 * a result where getId() returns null.
 *
 * Validates: Requirements 2.5, 2.10, 3.2, 8.6
 */
class CreateMappingIdNullPropertyTest {

    private final TestEntityServiceMapper serviceMapper = new TestEntityServiceMapperImpl();
    private final TestEntityControllerMapper controllerMapper = new TestEntityControllerMapperImpl();

    @Property(tries = 100)
    void toCreateDaoModel_alwaysProducesNullId(@ForAll("serviceExtendedModels") TestServiceExtendedModel source) {
        var result = serviceMapper.toCreateDaoModel(source);

        assertThat(result.getId()).isNull();
    }

    @Property(tries = 100)
    void toServiceExtendedModel_fromCreateRequest_alwaysProducesNullId(@ForAll("createRequests") TestCreateRequest source) {
        var result = controllerMapper.toServiceExtendedModel(source);

        assertThat(result.getId()).isNull();
    }

    @Provide
    Arbitrary<TestServiceExtendedModel> serviceExtendedModels() {
        Arbitrary<Long> ids = Arbitraries.oneOf(
                Arbitraries.longs().between(1L, 10000L).map(id -> id),
                Arbitraries.just(null)
        );
        Arbitrary<String> strings = Arbitraries.oneOf(
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(50),
                Arbitraries.just(null)
        );
        Arbitrary<Integer> quantities = Arbitraries.oneOf(
                Arbitraries.integers().between(0, 1000),
                Arbitraries.just(null)
        );

        return Combinators.combine(ids, strings, strings, strings, strings, strings, quantities)
                .as((id, nameRU, namePL, descRU, descPL, code, quantity) -> {
                    TestServiceExtendedModel model = new TestServiceExtendedModel();
                    model.setId(id);
                    model.setNameRU(nameRU);
                    model.setNamePL(namePL);
                    model.setDescriptionRU(descRU);
                    model.setDescriptionPL(descPL);
                    model.setCode(code);
                    model.setQuantity(quantity);
                    return model;
                });
    }

    @Provide
    Arbitrary<TestCreateRequest> createRequests() {
        Arbitrary<String> strings = Arbitraries.oneOf(
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(50),
                Arbitraries.just(null)
        );
        Arbitrary<Integer> quantities = Arbitraries.oneOf(
                Arbitraries.integers().between(0, 1000),
                Arbitraries.just(null)
        );

        return Combinators.combine(strings, strings, strings, strings, strings, quantities)
                .as((nameRU, namePL, descRU, descPL, code, quantity) -> {
                    TestCreateRequest request = new TestCreateRequest();
                    request.setNameRU(nameRU);
                    request.setNamePL(namePL);
                    request.setDescriptionRU(descRU);
                    request.setDescriptionPL(descPL);
                    request.setCode(code);
                    request.setQuantity(quantity);
                    return request;
                });
    }
}
