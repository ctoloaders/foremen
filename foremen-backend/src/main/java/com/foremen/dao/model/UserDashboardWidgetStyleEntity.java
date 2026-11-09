package com.foremen.dao.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** FOR-10 (task 1.1) — per-placement style override. Maps {@code user_dashboard_widget_style}. */
@Entity
@Table(name = "user_dashboard_widget_style")
@Getter
@Setter
@NoArgsConstructor
public class UserDashboardWidgetStyleEntity {

    @Id
    @Column(name = "placement_id", nullable = false)
    private Long placementId;

    @Column(name = "style", nullable = false, length = 32)
    private String style;
}
