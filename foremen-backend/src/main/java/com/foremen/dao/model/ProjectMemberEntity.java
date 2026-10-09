package com.foremen.dao.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "project_members",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_project_members_user_project",
                columnNames = {"user_id", "project_id"}))
@Getter
@Setter
@NoArgsConstructor
public class ProjectMemberEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    // Plain BIGINT: the projects table does not exist in this spec (arrives in FOR-06), so no FK/association.
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_role_id", nullable = false)
    private RoleEntity projectRole;

    /**
     * FOR-05-09 (R14, D14) — the worker hiring type (FOR-05-06 dictionary). WORKER members only;
     * null = an Uncategorized_Worker. The FK is {@code ON DELETE RESTRICT} (changeset 150) so a
     * referenced worker type cannot be hard-deleted, only deactivated.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "worker_type_id")
    private WorkerTypeEntity workerType;

    /**
     * FOR-05-09 (R27, D14) — ACTIVE / INACTIVE. NOT NULL; a new member starts ACTIVE.
     * Only ACTIVE members count toward readiness and the last-ACTIVE-MANAGER/CLIENT invariants.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_status", nullable = false)
    private AssignmentStatus assignmentStatus = AssignmentStatus.ACTIVE;

    /**
     * FOR-05-09 (R15, D11) — the ordered 0–10 free-text tag list, mapped from the
     * {@code project_member_tags} child table. {@code @OrderColumn} preserves the submitted order;
     * {@code ON DELETE CASCADE} (changeset 151) discards the tags when the membership is removed.
     */
    @ElementCollection
    @CollectionTable(
            name = "project_member_tags",
            joinColumns = @JoinColumn(name = "project_member_id"))
    @OrderColumn(name = "order_no")
    @Column(name = "tag", length = 50, nullable = false)
    private List<String> tags = new ArrayList<>();
}
