package com.foremen.service.property;

// Feature: FOR-03-02-user-invitation, Property 3: Created users are INVITED with no password

import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.service.InviteService;
import com.foremen.service.UserService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.model.UserServiceExtendedModel;
import com.foremen.service.model.mapper.UserServiceMapper;
import com.foremen.service.model.mapper.UserServiceMapperImpl;

import jakarta.persistence.EntityManager;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property test for the created-user invariant enforced by the user-management create path
 * (Requirements 3.4, 3.7).
 *
 * <p><b>Property 3: Created users are INVITED with no password</b> &mdash; <i>for all</i>
 * user-creation inputs to the user-management API, the created {@link UserEntity} has status
 * {@link UserStatus#INVITED} and a {@code null} {@code passwordHash}.
 *
 * <p>The invariant is guaranteed by two collaborators the create path routes through:
 * the request DTO ({@link UserServiceExtendedModel}) exposes no {@code status} or
 * {@code password}/{@code passwordHash} field, and {@link UserServiceMapper#toCreateDaoModel}
 * builds a fresh {@link UserEntity} without ever setting {@code status} or {@code passwordHash},
 * so both keep their construction defaults ({@code INVITED} / {@code null}).
 *
 * <p>To exercise the genuine create path rather than a re-implementation, this test drives
 * {@link UserService#create(Object)} (the {@code AdminService} default: validate &rarr; map
 * &rarr; save &rarr; flush &rarr; {@code afterCreate} &rarr; audit) with the <b>real</b>
 * {@link UserServiceMapperImpl}. DAOs and the {@link EntityManager} are Mockito mocks; the
 * {@link RoleDao} resolves any generated {@code roleId} to a non-ADMIN role (so
 * {@code validateCreate} passes) and {@code findByEmail} returns empty (unique email). The
 * {@link UserDao} echoes back the entity from {@code save} so the persisted instance can be
 * captured and asserted. {@link InviteService} is mocked, making {@code afterCreate} a no-op so
 * this property isolates the created-user invariant from invite issuance.
 *
 * <b>Validates: Requirements 3.4, 3.7</b>
 */
class UserCreateInvitePropertyTest {

    /** Non-ADMIN role codes: the create path forbids ADMIN, so we never generate it here. */
    @Provide
    Arbitrary<String> nonAdminRoleCodes() {
        return Arbitraries.of("MANAGER", "FOREMAN", "WORKER", "FINANCIER", "CLIENT", "CUSTOM_ROLE");
    }

    @Provide
    Arbitrary<String> names() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(30);
    }

    @Provide
    Arbitrary<String> emails() {
        Arbitrary<String> local = Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(20);
        Arbitrary<String> domain = Arbitraries.of("example.com", "foremen.pl", "mail.test", "corp.io");
        return Combinators.combine(local, domain).as((l, d) -> l + "@" + d);
    }

    /** Supported locales (validateCreate rejects unsupported ones) plus null (no locale supplied). */
    @Provide
    Arbitrary<String> locales() {
        return Arbitraries.of("ru", "pl", null);
    }

    // Feature: FOR-03-02-user-invitation, Property 3: Created users are INVITED with no password
    // For all user-creation inputs, the persisted UserEntity is INVITED with a null passwordHash.
    // Validates: Requirements 3.4, 3.7
    @Property(tries = 100)
    void createdUserIsInvitedWithNoPassword(
            @ForAll("names") String name,
            @ForAll("emails") String email,
            @ForAll("locales") String locale,
            @ForAll("nonAdminRoleCodes") String roleCode,
            @ForAll long roleId,
            @ForAll boolean active) {

        Fixture f = new Fixture();
        when(f.roleDao.findById(anyLong())).thenReturn(Optional.of(role(roleId, roleCode)));

        UserServiceExtendedModel model = new UserServiceExtendedModel(
                null, name, email, null, roleId, null, active, locale, null);

        f.service.create(model);

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(f.userDao).save(captor.capture());
        UserEntity created = captor.getValue();

        // The created user is always INVITED (3.7) with a null passwordHash (3.4),
        // regardless of the generated create input.
        assertThat(created.getStatus()).isEqualTo(UserStatus.INVITED);
        assertThat(created.getPasswordHash()).isNull();
    }

    // ---- Fixture and helpers ----

    /**
     * Bundles a {@link UserService} built over the real {@link UserServiceMapperImpl} and mocked
     * collaborators. {@code userDao.save} echoes its argument so the persisted entity can be
     * captured; {@code findByEmail} returns empty (email uniqueness passes). {@link InviteService}
     * is mocked so the transactional {@code afterCreate} hook is a no-op.
     */
    private static final class Fixture {
        final UserDao userDao = mock(UserDao.class);
        final RoleDao roleDao = mock(RoleDao.class);
        final AuditLogDao auditLogDao = mock(AuditLogDao.class);
        final EntityManager entityManager = mock(EntityManager.class);
        final InviteService inviteService = mock(InviteService.class);
        final UserService service;

        Fixture() {
            UserServiceMapperImpl mapper = new UserServiceMapperImpl();
            injectRoleDao(mapper, roleDao);
            when(userDao.findByEmail(any())).thenReturn(Optional.empty());
            when(userDao.save(any(UserEntity.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            service = new UserService(
                    userDao, roleDao, mapper, auditLogDao, entityManager, inviteService, null);
        }
    }

    private static RoleEntity role(long id, String code) {
        RoleEntity role = new RoleEntity();
        role.setId(id);
        role.setCode(code);
        return role;
    }

    /**
     * Injects the mocked {@link RoleDao} into the mapper's {@code protected roleDao} field
     * (declared on {@link UserServiceMapper}) so the real {@code resolveRole} mapping step can
     * resolve generated {@code roleId}s. Mirrors what Spring's {@code @Autowired} does at runtime.
     */
    private static void injectRoleDao(UserServiceMapper mapper, RoleDao roleDao) {
        try {
            Field field = UserServiceMapper.class.getDeclaredField("roleDao");
            field.setAccessible(true);
            field.set(mapper, roleDao);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to inject roleDao into mapper", e);
        }
    }
}
