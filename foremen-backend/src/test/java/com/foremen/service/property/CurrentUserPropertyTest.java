package com.foremen.service.property;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.foremen.config.security.JwtProperties;
import com.foremen.config.security.JwtTokenProvider;
import com.foremen.controller.dto.auth.CurrentUserResponse;
import com.foremen.controller.dto.auth.PermissionView;
import com.foremen.dao.PasswordResetTokenDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.AuthService;
import com.foremen.service.RefreshTokenService;
import com.foremen.service.mail.MailSender;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.LongRange;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link AuthService#currentUser(Long)} (Requirements 9.2, 9.4).
 *
 * <p>Each iteration builds a random role/resource/operation graph, attaches it to a generated
 * {@link UserEntity}, and mocks {@link UserDao#findById(Object)} to return that user. The other
 * constructor collaborators are Mockito mocks; a real {@link BCryptPasswordEncoder} with cost
 * factor 12 is used because the {@code AuthService} constructor computes a dummy hash with it.
 *
 * <p>The expected permission projection is built independently the same way the design mandates
 * (each {@link RoleResourceEntity} -> resource code paired with the set of its operation codes)
 * and compared to the returned {@code permissions} for exact equality. Resource codes in the
 * generated graph are kept distinct so the projection is well-defined (no two role-resources
 * collapse onto the same resource code).
 *
 * Property 22: Current-user response reflects identity and derived permissions
 * — Validates: Requirements 9.2, 9.4
 */
@Tag("Feature: FOR-03-01-jwt-auth, Property 22: Current-user response")
class CurrentUserPropertyTest {

    private static final String SECRET =
            "foremen-jwt-test-secret-key-0123456789ABCDEF";

    private record Fixture(AuthService service, UserDao userDao) {}

    /** A generated role graph: role code plus a distinct-resource-code -> operation-codes map. */
    private record RoleGraph(String roleCode, List<ResourceOps> resources) {}

    private record ResourceOps(String resourceCode, List<String> operationCodes) {}

    private static Fixture fixture() {
        UserDao userDao = Mockito.mock(UserDao.class);
        JwtTokenProvider jwtTokenProvider = Mockito.mock(JwtTokenProvider.class);
        RefreshTokenService refreshTokenService = Mockito.mock(RefreshTokenService.class);
        PasswordResetTokenDao passwordResetTokenDao = Mockito.mock(PasswordResetTokenDao.class);
        MailSender mailSender = Mockito.mock(MailSender.class);
        JwtProperties props = new JwtProperties(30, 7, SECRET);
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

        AuthService service = new AuthService(
                userDao, encoder, jwtTokenProvider, refreshTokenService, props,
                passwordResetTokenDao, mailSender, null, null, null, null);
        return new Fixture(service, userDao);
    }

    /**
     * Materializes a {@link RoleGraph} into a {@link UserEntity} carrying a {@link RoleEntity}
     * with the corresponding {@link RoleResourceEntity} associations.
     */
    private static UserEntity buildUser(long userId, String name, String email, RoleGraph graph) {
        RoleEntity role = new RoleEntity();
        role.setId(userId);
        role.setCode(graph.roleCode());

        List<RoleResourceEntity> roleResources = new ArrayList<>();
        long idSeq = 1;
        for (ResourceOps resourceOps : graph.resources()) {
            ResourceEntity resource = new ResourceEntity();
            resource.setId(idSeq);
            resource.setCode(resourceOps.resourceCode());

            RoleResourceEntity roleResource = new RoleResourceEntity();
            roleResource.setId(idSeq);
            roleResource.setRole(role);
            roleResource.setResource(resource);

            List<OperationEntity> operations = new ArrayList<>();
            long opId = 1;
            for (String opCode : resourceOps.operationCodes()) {
                OperationEntity operation = new OperationEntity();
                operation.setId(idSeq * 1000 + opId++);
                operation.setCode(opCode);
                operations.add(operation);
            }
            roleResource.setOperations(operations);
            roleResources.add(roleResource);
            idSeq++;
        }
        role.setRoleResources(roleResources);

        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setName(name);
        user.setEmail(email);
        user.setRole(role);
        return user;
    }

    /** Builds the expected permission projection the same way the design mandates. */
    private static Set<PermissionView> expectedPermissions(RoleGraph graph) {
        Set<PermissionView> expected = new LinkedHashSet<>();
        for (ResourceOps resourceOps : graph.resources()) {
            Set<String> ops = new LinkedHashSet<>(resourceOps.operationCodes());
            expected.add(new PermissionView(resourceOps.resourceCode(), ops));
        }
        return expected;
    }

    // Feature: FOR-03-01-jwt-auth, Property 22: Current-user response reflects identity and
    // derived permissions.
    // For all authenticated users, currentUser returns the user's id, name, email, and role code,
    // and a permission set derived exactly from the role's resource-operation associations.
    // Validates: Requirements 9.2, 9.4
    @Property(tries = 100)
    void currentUserReflectsIdentityAndDerivedPermissions(
            @ForAll @LongRange(min = 1, max = Long.MAX_VALUE) long userId,
            @ForAll("names") String name,
            @ForAll("emails") String email,
            @ForAll("roleGraphs") RoleGraph graph) {

        Fixture fx = fixture();
        UserEntity user = buildUser(userId, name, email, graph);
        when(fx.userDao().findById(eq(userId))).thenReturn(Optional.of(user));

        CurrentUserResponse response = fx.service().currentUser(userId);

        assertThat(response.id()).as("id must match").isEqualTo(userId);
        assertThat(response.name()).as("name must match").isEqualTo(name);
        assertThat(response.email()).as("email must match").isEqualTo(email);
        assertThat(response.roleCode()).as("role code must match").isEqualTo(graph.roleCode());
        assertThat(response.permissions())
                .as("permissions must match the derived resource-operation projection exactly")
                .isEqualTo(expectedPermissions(graph));
    }

    // --- Providers ---

    @Provide
    Arbitrary<String> names() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(30);
    }

    @Provide
    Arbitrary<String> emails() {
        Arbitrary<String> local = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12);
        Arbitrary<String> domain = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8);
        return Combinators.combine(local, domain).as((l, d) -> l + "@" + d + ".com");
    }

    /**
     * Role graphs with distinct resource codes (so the resource->operations projection is
     * well-defined) and de-duplicated operation codes per resource.
     */
    @Provide
    Arbitrary<RoleGraph> roleGraphs() {
        Arbitrary<String> roleCode = Arbitraries.of(
                "ADMIN", "MANAGER", "FOREMAN", "WORKER", "FINANCIER", "CLIENT");

        Arbitrary<String> resourceCode = Arbitraries.strings()
                .withCharRange('a', 'z').ofMinLength(1).ofMaxLength(10);

        Arbitrary<List<String>> operationCodes = Arbitraries.strings()
                .withCharRange('a', 'z').ofMinLength(1).ofMaxLength(6)
                .list().uniqueElements().ofMinSize(0).ofMaxSize(5);

        Arbitrary<ResourceOps> resourceOps =
                Combinators.combine(resourceCode, operationCodes).as(ResourceOps::new);

        // Keep resource codes distinct across the list so the projection collapses cleanly.
        Arbitrary<List<ResourceOps>> resources = resourceOps
                .list()
                .uniqueElements(ResourceOps::resourceCode)
                .ofMinSize(0)
                .ofMaxSize(6);

        return Combinators.combine(roleCode, resources).as(RoleGraph::new);
    }
}
