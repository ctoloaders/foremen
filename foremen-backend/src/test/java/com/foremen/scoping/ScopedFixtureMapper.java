package com.foremen.scoping;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.mapper.ServiceToDaoMapper;
import org.mapstruct.Mapper;

import java.util.Set;

/**
 * Test-only MapStruct mapper for {@link ScopedFixtureEntity}, mirroring {@code SampleEntityMapper}.
 * The fixture carries no i18n properties, so {@code getI18nSupportedProperties()} returns the empty
 * set.
 */
@Mapper(config = ForemenMapperConfig.class)
public interface ScopedFixtureMapper
        extends ServiceToDaoMapper<ScopedFixtureEntity, ScopedFixtureServiceModel, ScopedFixtureServiceExtendedModel> {

    @Override
    default Set<String> getI18nSupportedProperties() {
        return Set.of();
    }
}
