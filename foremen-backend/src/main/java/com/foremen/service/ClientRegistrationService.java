package com.foremen.service;

import com.foremen.controller.model.ClientRegistrationRequest;
import com.foremen.controller.model.ClientRegistrationResponse;
import com.foremen.dao.RoleDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers a CLIENT user for a specific project through the dedicated client-registration path,
 * distinct from the generic {@code POST /api/users} CRUD create (Requirement 10).
 *
 * <p>The role is fixed to {@code CLIENT} server-side (never supplied by the caller): it is resolved
 * via {@link RoleDao#findByCode(String)} (Req 10.4). The service reuses the FOR-03-02 invite create
 * path through {@link UserService#createClient}, so the new user is persisted with {@code status =
 * INVITED}, a null password, and a client-portal invitation email dispatched by the {@code
 * afterCreate} hook (Req 10.4, 10.5); a duplicate email surfaces the established 409 there (Req 10.8).
 * It then attaches the user to the supplied project under the CLIENT project role via
 * {@link ProjectMemberService#assign} (Req 10.6), whose duplicate {@code (userId, projectId)} pair
 * yields 409 {@code error.project.member.duplicate} (Req 10.9).
 *
 * <p>The whole method runs in a single transaction (class-level {@link Transactional}), so a failed
 * membership assignment rolls back the user creation and invitation, leaving no orphaned CLIENT user
 * (Req 10.7, 10.9).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ClientRegistrationService {

    private final UserService userService;                   // reuses create(...) + afterCreate invite (FOR-03-02)
    private final RoleDao roleDao;
    private final ProjectMemberService projectMemberService; // FOR-03-04

    /**
     * Creates an INVITED CLIENT user and assigns it to the requested project as one atomic unit.
     *
     * @throws ForemenApiException 500 {@code error.role.client.missing} when the CLIENT role is not
     *         seeded; the established 409 duplicate-email error from the create path (Req 10.8);
     *         409 {@code error.project.member.duplicate} from {@link ProjectMemberService#assign}
     *         when the membership already exists (Req 10.9).
     */
    public ClientRegistrationResponse register(ClientRegistrationRequest request) {
        RoleEntity clientRole = roleDao.findByCode("CLIENT")                             // Req 10.4 - server-resolved
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "error.role.client.missing"));

        // Req 10.4, 10.5, 10.8 - create the INVITED CLIENT user through the same UserService path
        // the admin create uses; its afterCreate hook issues the invite token and client-portal
        // invitation. Duplicate email surfaces the established 409 here.
        UserEntity client = userService.createClient(
                request.name(), request.email(), request.phone(), request.locale(), clientRole);

        // Req 10.6, 10.9 - attach to the project under the CLIENT project role; a duplicate
        // (userId, projectId) throws 409 error.project.member.duplicate, rolling back the whole
        // transaction (Req 10.7).
        projectMemberService.assign(client.getId(), request.projectId(), clientRole.getId());

        return new ClientRegistrationResponse(client.getId(), client.getEmail(), request.projectId());
    }
}
