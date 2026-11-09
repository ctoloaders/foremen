package com.foremen.dao.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** FOR-10 (task 1.1) — default widget placement of a role. Maps {@code role_default_widgets}. */
@Entity
@Table(name = "role_default_widgets")
@IdClass(RoleDefaultWidgetId.class)
@Getter
@Setter
@NoArgsConstructor
public class RoleDefaultWidgetEntity {

    @Id
    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Id
    @Column(name = "cell_index", nullable = false)
    private Short cellIndex;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "widget_id", nullable = false)
    private DashboardWidgetEntity widget;
}
