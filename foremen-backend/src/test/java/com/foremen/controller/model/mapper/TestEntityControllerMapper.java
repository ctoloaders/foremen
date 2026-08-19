package com.foremen.controller.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.mapper.ControllerToServiceMapper;
import com.foremen.mapper.fixture.TestCreateRequest;
import com.foremen.mapper.fixture.TestCreateResponse;
import com.foremen.mapper.fixture.TestDtoExtendedModel;
import com.foremen.mapper.fixture.TestDtoModel;
import com.foremen.mapper.fixture.TestServiceExtendedModel;
import com.foremen.mapper.fixture.TestServiceModel;
import com.foremen.mapper.fixture.TestUpdateRequest;
import com.foremen.mapper.fixture.TestUpdateResponse;
import org.mapstruct.Mapper;

@Mapper(config = ForemenMapperConfig.class)
public interface TestEntityControllerMapper
        extends ControllerToServiceMapper<
                TestServiceModel,
                TestServiceExtendedModel,
                TestDtoModel,
                TestDtoExtendedModel,
                TestCreateRequest,
                TestCreateResponse,
                TestUpdateRequest,
                TestUpdateResponse> {
}
