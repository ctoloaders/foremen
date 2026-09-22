package com.foremen.dao.model;

import com.foremen.dao.model.formula.FormulaAst;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A {@code (WorkItem, OfferPackage)} pair's package membership flag and optional
 * package-specific volume formula override (FOR-05-04 Requirement 4; design §Component 3).
 *
 * <p>Replaces the retired per-package price row ({@code WorkPackagePriceEntity}) as the
 * mechanism for "this work behaves differently in package X" — this entity carries
 * <strong>no price</strong> (Requirement 4.5); the work's single catalog price
 * ({@code WorkPriceEntity}) is independent of package membership.
 *
 * <p>The "present override ⇒ member" rule (Requirement 4.2) and validating
 * {@link #overrideSourceText} through the formula parser + validator on write (Requirement 4.4,
 * mirroring {@code WorkVolumeFormulaEntity}'s Requirement 2.6 contract) are
 * <strong>service-layer</strong> responsibilities (tasks 15.1/15.2/18.3) — they are not, and
 * cannot be, enforced by the JPA annotations on this entity alone.
 */
@Entity
@Table(name = "work_package_overrides")
@Getter
@Setter
@NoArgsConstructor
public class WorkPackageOverrideEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_item_id", nullable = false)
    private WorkItemEntity workItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "offer_package_id", nullable = false)
    private OfferPackageEntity offerPackage;

    /** Package membership flag (Requirement 4.1, 4.2); defaults to {@code true} at the DB level. */
    @Column(name = "member", nullable = false)
    private Boolean member;

    /** Human-readable override formula source text; nullable — flag-only membership is allowed (R4.1, R4.3). */
    @Column(name = "override_source_text", columnDefinition = "text")
    private String overrideSourceText;

    /** The validated parsed override formula; present iff {@link #overrideSourceText} is present. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "override_parsed_ast", columnDefinition = "jsonb")
    private FormulaAst overrideParsedAst;
}
