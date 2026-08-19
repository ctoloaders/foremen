package com.foremen.service.audit;

import com.foremen.dao.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "audit_log")
@Getter
@Setter
public class AuditLogEntity extends BaseEntity {

    @Column(name = "entity_class", nullable = false, length = 255)
    private String entityClass;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "operation", nullable = false, length = 50)
    private String operation;

    @Column(name = "performed_by", nullable = false, length = 255)
    private String performedBy;

    @Column(name = "performed_at", nullable = false)
    private LocalDateTime performedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot_before", columnDefinition = "jsonb")
    private String snapshotBefore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot_after", columnDefinition = "jsonb")
    private String snapshotAfter;
}
