package com.foremen.dao.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** FOR-10 (task 1.1) — marks that a user's default dashboard was applied. Maps {@code user_dashboard_state}. */
@Entity
@Table(name = "user_dashboard_state")
@Getter
@Setter
@NoArgsConstructor
public class UserDashboardStateEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "initialized_at", nullable = false)
    private LocalDateTime initializedAt;
}
