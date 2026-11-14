package com.foremen.dao;

import com.foremen.dao.model.PositionApprovalOptionEntity;
import org.springframework.stereotype.Repository;

/** FOR-11-02: options of approval rounds. Client reads go through {@code ApprovalQueryService}. */
@Repository
public interface PositionApprovalOptionDao extends AdminDao<PositionApprovalOptionEntity, Long> {

    /** Options of one round in display order. */
    java.util.List<PositionApprovalOptionEntity> findByRoundIdOrderBySortOrderAscIdAsc(Long roundId);
}
