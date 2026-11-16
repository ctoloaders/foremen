package com.foremen.controller.model;

/**
 * Body of {@code PUT /api/projects/{id}/bank-account} (FOR-11-06 R4.2): the payee IBAN, or
 * {@code null} / blank to clear the project account.
 */
public record ProjectBankAccountRequest(String bankAccount) {}
