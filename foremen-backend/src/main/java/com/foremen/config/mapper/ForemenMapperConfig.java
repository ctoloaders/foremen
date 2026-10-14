package com.foremen.config.mapper;

import org.mapstruct.Builder;
import org.mapstruct.MapperConfig;
import org.mapstruct.MappingConstants;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;

/**
 * Shared MapStruct configuration for every Foremen mapper (referenced via
 * {@code @Mapper(config = ForemenMapperConfig.class)}).
 *
 * <p><b>{@code unmappedTargetPolicy = IGNORE}.</b> Across the service/dao and controller/service
 * mappers, write-side mappings intentionally leave a number of target properties unmapped — they
 * are populated by a different mechanism than field-by-field copying, by design:
 * <ul>
 *   <li>the {@code BaseEntity} audit columns {@code createdDate / createdBy / updatedDate /
 *       updatedBy} are set by JPA auditing, never copied from a model;</li>
 *   <li>the i18n {@code name} on a service model is resolved from the entity's {@code nameRU /
 *       namePL} columns by {@code I18nPropertiesMapper}, not mapped directly;</li>
 *   <li>derived / recomputed fields (e.g. estimate {@code totalNet/totalVat/totalGross}, estimate
 *       line {@code quantity/valueNet}, room geometry) are written exclusively by their owning
 *       recompute service, and read-side display projections (e.g. {@code workItemName},
 *       {@code projectName}) are resolved separately.</li>
 * </ul>
 * With the default {@code WARN} policy these deliberate omissions produced ~100 permanent
 * compile warnings — pure noise that trains readers to ignore MapStruct warnings and hides any
 * genuinely-forgotten mapping. {@code IGNORE} silences the intentional omissions. A real
 * mapping gap now surfaces at runtime (a {@code null} target field) and through the mapper tests,
 * rather than being lost in the noise; the explicit {@code @Mapping(target = ..., ignore = true)}
 * annotations already present on individual mappers continue to document the per-mapper intent.
 */
@MapperConfig(
        componentModel = MappingConstants.ComponentModel.SPRING,
        builder = @Builder(disableBuilder = true),
        nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE,
        unmappedTargetPolicy = ReportingPolicy.IGNORE
)
public interface ForemenMapperConfig {
}
