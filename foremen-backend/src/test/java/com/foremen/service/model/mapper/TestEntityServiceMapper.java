package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.mapper.fixture.TestDaoModel;
import com.foremen.mapper.fixture.TestServiceExtendedModel;
import com.foremen.mapper.fixture.TestServiceModel;
import org.mapstruct.Mapper;

import java.util.Set;

@Mapper(config = ForemenMapperConfig.class)
public interface TestEntityServiceMapper
        extends ServiceToDaoMapper<TestDaoModel, TestServiceModel, TestServiceExtendedModel> {

    @Override
    default Set<String> getI18nSupportedProperties() {
        return Set.of("name", "description");
    }
}
