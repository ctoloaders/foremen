package com.foremen.service.document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.ProjectDao;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.signing.merge.MergeContext;
import com.foremen.service.signing.merge.MergeFieldResolver;

import lombok.RequiredArgsConstructor;

/**
 * FOR-05-08 (template placeholder values): assembles the {@link ProjectTemplateViewDTO} for a
 * project — the resolved value of every supported template {@code {Token}} placeholder.
 *
 * <p>It reuses the SAME merge machinery that fills a real generated document: it builds the project's
 * {@link MergeContext} via {@link MergeContextFactory} and resolves each supported token through the
 * injected ordered {@link MergeFieldResolver}s (exactly like {@code DefaultTemplateMergeEngine}
 * does per placeholder). So a token resolves to the same value here as it would in a generated
 * document — the preview can never drift from generation.
 *
 * <p>The document-meta tokens {@code {Id}} / {@code {DocumentCreateTime}} are intentionally NOT
 * surfaced: they are per-document (there is no persisted document in a pure project preview), so they
 * stay manual placeholders. Every other supported token is read from the project data.
 */
@Service
@RequiredArgsConstructor
public class ProjectTemplateViewService {

    private static final String ENTITY_NOT_FOUND_MESSAGE = "error.entity.not.found";

    /**
     * The supported, project-backed tokens — the authoritative preview set, mirroring the resolver
     * groups (ClientResolver / CompanyRequisitesResolver / RepresentativeResolver / PropertyResolver
     * / ScheduleResolver / OfferTotalsResolver). Document-meta tokens are excluded on purpose (see
     * class javadoc). Order is presentation-stable for the flat {@code tokens} map.
     */
    static final List<String> SUPPORTED_TOKENS = List.of(
            // client
            "ContactName", "ContactLastName", "ContactEmail", "ContactAddress",
            "RequisitePrimaryAddressText",
            // executor company requisites
            "UfCrm1663076108RequisiteName", "UfCrm1663076108RequisiteAddress",
            "UfCrm1663076108RequisiteNip", "UfCrm1663076108RequisiteRegon",
            "UfCrm1663076108RequisiteRepresentativeName",
            "UfCrm1663076108RequisiteRepresentativeRole", "UfCrm1663076108RequisiteEmail",
            // executor representative
            "AssignedName", "AssignedLastName",
            "UfCrm1692271880Email", "UfCrm1692271880Phone",
            // property / area
            "UfCrm1662541716442", "UfCrm1691765118573",
            // schedule
            "Begindate", "Closedate",
            // offer totals
            "TotalBeforeTax");

    private final ProjectDao projectDao;
    private final MergeContextFactory mergeContextFactory;
    /** The ordered resolvers (Spring-injected), used exactly as {@code DefaultTemplateMergeEngine} does. */
    private final List<MergeFieldResolver> resolvers;

    /**
     * Builds the {@link ProjectTemplateViewDTO} for a project: resolves every supported token from
     * the project's merge context and packs the results into the nested groups + the flat
     * {@code tokens} map (resolved tokens only).
     *
     * @param projectId the project to build the view for
     * @return the resolved template-value view
     * @throws ForemenApiException 404 when the project is missing
     */
    @Transactional(readOnly = true)
    public ProjectTemplateViewDTO getView(Long projectId) {
        ProjectEntity project = requireProject(projectId);

        // A transient placeholder document: the MergeContext requires a non-null document, but a
        // pure project preview has none persisted. It carries no project-meta, so {Id} /
        // {DocumentCreateTime} resolve to null (unresolved) — which is correct for a project preview.
        SignableDocumentEntity placeholder = new SignableDocumentEntity();
        MergeContext ctx = mergeContextFactory.build(project, placeholder);

        Map<String, String> tokens = new LinkedHashMap<>();
        for (String token : SUPPORTED_TOKENS) {
            String value = resolve(token, ctx);
            if (value != null && !value.isBlank()) {
                tokens.put(token, value);
            }
        }

        return new ProjectTemplateViewDTO(
                projectId,
                new ProjectTemplateViewDTO.Client(
                        tokens.get("ContactName"),
                        tokens.get("ContactLastName"),
                        tokens.get("ContactEmail"),
                        tokens.get("ContactAddress")),
                new ProjectTemplateViewDTO.Executor(
                        tokens.get("UfCrm1663076108RequisiteName"),
                        tokens.get("UfCrm1663076108RequisiteAddress"),
                        tokens.get("UfCrm1663076108RequisiteNip"),
                        tokens.get("UfCrm1663076108RequisiteRegon"),
                        tokens.get("UfCrm1663076108RequisiteRepresentativeName"),
                        tokens.get("UfCrm1663076108RequisiteRepresentativeRole"),
                        tokens.get("UfCrm1663076108RequisiteEmail"),
                        tokens.get("AssignedName"),
                        tokens.get("AssignedLastName"),
                        tokens.get("UfCrm1692271880Email"),
                        tokens.get("UfCrm1692271880Phone")),
                new ProjectTemplateViewDTO.Property(
                        tokens.get("UfCrm1662541716442"),
                        tokens.get("UfCrm1691765118573")),
                new ProjectTemplateViewDTO.Schedule(
                        tokens.get("Begindate"),
                        tokens.get("Closedate")),
                new ProjectTemplateViewDTO.Offer(tokens.get("TotalBeforeTax")),
                tokens);
    }

    /** Resolves one token via the first supporting resolver (mirrors DefaultTemplateMergeEngine). */
    private String resolve(String token, MergeContext ctx) {
        for (MergeFieldResolver resolver : resolvers) {
            if (resolver.supports(token)) {
                return resolver.resolve(token, ctx);
            }
        }
        return null;
    }

    private ProjectEntity requireProject(Long projectId) {
        if (projectId == null) {
            throw new ForemenApiException(HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "projectId", projectId);
        }
        return projectDao.findById(projectId)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.NOT_FOUND, ENTITY_NOT_FOUND_MESSAGE, "projectId", projectId));
    }
}
