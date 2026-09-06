package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.mapper.qualifier.ToExtendedServiceModel;
import com.foremen.service.model.VatRateServiceExtendedModel;
import com.foremen.service.model.VatRateServiceModel;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.Set;

@Mapper(config = ForemenMapperConfig.class)
public interface VatRateServiceMapper
        extends ServiceToDaoMapper<VatRateEntity, VatRateServiceModel, VatRateServiceExtendedModel> {

    @Override
    default Set<String> getI18nSupportedProperties() {
        return Set.of("name");
    }

    // The entity's boolean getter isDefault() exposes the MapStruct property name "default",
    // whereas the record component is "isDefault"; map explicitly so the flag survives.
    @Override
    @ToExtendedServiceModel
    @Mapping(target = "isDefault", source = "default")
    VatRateServiceExtendedModel toServiceExtendedModel(VatRateEntity source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "default", source = "isDefault")
    VatRateEntity toCreateDaoModel(VatRateServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "default", source = "isDefault")
    void updateFields(VatRateServiceExtendedModel source, @MappingTarget VatRateEntity target);
}
