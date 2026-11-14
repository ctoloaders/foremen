package com.foremen.controller.model;

import com.foremen.dao.model.ProjectStatus;

/** FOR-11-00 (R9.8): one row of {@code GET /api/procurement/projects}. */
public record ProcurementProjectDto(Long id, String name, String address, ProjectStatus status) {
}
