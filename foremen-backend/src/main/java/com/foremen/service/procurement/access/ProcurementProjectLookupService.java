package com.foremen.service.procurement.access;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.ProjectDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;

/**
 * FOR-11-00 (R9.8): the projects the caller may work with in procurement, for the project filter.
 * ADMIN and BUYER get every {@code ACTIVE} project; every other role gets the projects of its ACTIVE
 * memberships (resolved by {@link ProcurementProjectAccess}).
 */
@Service
public class ProcurementProjectLookupService {

    /** Read model of one lookup row. */
    public record ProjectLookupItem(Long id, String name, String address, ProjectStatus status) {
    }

    private final ProjectDao projectDao;
    private final ProcurementProjectAccess access;

    public ProcurementProjectLookupService(ProjectDao projectDao, ProcurementProjectAccess access) {
        this.projectDao = projectDao;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public List<ProjectLookupItem> projectsForCaller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return List.of();
        }
        List<ProjectEntity> projects;
        if (isAdmin(auth) || access.isBuyer(auth)) {
            projects = projectDao.findByStatusInOrderByIdAsc(Set.of(ProjectStatus.ACTIVE));
        } else {
            Long userId = parseUserId(auth.getName());
            Set<Long> ids = access.allowedProjectIds(userId);
            projects = new ArrayList<>();
            if (ids != null && !ids.isEmpty()) {
                projectDao.findAllById(ids).forEach(projects::add);
            }
        }
        return projects.stream()
                .sorted(Comparator.comparing(ProjectEntity::getName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(ProjectEntity::getId))
                .map(p -> new ProjectLookupItem(p.getId(), p.getName(), p.getAddress(), p.getStatus()))
                .toList();
    }

    private static boolean isAdmin(Authentication auth) {
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if ("ROLE_ADMIN".equals(ga.getAuthority()) || "ADMIN".equals(ga.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private static Long parseUserId(String name) {
        try {
            return name == null ? null : Long.parseLong(name.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
