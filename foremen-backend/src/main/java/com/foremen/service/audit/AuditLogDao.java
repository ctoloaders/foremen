package com.foremen.service.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditLogDao extends JpaRepository<AuditLogEntity, Long> {

    List<AuditLogEntity> findByEntityClassAndEntityIdOrderByPerformedAtAsc(String entityClass, Long entityId);
}
