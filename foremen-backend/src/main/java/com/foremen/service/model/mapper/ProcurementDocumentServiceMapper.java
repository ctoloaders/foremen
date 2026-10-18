package com.foremen.service.model.mapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.controller.model.ProcurementLineModel;
import com.foremen.dao.model.MatchTier;
import com.foremen.dao.model.ProcurementDocumentEntity;
import com.foremen.dao.model.ProcurementLineEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.ProcurementDocumentServiceExtendedModel;
import com.foremen.service.model.ProcurementDocumentServiceModel;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-03 (Requirements 5.8, 10.1; design §5.6) — service mapper for
 * {@link ProcurementDocumentEntity}, following the {@code WorkReportServiceMapper} FK-resolution
 * pattern: an <b>abstract class</b> holding an injected {@link EntityManager} so the flat
 * {@code projectId} becomes a managed reference via {@code getReference(...)} without a SELECT.
 *
 * <p><b>Write path.</b> Procurement intake is file-driven — a document is created by
 * {@code upload(projectId, MultipartFile)}, never by a JSON body — so {@code toCreateDaoModel}/
 * {@code updateFields} set only the {@code project} reference. Everything else
 * ({@code uploadedBy}, the object keys, {@code sourceMimeType}, {@code status}, {@code uploadedAt}/
 * {@code parsedAt}, the parse-derived {@code supplierName}/{@code documentTotal}, the
 * {@code failureReason}, and the owned {@code lines}) is derived server-side by
 * {@code ProcurementDocumentService}, so those targets are {@code ignore}d here.
 *
 * <p><b>Read path.</b> {@code toServiceModel}/{@code toServiceExtendedModel} map the flat FK ids and
 * the lifecycle/parse scalars by name; {@link #resolveReadModel}/{@link #resolveExtendedReadModel}
 * then project the ordered per-line split into {@link ProcurementLineModel} rows, computing the
 * derived tier percent (100 / 80 / 50 / 0, R5.8) from the persisted {@link MatchTier}. The
 * uploader's display name, the resolved raw + processed image URLs, and each line's matched-material
 * localized name are filled by {@code ProcurementDocumentService} (they need the uploader entity,
 * {@code ImageStorage} URL resolution, and the FOR-05-05b candidate projection respectively), so
 * they are left unset here.
 */
@Mapper(config = ForemenMapperConfig.class)
public abstract class ProcurementDocumentServiceMapper
        implements ServiceToDaoMapper<ProcurementDocumentEntity, ProcurementDocumentServiceModel,
        ProcurementDocumentServiceExtendedModel> {

    @Autowired
    protected EntityManager entityManager;

    protected ProjectEntity projectRef(Long id) {
        return id == null ? null : entityManager.getReference(ProjectEntity.class, id);
    }

    /**
     * The derived tier percent — a presentational label mapped from the persisted tier, never a
     * free continuous score (R5.8): {@code EXACT}=100, {@code MANUFACTURER_CATEGORY}=80,
     * {@code CATEGORY}=50, {@code NONE}=0.
     */
    public static int tierPercent(MatchTier tier) {
        if (tier == null) {
            return 0;
        }
        return switch (tier) {
            case EXACT -> 100;
            case MANUFACTURER_CATEGORY -> 80;
            case CATEGORY -> 50;
            case NONE -> 0;
        };
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Set.of();
    }

    // --- Write path ------------------------------------------------------------------------------

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "project", expression = "java(projectRef(source.getProjectId()))")
    @Mapping(target = "uploadedBy", ignore = true)
    @Mapping(target = "rawObjectKey", ignore = true)
    @Mapping(target = "processedObjectKey", ignore = true)
    @Mapping(target = "sourceMimeType", ignore = true)
    @Mapping(target = "supplierName", ignore = true)
    @Mapping(target = "documentTotal", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "uploadedAt", ignore = true)
    @Mapping(target = "parsedAt", ignore = true)
    @Mapping(target = "failureReason", ignore = true)
    @Mapping(target = "lines", ignore = true)
    public abstract ProcurementDocumentEntity toCreateDaoModel(
            ProcurementDocumentServiceExtendedModel source);

    @Override
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "project", ignore = true)
    @Mapping(target = "uploadedBy", ignore = true)
    @Mapping(target = "rawObjectKey", ignore = true)
    @Mapping(target = "processedObjectKey", ignore = true)
    @Mapping(target = "sourceMimeType", ignore = true)
    @Mapping(target = "supplierName", ignore = true)
    @Mapping(target = "documentTotal", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "uploadedAt", ignore = true)
    @Mapping(target = "parsedAt", ignore = true)
    @Mapping(target = "failureReason", ignore = true)
    @Mapping(target = "lines", ignore = true)
    public abstract void updateFields(ProcurementDocumentServiceExtendedModel source,
                                      @MappingTarget ProcurementDocumentEntity target);

    // --- Read path -------------------------------------------------------------------------------

    @Override
    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "uploadedById", source = "uploadedBy.id")
    @Mapping(target = "uploaderName", ignore = true)
    @Mapping(target = "rawImageUrl", ignore = true)
    @Mapping(target = "processedImageUrl", ignore = true)
    @Mapping(target = "lines", ignore = true)
    public abstract ProcurementDocumentServiceModel toServiceModel(ProcurementDocumentEntity source);

    @Override
    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "uploadedById", source = "uploadedBy.id")
    @Mapping(target = "uploaderName", ignore = true)
    @Mapping(target = "rawImageUrl", ignore = true)
    @Mapping(target = "processedImageUrl", ignore = true)
    @Mapping(target = "lines", ignore = true)
    public abstract ProcurementDocumentServiceExtendedModel toServiceExtendedModel(
            ProcurementDocumentEntity source);

    /**
     * Projects the ordered per-line split into {@link ProcurementLineModel} rows with the derived
     * tier percent (R5.8). The matched-material localized name is left {@code null} here — the
     * service fills it from the FOR-05-05b candidate projection with the {@code ru → pl → code}
     * fallback.
     */
    @AfterMapping
    protected void resolveReadModel(@MappingTarget ProcurementDocumentServiceModel target,
                                    ProcurementDocumentEntity source) {
        target.setLines(projectLines(source));
    }

    /**
     * FOR-06-03 single-document review projection: the same per-line projection carried on the
     * extended (single-read) model so {@code GET /{id}} returns the ordered lines + derived tier
     * percent. The lifecycle/parse scalars auto-map by name from the entity.
     */
    @AfterMapping
    protected void resolveExtendedReadModel(
            @MappingTarget ProcurementDocumentServiceExtendedModel target,
            ProcurementDocumentEntity source) {
        target.setLines(projectLines(source));
    }

    private static List<ProcurementLineModel> projectLines(ProcurementDocumentEntity source) {
        List<ProcurementLineModel> rows = new ArrayList<>();
        if (source.getLines() != null) {
            for (ProcurementLineEntity line : source.getLines()) {
                rows.add(new ProcurementLineModel(
                        line.getLineNo(),
                        line.getRawName(),
                        line.getQuantity(),
                        line.getUnitHint(),
                        line.getUnitPrice(),
                        line.getLineTotal(),
                        line.getManufacturerHint(),
                        line.getMatchedMaterialId(),
                        null,
                        line.getMatchTier(),
                        tierPercent(line.getMatchTier()),
                        line.getMatchStatus()));
            }
        }
        return rows;
    }
}
