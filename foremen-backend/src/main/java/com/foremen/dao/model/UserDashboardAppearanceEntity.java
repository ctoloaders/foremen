package com.foremen.dao.model;

import java.time.LocalDateTime;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** FOR-10 (task 1.1) — per-user dashboard palette. Maps {@code user_dashboard_appearance}. */
@Entity
@Table(name = "user_dashboard_appearance")
@Getter
@Setter
@NoArgsConstructor
public class UserDashboardAppearanceEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Ordered 1..8 {@code #RRGGBB} strings; {@code null} = follow the theme. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "palette", columnDefinition = "jsonb")
    private List<String> palette;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
