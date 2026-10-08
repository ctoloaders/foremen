package com.foremen.service.document.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.foremen.config.mapper.ForemenMapperConfig;
import com.foremen.dao.model.DocumentFormFieldEntity;
import com.foremen.dao.model.DocumentMediaEntity;
import com.foremen.dao.model.DocumentSignatureEntity;
import com.foremen.dao.model.DocumentTemplateEntity;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.service.document.DocumentMediaSummaryDto;
import com.foremen.service.document.DocumentSignatureDto;
import com.foremen.service.document.DocumentTemplateDto;
import com.foremen.service.document.FormFieldDto;
import com.foremen.service.document.SignableDocumentDto;

/**
 * FOR-05-08 (Requirements 4.5, 9.6, 13.1, 13.3): MapStruct read mapper from the signable-document
 * entity graph to the client-reachable DTOs of {@code com.foremen.service.document}. Spring component
 * (via {@link ForemenMapperConfig}); builder-less, so records are populated through their canonical
 * constructors.
 *
 * <p><b>Confidentiality (R9.6, R13.3):</b> every target type here is client-reachable and carries no
 * cost/estimate/margin field — the mapper copies only the identity/lifecycle facts of each entity,
 * and the originating object is exposed only as the opaque {@code sourceRef} string.
 *
 * <p>{@link SignableDocumentDto#progress()} is deliberately mapped to {@code null}: the aggregate
 * {@code SigningProgressDto} is a pure projection produced by {@code SigningProgressCalculator} (task
 * 3.3) and wired in by {@code SignableDocumentService}, not derived during entity mapping.
 */
@Mapper(config = ForemenMapperConfig.class)
public interface DocumentDtoMapper {

    @Mapping(target = "projectId", source = "project.id")
    @Mapping(target = "documentTypeCode", source = "documentType.code")
    @Mapping(target = "createdAt", source = "createdDate")
    @Mapping(target = "progress", ignore = true)
    SignableDocumentDto toDto(SignableDocumentEntity entity);

    @Mapping(target = "signerUserId", source = "signerUser.id")
    DocumentSignatureDto toDto(DocumentSignatureEntity entity);

    List<DocumentSignatureDto> toSignatureDtos(List<DocumentSignatureEntity> entities);

    DocumentMediaSummaryDto toDto(DocumentMediaEntity entity);

    List<DocumentMediaSummaryDto> toMediaDtos(List<DocumentMediaEntity> entities);

    FormFieldDto toDto(DocumentFormFieldEntity entity);

    List<FormFieldDto> toFormFieldDtos(List<DocumentFormFieldEntity> entities);

    @Mapping(target = "documentTypeId", source = "documentType.id")
    @Mapping(target = "documentTypeCode", source = "documentType.code")
    @Mapping(target = "uploadedById", source = "uploadedBy.id")
    @Mapping(target = "uploadedAt", source = "createdDate")
    DocumentTemplateDto toDto(DocumentTemplateEntity entity);
}
