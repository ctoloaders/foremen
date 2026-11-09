package com.foremen.dao.model;

import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-10 (task 1.1) — a shared dashboard widget library entry. Maps {@code dashboard_widgets}
 * (changeset {@code 219-create-dashboard-tables.xml}). {@code config} is the declarative, versioned
 * Widget_Config held as a generic JSON structure.
 */
@Entity
@Table(name = "dashboard_widgets")
@Getter
@Setter
@NoArgsConstructor
public class DashboardWidgetEntity extends BaseEntity {

    /** Unique, immutable code, {@code ^[A-Z][A-Z0-9_]{2,63}$}. */
    @Column(name = "code", nullable = false, length = 64, updatable = false)
    private String code;

    @Column(name = "name_ru", nullable = false, length = 255)
    private String nameRu;

    @Column(name = "name_pl", nullable = false, length = 255)
    private String namePl;

    @Column(name = "description_ru", length = 1000)
    private String descriptionRu;

    @Column(name = "description_pl", length = 1000)
    private String descriptionPl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> config;

    /** Equals {@code config.schemaVersion}. */
    @Column(name = "config_version", nullable = false)
    private Integer configVersion;

    /** Grid columns occupied, 1..4. */
    @Column(name = "col_span", nullable = false)
    private Short colSpan;

    /** Grid rows occupied, 1..9. */
    @Column(name = "row_span", nullable = false)
    private Short rowSpan;

    /** {@code NONE} or {@code SINGLE_PROJECT}. */
    @Column(name = "project_scope", nullable = false, length = 16)
    private String projectScope = "NONE";

    @Column(name = "active", nullable = false)
    private Boolean active = Boolean.TRUE;

    @Column(name = "system_seed", nullable = false)
    private Boolean systemSeed = Boolean.FALSE;
}
