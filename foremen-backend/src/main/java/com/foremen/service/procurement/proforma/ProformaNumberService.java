package com.foremen.service.procurement.proforma;

import java.time.LocalDate;

import org.springframework.stereotype.Service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-11-03 (R2.5): hands out proforma numbers {@code PF/{yyyy}/{seq:6}} from the global database
 * sequence {@code client_proforma_number_seq}. The sequence is gap-tolerant: a rolled-back issue burns
 * its value, which is acceptable for an advance-payment document.
 */
@Service
public class ProformaNumberService {

    static final String SEQUENCE = "client_proforma_number_seq";

    @PersistenceContext
    private EntityManager entityManager;

    /** Next number, with the year taken from {@code issueDate}. */
    public String next(LocalDate issueDate) {
        Number seq = (Number) entityManager
                .createNativeQuery("SELECT nextval('" + SEQUENCE + "')")
                .getSingleResult();
        return ProformaNumberFormatter.format(issueDate.getYear(), seq.longValue());
    }
}
