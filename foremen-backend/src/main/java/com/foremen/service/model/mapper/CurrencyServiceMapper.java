package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.CurrencyServiceExtendedModel;
import com.foremen.service.model.CurrencyServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.Set;

@Mapper(config = ForemenMapperConfig.class)
public interface CurrencyServiceMapper
        extends ServiceToDaoMapper<CurrencyEntity, CurrencyServiceModel, CurrencyServiceExtendedModel> {

    @Override
    default Set<String> getI18nSupportedProperties() {
        return Set.of("name");
    }

    @Override
    @Mapping(target = "id", ignore = true)
    CurrencyEntity toCreateDaoModel(CurrencyServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    void updateFields(CurrencyServiceExtendedModel source, @MappingTarget CurrencyEntity target);
}
