package com.foremen.config.mapper;

import org.mapstruct.Builder;
import org.mapstruct.MapperConfig;
import org.mapstruct.MappingConstants;
import org.mapstruct.NullValuePropertyMappingStrategy;

@MapperConfig(
        componentModel = MappingConstants.ComponentModel.SPRING,
        builder = @Builder(disableBuilder = true),
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE
)
public interface ForemenMapperConfig {
}
