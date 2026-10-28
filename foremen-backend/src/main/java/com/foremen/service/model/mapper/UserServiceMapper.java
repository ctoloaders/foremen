package com.foremen.service.model.mapper;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.RefDto;
import com.foremen.dao.RoleDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkerTypeEntity;
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
        // User has no own localized model field resolved by the read-path i18n @AfterMapping
        // (roleName is mapped by a dedicated expression, not the generic processI18n), so this set
        // stays EMPTY. The localized role sort/filter path is declared separately in
        // getI18nSortFilterProperties() so it does not feed the model-field i18n hook.
        return Set.of();
    }

    @Override
    public Set<String> getI18nSortFilterProperties() {
        // Sort/filter-only i18n resolution: the UI sends the unsuffixed association path
        // "role.name"; the shared sort/filter resolution appends the active-locale suffix to the
        // final segment -> "role.nameRU"/"role.namePL" (RoleEntity is localized). This is kept
        // apart from getI18nSupportedProperties() because that set also drives the read/write
        // model-field i18n @AfterMapping hooks, which expect a bare, model-resolvable property —
        // a nested "role.name" there would make them look up a non-existent "role.namePL" field.
        return Set.of("role.name");
    }

    @Override
    @Mapping(target = "roleId", source = "role.id")
    @Mapping(target = "roleName", expression = "java(localizedRoleName(source.getRole()))")
    @Mapping(target = "locale", expression = "java(localeToTag(source.getLocale()))")
    public abstract UserServiceModel toServiceModel(UserEntity source);

    @Override
    @Mapping(target = "roleId", source = "role.id")
    @Mapping(target = "roleName", expression = "java(localizedRoleName(source.getRole()))")
    @Mapping(target = "locale", expression = "java(localeToTag(source.getLocale()))")
    @Mapping(target = "workCategoryIds", ignore = true)
    @Mapping(target = "workCategories", expression = "java(toWorkCategoryRefs(source))")
    // FOR-02-07 — read path: inbound id stays null; the outbound ref is derived from the entity.
    @Mapping(target = "workerTypeId", ignore = true)
    @Mapping(target = "workerType", expression = "java(toWorkerTypeRef(source))")
    public abstract UserServiceExtendedModel toServiceExtendedModel(UserEntity source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "role", ignore = true)
    @Mapping(target = "locale", ignore = true)
    @Mapping(target = "workCategories", ignore = true)
    // FOR-02-07 — the service resolves workerTypeId into a managed WorkerTypeEntity, so the mapper
    // must not touch the association from the model's ref/id fields.
    @Mapping(target = "workerType", ignore = true)
    public abstract UserEntity toCreateDaoModel(UserServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "role", ignore = true)
    @Mapping(target = "locale", ignore = true)
    @Mapping(target = "workCategories", ignore = true)
    @Mapping(target = "workerType", ignore = true)
    public abstract void updateFields(UserServiceExtendedModel source, @MappingTarget UserEntity target);

    /**
     * Applies the inbound {@code String} locale (the wire contract's lowercase language tag) onto
     * the {@link UserEntity} as a {@link java.util.Locale}, keeping the entity side on the migrated
     * {@code Locale} type while the DTO/service-model side stays a {@code String} so the external
     * API shape is unchanged (FOR-06-12 R12.5). A {@code null}/blank value leaves the entity's
     * existing locale untouched (partial-update semantics, consistent with {@code updateFields});
     * on create the entity default (RU) then applies.
     */
    @AfterMapping
    protected void applyLocale(UserServiceExtendedModel source, @MappingTarget UserEntity target) {
        if (source.locale() != null && !source.locale().isBlank()) {
            target.setLocale(java.util.Locale.forLanguageTag(source.locale().trim()));
        }
    }

    /**
     * Renders an entity {@link java.util.Locale} back to the wire-contract lowercase language-tag
     * {@code String} ({@code "pl"}/{@code "ru"}), reusing the
     * {@code LocaleAttributeConverter} write rule so the read side matches what is stored.
     * {@code null} stays {@code null}.
     */
    protected static String localeToTag(java.util.Locale locale) {
        return locale == null ? null : locale.toLanguageTag().toLowerCase(java.util.Locale.ROOT);
    }

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
     * Localized display name of a user's role: {@code nameRU} for a ru request else {@code namePL},
     * with the other locale then the role {@code code} as fallbacks. Resolving by the active request
     * locale (rather than hard-coding RU) is what makes the Users table / edit form show the role
     * name in the caller's language. {@code null} role yields {@code null}.
     */
    protected static String localizedRoleName(RoleEntity role) {
        if (role == null) {
            return null;
        }
        boolean russian = isRussianLocale();
        String name = russian ? role.getNameRU() : role.getNamePL();
        if (name == null || name.isBlank()) {
            name = russian ? role.getNamePL() : role.getNameRU();
        }
        if (name == null || name.isBlank()) {
            name = role.getCode();
        }
        return name;
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

    /** Localized name of a worker type: {@code nameRU} for a ru request else {@code namePL}, with
     * the other locale then the code as fallbacks. */
    protected static String localizedName(WorkerTypeEntity workerType) {
        boolean russian = isRussianLocale();
        String name = russian ? workerType.getNameRU() : workerType.getNamePL();
        if (name == null || name.isBlank()) {
            name = russian ? workerType.getNamePL() : workerType.getNameRU();
        }
        if (name == null || name.isBlank()) {
            name = workerType.getCode();
        }
        return name;
    }

    /**
     * FOR-02-07 — the OPTIONAL worker-type ref for a WORKER user, used as the {@code workerType}
     * expression of {@link #toServiceExtendedModel(UserEntity)}. Carries the worker-type id and its
     * localized display name (ru → pl → code fallback). Gated on WORKER (D-worker-only): a non-WORKER
     * user, or a WORKER with no worker type, yields {@code null}. The write path leaves this null.
     */
    protected static RefDto toWorkerTypeRef(UserEntity source) {
        boolean worker = source.getRole() != null && WORKER_ROLE_CODE.equals(source.getRole().getCode());
        WorkerTypeEntity workerType = source.getWorkerType();
        if (!worker || workerType == null) {
            return null;
        }
        return new RefDto(workerType.getId(), localizedName(workerType));
    }

    private static boolean isRussianLocale() {
        Locale locale = LocaleContextHolder.getLocale();
        return locale != null && "ru".equalsIgnoreCase(locale.getLanguage());
    }
}
