package com.foremen.controller.model;

import java.util.List;

/**
 * FOR-07-03 (Requirements 4.1, 4.5, 4.7) - the single-document read: the document row (with signed
 * raw / processed image URLs) and its ordered line rows.
 */
public record CompanyDocumentDetailDto(CompanyDocumentRowDto document, List<CompanyLineRowDto> lines) {
}
