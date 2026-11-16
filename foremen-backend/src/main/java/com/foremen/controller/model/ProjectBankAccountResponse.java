package com.foremen.controller.model;

/**
 * Response of {@code PUT /api/projects/{id}/bank-account} (FOR-11-06 R4.2): the stored, normalised
 * project IBAN ({@code null} when cleared).
 */
public record ProjectBankAccountResponse(Long projectId, String bankAccount) {}
