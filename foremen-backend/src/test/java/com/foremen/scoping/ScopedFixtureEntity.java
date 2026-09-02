package com.foremen.scoping;

import com.foremen.dao.model.BaseEntity;
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
 * Test-only project-scoped {@code @Entity} used to prove the FOR-03-04 project-scoping mechanism
 * end to end, since no real project-scoped entity exists yet (they arrive in FOR-06). It carries
 * BOTH shapes of project-id path so a {@link com.foremen.service.ProjectScopedService} can be
 * exercised over each:
 *
 * <ul>
 *     <li>a plain {@code projectId} column — the single-segment path {@code "projectId"}
 *         (Requirement 7.1); and</li>
 *     <li>a {@code @ManyToOne} association to {@link ScopedFixtureProject} — the dotted path
 *         {@code "project.id"} that reaches the project id through a JPA join (Requirement 7.2).</li>
 * </ul>
 *
 * <p>Kept under {@code src/test} so it is never packaged into production sources. Its schema is
 * created automatically by Hibernate {@code create-drop} under
 * {@code @ActiveProfiles("integration-test")} (Requirements 6.4, 6.10).
 */
@Entity
@Table(name = "scoped_fixture_entity")
@Getter
@Setter
@NoArgsConstructor
public class ScopedFixtureEntity extends BaseEntity {

    @Column(name = "label")
    private String label;

    /** Single-segment project-id path: {@code "projectId"} (Requirement 7.1). */
    @Column(name = "project_id")
    private Long projectId;

    /** Dotted project-id path: {@code "project.id"} reached through a join (Requirement 7.2). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_ref_id")
    private ScopedFixtureProject project;
}
