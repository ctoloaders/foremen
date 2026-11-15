package com.foremen.service.procurement.proforma;

import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;

/**
 * FOR-11-03 (R5.3): the "no ledger link" check of the cancel flow. A minimal native query on
 * {@code bank_transaction_links.client_proforma_id} (changeset 238), kept apart so the proforma link
 * kinds of the bank-link DAO (task 5.1) can replace it with {@code existsByClientProformaId}.
 */
@Component
public class ProformaLinkLookup {

    private final EntityManager entityManager;

    public ProformaLinkLookup(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** True when at least one bank-ledger link points at the proforma. */
    public boolean hasLinks(Long proformaId) {
        Object count = entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM bank_transaction_links WHERE client_proforma_id = :id")
                .setParameter("id", proformaId)
                .getSingleResult();
        return count instanceof Number n && n.longValue() > 0;
    }
}
