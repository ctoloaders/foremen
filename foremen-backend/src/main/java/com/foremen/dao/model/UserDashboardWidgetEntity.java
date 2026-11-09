package com.foremen.dao.model;

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
 * FOR-10 (task 1.1) — a widget placement on a user's dashboard. Maps {@code user_dashboard_widgets}.
 * Only {@code cellIndex} (0..35) is stored; spans come from the widget.
 */
@Entity
@Table(name = "user_dashboard_widgets")
@Getter
@Setter
@NoArgsConstructor
public class UserDashboardWidgetEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "widget_id", nullable = false)
    private DashboardWidgetEntity widget;

    @Column(name = "cell_index", nullable = false)
    private Short cellIndex;

    /** Bound project for a SINGLE_PROJECT widget; {@code null} otherwise. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private ProjectEntity project;
}
