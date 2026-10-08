package com.foremen.dao.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-05-08 (Requirement 3.3, design decision 7 / Open Question 1): the CompanyProfile reference —
 * the executor ({@code Wykonawca}) requisites source read by {@code CompanyRequisitesResolver} when
 * merging a document body. Maps the {@code company_profile} table (changeset
 * {@code 140-create-company-profile.xml}).
 *
 * <p>A single admin-managed row (global reference, admin-only, standard CRUD). The "single-row"
 * intent is a service-layer invariant (the resolver reads the one row); the schema stays a plain
 * table.
 */
@Entity
@Table(name = "company_profile")
@Getter
@Setter
@NoArgsConstructor
public class CompanyProfileEntity extends BaseEntity {

    /** Company legal/full name, NOT NULL. */
    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    /** Registered seat address, NOT NULL. */
    @Column(name = "registered_address", nullable = false, length = 512)
    private String registeredAddress;

    /** Tax id, NOT NULL. */
    @Column(name = "nip", nullable = false, length = 32)
    private String nip;

    /** Statistical id; nullable. */
    @Column(name = "regon", length = 32)
    private String regon;

    /** Signing representative name; nullable. */
    @Column(name = "representative_name", length = 255)
    private String representativeName;

    /** Representative's role/title; nullable. */
    @Column(name = "representative_role", length = 255)
    private String representativeRole;

    /** Contact email; nullable. */
    @Column(name = "email", length = 255)
    private String email;
}
