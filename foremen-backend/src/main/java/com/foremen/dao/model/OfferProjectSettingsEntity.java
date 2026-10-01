package com.foremen.dao.model;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-05-07 (Requirement 6.4): a per-project ESCALATION OVERRIDE for the {@code Escalation_Threshold}
 * that gates a {@code MANAGER_PROPOSAL} requiring ADMIN approval. Maps the
 * {@code offer_project_settings} table (changeset {@code 132-create-offer-project-settings.xml}).
 *
 * <p>This row holds ONLY the per-project override; the GLOBAL default lives in application config
 * ({@code foremen.offer.escalation.percent-cap} / {@code ...absolute-cap}), not here. The owner FK
 * {@link #project} is UNIQUE — at most one settings row per project — and ON DELETE CASCADE at the DB
 * level. A null cap means "fall back to the GLOBAL config value" (R6.4).
 */
@Entity
@Table(name = "offer_project_settings")
@Getter
@Setter
@NoArgsConstructor
public class OfferProjectSettingsEntity extends BaseEntity {

    /** Owner FK to the project, NOT NULL and UNIQUE — one override row per project. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, unique = true)
    private ProjectEntity project;

    /** Per-project percent cap override; null ⇒ GLOBAL config default (R6.4). */
    @Column(name = "escalation_percent_cap", precision = 6, scale = 4)
    private BigDecimal escalationPercentCap;

    /** Per-project absolute cap override; null ⇒ GLOBAL config default (R6.4). */
    @Column(name = "escalation_absolute_cap", precision = 14, scale = 2)
    private BigDecimal escalationAbsoluteCap;
}
