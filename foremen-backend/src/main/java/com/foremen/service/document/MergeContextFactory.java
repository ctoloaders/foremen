package com.foremen.service.document;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.foremen.dao.CompanyProfileDao;
import com.foremen.dao.OfferDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoomDao;
import com.foremen.dao.model.CompanyProfileEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.signing.merge.MergeContext;

import lombok.RequiredArgsConstructor;

/**
 * FOR-05-08: the single assembler of a {@link MergeContext} from a project (and optionally the
 * document being generated). Reused by both the document-generation path
 * ({@code SignableDocumentService.generate}) and the project template-view preview
 * ({@code ProjectTemplateViewService}) so the two can never resolve a token differently.
 *
 * <p>It resolves, best-effort (a missing source leaves the field {@code null}/empty so the token is
 * reported unresolved rather than silently blank, R3.3/R3.4):
 * <ul>
 *   <li>the project CLIENT member(s) → {@code clientMembers} (first = primary client);</li>
 *   <li>the first MANAGER/FOREMAN member → {@code representative};</li>
 *   <li>the singleton {@code CompanyProfile} → {@code companyProfile};</li>
 *   <li>the project rooms → {@code rooms} (usable-area fallback);</li>
 *   <li>the project's APPROVED offer {@code totalNet} → {@code approvedOfferNetTotal}
 *       ({@code {TotalBeforeTax}}) — wired HERE (previously left unset).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class MergeContextFactory {

    private static final String CLIENT_ROLE = "CLIENT";
    private static final String MANAGER_ROLE = "MANAGER";
    private static final String FOREMAN_ROLE = "FOREMAN";

    private final ProjectMemberDao projectMemberDao;
    private final RoomDao roomDao;
    private final CompanyProfileDao companyProfileDao;
    private final OfferDao offerDao;

    /**
     * Builds the merge context for a project. The {@code document} is optional: pass the document
     * being generated (so {@code {Id}} / {@code {DocumentCreateTime}} resolve); pass a transient
     * placeholder document (bound to no project data) for a pure project preview.
     *
     * @param project  the owning project; may be {@code null} (every project-backed token unresolved)
     * @param document the document being generated / previewed; must not be {@code null} (the context
     *                 requires one — callers with no real document pass a transient instance)
     * @return the assembled, immutable context
     */
    public MergeContext build(ProjectEntity project, SignableDocumentEntity document) {
        Long projectId = project != null ? project.getId() : null;

        List<ProjectMemberEntity> members = projectId == null
                ? List.of()
                : projectMemberDao.findByProjectIdWithUserAndRole(projectId);

        List<UserEntity> clientMembers = new ArrayList<>();
        UserEntity representative = null;
        for (ProjectMemberEntity member : members) {
            String roleCode = projectRoleCode(member);
            if (member.getUser() == null) {
                continue;
            }
            if (CLIENT_ROLE.equals(roleCode)) {
                clientMembers.add(member.getUser());
            } else if (representative == null
                    && (MANAGER_ROLE.equals(roleCode) || FOREMAN_ROLE.equals(roleCode))) {
                representative = member.getUser();
            }
        }

        List<RoomEntity> rooms = projectId == null ? List.of() : roomDao.findByProjectId(projectId);
        CompanyProfileEntity companyProfile = singleCompanyProfile();
        String approvedOfferNetTotal = approvedOfferNetTotal(projectId);

        return MergeContext.builder()
                .document(document)
                .project(project)
                .clientMembers(clientMembers)
                .rooms(rooms)
                .representative(representative)
                .companyProfile(companyProfile)
                .approvedOfferNetTotal(approvedOfferNetTotal)
                .build();
    }

    /**
     * The project's APPROVED offer net total ({@code {TotalBeforeTax}}), formatted plainly, or
     * {@code null} when the project has no APPROVED offer. A project should have at most one APPROVED
     * offer; when several exist (data anomaly) the lowest-id one wins (findByProjectIdOrderByIdAsc).
     */
    private String approvedOfferNetTotal(Long projectId) {
        if (projectId == null) {
            return null;
        }
        for (OfferEntity offer : offerDao.findByProjectIdOrderByIdAsc(projectId)) {
            if (offer != null && offer.getStatus() == OfferStatus.APPROVED) {
                BigDecimal net = offer.getTotalNet();
                return net == null ? null : net.toPlainString();
            }
        }
        return null;
    }

    /** The single admin-managed {@code CompanyProfile} row, or {@code null} when none is configured. */
    private CompanyProfileEntity singleCompanyProfile() {
        for (CompanyProfileEntity profile : companyProfileDao.findAll()) {
            return profile;
        }
        return null;
    }

    private static String projectRoleCode(ProjectMemberEntity member) {
        if (member == null || member.getProjectRole() == null) {
            return null;
        }
        return member.getProjectRole().getCode();
    }
}
