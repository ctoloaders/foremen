package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.RefDto;
import com.foremen.dao.RoleDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.UserServiceExtendedModel;
import com.foremen.service.model.UserServiceModel;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Abstract mapper (rather than a plain interface) so it can resolve the {@code roleId}
 * on the incoming service model into a managed {@link RoleEntity} and assign it to the
 * user entity. This keeps role assignment inside the mapping step, allowing the CRUD
 * framework's default {@code create()}/{@code update()} (with their audit/snapshot
 * handling) to be reused by {@code UserService} — validation lives in the
 * {@code validateCreate}/{@code validateUpdate} hooks.
 *
 * <p>FOR-05-10b (R1.1, R1.5) — the User_Work_Categories collection is NOT mapped by MapStruct on
 * the write path ({@code toCreateDaoModel}/{@code updateFields} ignore both the inbound
 * {@code workCategoryIds} and the outbound {@code workCategories}, because the service owns resolving
 * ids into managed {@link WorkCategoryEntity} rows and setting the ordered entity list). On the read
 * path {@link #toWorkCategoryRefs} populates the service model's ordered {@code workCategories}
 * refs (id + localized name, ru → pl → code fallback) for a WORKER user only.
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class UserServiceMapper
        implements ServiceToDaoMapper<UserEntity, UserServiceModel, UserServiceExtendedModel> {

    private static final String WORKER_ROLE_CODE = "WORKER";

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
    @Mapping(target = "workCategoryIds", ignore = true)
    @Mapping(target = "workCategories", expression = "java(toWorkCategoryRefs(source))")
    public abstract UserServiceExtendedModel toServiceExtendedModel(UserEntity source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "role", ignore = true)
    @Mapping(target = "workCategories", ignore = true)
    public abstract UserEntity toCreateDaoModel(UserServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "role", ignore = true)
    @Mapping(target = "workCategories", ignore = true)
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

    /** Localized name of a work category: {@code nameRU} for a ru request else {@code namePL}, with
     * the other locale then the code as fallbacks (R1.5). */
    protected static String localizedName(WorkCategoryEntity category) {
        boolean russian = isRussianLocale();
        String name = russian ? category.getNameRU() : category.getNamePL();
        if (name == null || name.isBlank()) {
            name = russian ? category.getNamePL() : category.getNameRU();
        }
        if (name == null || name.isBlank()) {
            name = category.getCode();
        }
        return name;
    }

    /**
     * FOR-05-10b (R1.5) — the ordered {@code workCategories} refs for a WORKER user, used as the
     * {@code workCategories} expression of {@link #toServiceExtendedModel(UserEntity)}. Each ref
     * carries the category id and its localized display name (ru → pl → code fallback), in the stored
     * {@code @OrderColumn} order. A non-WORKER user (or a WORKER with no categories) yields an empty
     * list (D-worker-only). The write-path models leave this null (see class javadoc).
     */
    protected static List<RefDto> toWorkCategoryRefs(UserEntity source) {
        boolean worker = source.getRole() != null && WORKER_ROLE_CODE.equals(source.getRole().getCode());
        if (!worker || source.getWorkCategories() == null || source.getWorkCategories().isEmpty()) {
            return List.of();
        }
        List<RefDto> refs = new ArrayList<>(source.getWorkCategories().size());
        for (WorkCategoryEntity category : source.getWorkCategories()) {
            refs.add(new RefDto(category.getId(), localizedName(category)));
        }
        return refs;
    }

    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }
}
