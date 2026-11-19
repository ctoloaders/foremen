package com.foremen.dao.model;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A one-way link {@code workItem -> relatedWorkItem} (FOR-04-11 additional tasks 3-5). Selecting the
 * parent in the estimate also adds the related work. One level only: the related work's own links are
 * never followed. The link is set explicitly on the parent; there is no inverse or transitive link.
 */
@Entity
@Table(name = "work_related_works")
@Getter
@Setter
@NoArgsConstructor
public class WorkRelatedWorkEntity extends BaseEntity {

    /** The parent work the link is set on. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_item_id", nullable = false)
    private WorkItemEntity workItem;

    /** The work added together with the parent. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "related_work_item_id", nullable = false)
    private WorkItemEntity relatedWorkItem;
}
