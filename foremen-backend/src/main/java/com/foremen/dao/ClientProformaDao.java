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

    /** FOR-11-06 (R1.8): the MATERIALS detail of a unified client payment (ledger addressing by payment id). */
    @Query("SELECT p FROM ClientProformaEntity p WHERE p.payment.id = :paymentId")
    java.util.Optional<ClientProformaEntity> findByClientPaymentId(@Param("paymentId") Long paymentId);

    /** FOR-11-06 (R13.4): the unified client payment id of a proforma (notification deep links), or null. */
    @Query("SELECT p.payment.id FROM ClientProformaEntity p WHERE p.id = :proformaId")
    Long findClientPaymentId(@Param("proformaId") Long proformaId);

    /** Ledger matching (R10.1) / search (R10.5): proformas in the given statuses with the project fetched. */
    default List<ClientProformaEntity> findByStatusInWithProject(Collection<ProformaStatus> statuses) {
        return findByStatusNamesInWithProject(statuses.stream().map(Enum::name).toList());
    }

    @Query("SELECT p FROM ClientProformaEntity p JOIN FETCH p.project WHERE p.payment.status IN :statuses ORDER BY p.id")
    List<ClientProformaEntity> findByStatusNamesInWithProject(@Param("statuses") Collection<String> statuses);

    /** Client surface (R6.1, R6.2): the project's proformas in the given statuses, newest first. */
    default List<ClientProformaEntity> findByProjectIdAndStatusIn(Long projectId,
                                                                  Collection<ProformaStatus> statuses) {
        return findByProjectIdAndStatusNamesIn(projectId, statuses.stream().map(Enum::name).toList());
    }

    @Query("SELECT p FROM ClientProformaEntity p WHERE p.project.id = :projectId AND p.payment.status IN :statuses "
            + "ORDER BY p.id DESC")
    List<ClientProformaEntity> findByProjectIdAndStatusNamesIn(@Param("projectId") Long projectId,
                                                               @Param("statuses") Collection<String> statuses);
}
