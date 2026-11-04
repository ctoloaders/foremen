package com.foremen.controller.bank.dto;

import java.util.List;

import com.foremen.dao.model.FeedProvider;
import com.foremen.dao.model.SaltEdgeMode;
import com.foremen.service.bank.connector.CredentialField;

/**
 * FOR-07-04 (Requirements 2.2, 4.5, 19.4) — an available bank-feed provider for the "Add bank"
 * dialog: the Salt Edge mode (when applicable) and the per-connection credential field descriptors
 * (names / label keys only — never values).
 */
public record BankProviderDto(
        FeedProvider provider,
        SaltEdgeMode mode,
        boolean available,
        boolean credentialsRequired,
        List<CredentialField> credentialFields) {
}
