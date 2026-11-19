package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
public class ProjectEntity extends BaseEntity {

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 500)
    private String address;

    @Column(name = "google_place_id", length = 255)
    private String googlePlaceId;

    @Column(name = "formatted_address", length = 500)
    private String formattedAddress;

    @Column(precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(precision = 12, scale = 2)
    private BigDecimal area;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    /**
     * FOR-11-06 (R4.1): optional payee IBAN, normalised (upper case, no spaces). Written only by
     * {@code ProjectBankAccountService}; deliberately absent from the service models, so the generic
     * project update neither reads nor overwrites it.
     */
    @Column(name = "bank_account", length = 34)
    private String bankAccount;

    /**
     * Project-level VAT rate (reference data, FOR-04-04). Drives the offer totals (net / VAT /
     * gross). New projects default to the 8% rate; see {@code ProjectService#createProject}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vat_rate_id")
    private VatRateEntity vatRate;

    /**
     * FOR-05-04 (additional tasks): whether an offer package is applied to the project's estimate.
     * With a package the estimate total is package price per m2 x apartment area + extra works
     * beyond the package; without one it is the price-list calculation. Set / cleared only by the
     * estimate apply / remove package path (same transaction as the matrix change).
     */
    @Column(name = "package_applied", nullable = false)
    private boolean packageApplied = false;

    /** The applied offer package (FOR-05-04); {@code null} while {@link #packageApplied} is false. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "offer_package_id")
    private OfferPackageEntity offerPackage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectStatus status = ProjectStatus.DRAFT;

    /**
     * Read-only team association. The {@code project_members} table carries a plain
     * {@code project_id} column (no owning FK side there), so this side is mapped by that column
     * and is NOT the write path. Members are created only through
     * {@code ProjectMemberService.assign(...)}; this collection is populated for LIST/read
     * projection and traversed by {@code SpecificationBuilder} joins for nested filtering, but is
     * never mutated through {@code ProjectEntity}. {@code insertable=false, updatable=false}
     * guarantees Hibernate never writes this side; {@code @BatchSize} bounds member hydration to
     * one extra query per list page rather than one per row.
     */
    @OneToMany(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", referencedColumnName = "id",
            insertable = false, updatable = false)
    @BatchSize(size = 100)
    private List<ProjectMemberEntity> members = new ArrayList<>();
}
