package com.foremen.dao;

import com.foremen.service.audit.AuditLogEntity;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditReadOnlyDao extends ReadOnlyAdminDao<AuditLogEntity, Long> {
}
