package com.foremen.scoping;

import com.foremen.dao.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Test-only stand-in for the real {@code projects} table (which arrives in FOR-06). It exists solely
 * so a {@link ScopedFixtureEntity} can reach a project id through an association and exercise the
 * dotted-path ({@code project.id}) branch of
 * {@link com.foremen.service.ProjectScopedService#addRequiredQuery()} (Requirements 6.10, 7.2).
 *
 * <p>Kept under {@code src/test} so it never leaks into production sources. Its schema is created
 * automatically by Hibernate {@code create-drop} under {@code @ActiveProfiles("integration-test")},
 * matching how {@code ProjectMemberDaoIntegrationTest} obtains its schema.
 *
 * <p>The entity's own {@code id} (from {@link BaseEntity}) is the "project id" reached via the
 * {@code project.id} path.
 */
@Entity
@Table(name = "scoped_fixture_project")
@Getter
@Setter
@NoArgsConstructor
public class ScopedFixtureProject extends BaseEntity {

    @Column(name = "title")
    private String title;
}
