package com.foremen.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.foremen.controller.model.ProcurementLineModel;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.service.image.ImageStorage;
import com.foremen.service.model.ProcurementDocumentServiceExtendedModel;
import com.foremen.service.model.mapper.ProcurementDocumentServiceMapper;
import com.foremen.service.procurement.PlannedMaterial;
import com.foremen.service.procurement.PlannedMaterialCandidateProvider;

/**
 * FOR-07-03 (design §CompanyPurchasesService) — the read-field resolution shared by
 * {@link ProcurementDocumentService} (project-scoped) and the company layer. Fills the fields the
 * mapper deliberately leaves {@code null}: the uploader display name, the signed raw + processed image
 * URLs, and each line's matched-material name. It performs NO project-scope access check; callers are
 * responsible for authorization.
 */
final class ProcurementReadFields {

    /** Signed-download-URL TTL for the raw/processed renditions resolved on the read path. */
    static final long IMAGE_URL_TTL_SECONDS = 600L;

    private final ProcurementDocumentServiceMapper serviceMapper;
    private final ImageStorage imageStorage;
    private final PlannedMaterialCandidateProvider candidateProvider;

    ProcurementReadFields(ProcurementDocumentServiceMapper serviceMapper, ImageStorage imageStorage,
                          PlannedMaterialCandidateProvider candidateProvider) {
        this.serviceMapper = serviceMapper;
        this.imageStorage = imageStorage;
        this.candidateProvider = candidateProvider;
    }

    /** Maps the entity and fills all read fields. */
    ProcurementDocumentServiceExtendedModel toReadModel(ProcurementDocumentEntity entity) {
        ProcurementDocumentServiceExtendedModel model = serviceMapper.toServiceExtendedModel(entity);
        resolve(model, entity);
        return model;
    }

    /** Fills uploader name, image URLs and matched-material names on an already-mapped model. */
    void resolve(ProcurementDocumentServiceExtendedModel model, ProcurementDocumentEntity entity) {
        if (entity.getUploadedBy() != null) {
            model.setUploaderName(entity.getUploadedBy().getName());
        }
        model.setRawImageUrl(resolveImageUrl(entity.getRawObjectKey()));
        model.setProcessedImageUrl(resolveImageUrl(entity.getProcessedObjectKey()));
        fillMatchedMaterialNames(model, entity);
    }

    /**
     * Resolves a procurement image to a short-lived V4 signed download URL (the bucket is private,
     * mirroring {@code SignableDocumentService}). A {@code null}/blank key yields {@code null}.
     */
    String resolveImageUrl(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        return imageStorage.signedDownloadUrl(objectKey, IMAGE_URL_TTL_SECONDS);
    }

    /**
     * Replaces each read-line's {@code matchedMaterialName} with the matched material's display name
     * from the project's candidate projection. Unmatched lines, and documents without a project, keep
     * a {@code null} name.
     */
    private void fillMatchedMaterialNames(ProcurementDocumentServiceExtendedModel model,
                                          ProcurementDocumentEntity entity) {
        if (model.getLines() == null || model.getLines().isEmpty()) {
            return;
        }
        Long projectId = entity.getProject() == null ? null : entity.getProject().getId();
        Map<Long, String> namesById = matchedNamesById(projectId);
        List<ProcurementLineModel> resolved = new ArrayList<>(model.getLines().size());
        for (ProcurementLineModel line : model.getLines()) {
            String name = line.matchedMaterialId() == null
                    ? null
                    : namesById.get(line.matchedMaterialId());
            resolved.add(new ProcurementLineModel(
                    line.lineNo(), line.rawName(), line.quantity(), line.unitHint(),
                    line.unitPrice(), line.lineTotal(), line.manufacturerHint(),
                    line.matchedMaterialId(), name, line.matchTier(), line.matchPercent(),
                    line.matchStatus()));
        }
        model.setLines(resolved);
    }

    /** Matched-material display names by material id for a project ({@code null} project → empty). */
    Map<Long, String> matchedNamesById(Long projectId) {
        Map<Long, String> byId = new HashMap<>();
        if (projectId == null) {
            return byId;
        }
        for (PlannedMaterial candidate : candidateProvider.candidatesFor(projectId)) {
            if (candidate == null || candidate.materialId() == null) {
                continue;
            }
            byId.put(candidate.materialId(), primaryName(candidate));
        }
        return byId;
    }

    private static String primaryName(PlannedMaterial candidate) {
        if (candidate.names() == null) {
            return null;
        }
        for (String name : candidate.names()) {
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return null;
    }
}
