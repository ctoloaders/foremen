package com.foremen.service;

import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.UserServiceExtendedModel;
import com.foremen.service.model.UserServiceModel;
import com.foremen.service.model.mapper.UserServiceMapper;
import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
@RequiredArgsConstructor
@Getter
@Transactional
public class UserService implements AdminService<
        UserServiceModel, UserServiceExtendedModel, UserEntity, Long> {

    private static final Set<String> SUPPORTED_LOCALES = Set.of("ru", "pl", "en");

    private final UserDao dao;
    private final RoleDao roleDao;
    private final UserServiceMapper mapper;
    private final AuditLogDao auditLogDao;
    private final EntityManager entityManager;
    private final Class<UserEntity> daoModelClass = UserEntity.class;

    // --- Create with validation ---

    @Override
    public UserServiceExtendedModel create(UserServiceExtendedModel model) {
        validateEmailUniqueness(model.email(), null);
        validateLocale(model.locale());
        RoleEntity role = resolveRole(model.roleId());

        UserEntity entity = mapper.toCreateDaoModel(model);
        entity.setRole(role);
        entity = dao.save(entity);
        entityManager.flush();
        saveAudit(null, entity, "CREATE");
        return mapper.toServiceExtendedModel(entity);
    }

    // --- Update with validation ---

    @Override
    public UserServiceExtendedModel update(Long id, UserServiceExtendedModel model) {
        UserEntity existing = dao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", id));

        validateEmailUniqueness(model.email(), id);
        validateLocale(model.locale());
        RoleEntity role = resolveRole(model.roleId());

        UserEntity before = new UserEntity();
        before.setId(existing.getId());
        before.setName(existing.getName());
        before.setEmail(existing.getEmail());
        before.setPhone(existing.getPhone());
        before.setRole(existing.getRole());
        before.setActive(existing.isActive());
        before.setLocale(existing.getLocale());
        before.setDisplayPreferences(existing.getDisplayPreferences());

        mapper.updateFields(model, existing);
        existing.setRole(role);
        UserEntity saved = dao.save(existing);
        entityManager.flush();
        saveAudit(before, saved, "UPDATE");
        return mapper.toServiceExtendedModel(saved);
    }

    // --- Soft-delete (set active = false) ---

    @Override
    public void deleteById(Long id) {
        UserEntity user = dao.findById(id)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", id));

        UserEntity before = new UserEntity();
        before.setId(user.getId());
        before.setActive(user.isActive());

        user.setActive(false);
        dao.save(user);
        entityManager.flush();
        saveAudit(before, user, "DEACTIVATE");
    }

    // --- Validation helpers ---

    private void validateEmailUniqueness(String email, Long excludeId) {
        if (email == null) return;
        dao.findByEmail(email).ifPresent(existing -> {
            if (!existing.getId().equals(excludeId)) {
                throw new ForemenApiException(HttpStatus.CONFLICT, "error.user.email.already.exists", email);
            }
        });
    }

    private void validateLocale(String locale) {
        if (locale != null && !SUPPORTED_LOCALES.contains(locale)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.user.locale.invalid", locale);
        }
    }

    private RoleEntity resolveRole(Long roleId) {
        if (roleId == null) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.user.role.required");
        }
        return roleDao.findById(roleId)
                .orElseThrow(() -> new ForemenApiException(HttpStatus.BAD_REQUEST, "error.user.role.not.found", roleId));
    }
}
