package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.RoleDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.UserServiceExtendedModel;
import com.foremen.service.model.UserServiceModel;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.Set;

/**
 * Abstract mapper (rather than a plain interface) so it can resolve the {@code roleId}
 * on the incoming service model into a managed {@link RoleEntity} and assign it to the
 * user entity. This keeps role assignment inside the mapping step, allowing the CRUD
 * framework's default {@code create()}/{@code update()} (with their audit/snapshot
 * handling) to be reused by {@code UserService} — validation lives in the
 * {@code validateCreate}/{@code validateUpdate} hooks.
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class UserServiceMapper
        implements ServiceToDaoMapper<UserEntity, UserServiceModel, UserServiceExtendedModel> {

    @Autowired
    protected RoleDao roleDao;

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Set.of();
    }

    @Override
    @Mapping(target = "roleId", source = "role.id")
    @Mapping(target = "roleName", expression = "java(source.getRole() != null ? source.getRole().getNameRU() : null)")
    public abstract UserServiceModel toServiceModel(UserEntity source);

    @Override
    @Mapping(target = "roleId", source = "role.id")
    @Mapping(target = "roleName", expression = "java(source.getRole() != null ? source.getRole().getNameRU() : null)")
    public abstract UserServiceExtendedModel toServiceExtendedModel(UserEntity source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "role", ignore = true)
    public abstract UserEntity toCreateDaoModel(UserServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "role", ignore = true)
    public abstract void updateFields(UserServiceExtendedModel source, @MappingTarget UserEntity target);

    /**
     * Resolves {@code roleId} into a managed {@link RoleEntity} and assigns it to the target.
     * On create the role is always resolved; on update the role is only reassigned when the
     * incoming model carries a {@code roleId} (a {@code null} roleId leaves the existing role
     * untouched, consistent with the partial-update semantics of {@code updateFields}).
     * {@code UserService.validateCreate}/{@code validateUpdate} guarantee the roleId is present
     * and valid before the framework calls the mapper, so a missing role here is unexpected.
     */
    @AfterMapping
    protected void resolveRole(UserServiceExtendedModel source, @MappingTarget UserEntity target) {
        if (source.roleId() != null) {
            RoleEntity role = roleDao.findById(source.roleId()).orElse(null);
            if (role != null) {
                target.setRole(role);
            }
        }
    }

    /**
     * Defaults {@code displayPreferences} to an empty map when the incoming value is {@code null},
     * so the entity never carries a {@code null} for this {@code jsonb} column. This keeps the
     * {@link com.foremen.config.persistence.JsonMapConverter} on its non-null path (which returns
     * a {@code jsonb}-typed {@code PGobject} that Hibernate can bind), avoiding the
     * "Unable to bind parameter ... null [Unknown Types value.]" failure that occurs when a null
     * map reaches the converter whose relational type is {@code Object}. Runs for both the create
     * mapping and the update mapping. The stored value for an absent preference set is an empty
     * {@code jsonb} object ({@code {}}); reads coalesce this to {@code {}} identically to a null.
     */
    @AfterMapping
    protected void defaultDisplayPreferences(@MappingTarget UserEntity target) {
        if (target.getDisplayPreferences() == null) {
            target.setDisplayPreferences(Map.of());
        }
    }
}
