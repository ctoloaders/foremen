package com.foremen.dao;

import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.service.procurement.proforma.ProformaStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** FOR-11-03 (R2.1): client proformas. */
@Repository
public interface ClientProformaDao extends AdminDao<ClientProformaEntity, Long> {

    boolean existsByNumber(String number);

    /** Ledger matching (R10.1) / search (R10.5): proformas in the given statuses with the project fetched. */
    @Query("SELECT p FROM ClientProformaEntity p JOIN FETCH p.project WHERE p.status IN :statuses ORDER BY p.id")
    List<ClientProformaEntity> findByStatusInWithProject(@Param("statuses") Collection<ProformaStatus> statuses);

    /** Client surface (R6.1, R6.2): the project's proformas in the given statuses, newest first. */
    @Query("SELECT p FROM ClientProformaEntity p WHERE p.project.id = :projectId AND p.status IN :statuses "
            + "ORDER BY p.id DESC")
    List<ClientProformaEntity> findByProjectIdAndStatusIn(@Param("projectId") Long projectId,
                                                          @Param("statuses") Collection<ProformaStatus> statuses);
}
