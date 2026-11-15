package com.foremen.dao;

import com.foremen.dao.model.ClientProformaLineEntity;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** FOR-11-03 (R2.2, R2.4): frozen proforma lines. */
@Repository
public interface ClientProformaLineDao extends AdminDao<ClientProformaLineEntity, Long> {

    /** True when the position sits on a live (draft or issued) proforma. */
    boolean existsByPositionIdAndActiveTrue(Long positionId);

    /** Live line of the position, if any. */
    @Query("SELECT l FROM ClientProformaLineEntity l WHERE l.position.id = :positionId AND l.active = true")
    List<ClientProformaLineEntity> findActiveByPositionId(@Param("positionId") Long positionId);

    /** Lines of one proforma, in line order, with the position fetched. */
    @Query("SELECT l FROM ClientProformaLineEntity l JOIN FETCH l.position "
            + "WHERE l.proforma.id = :proformaId ORDER BY l.lineNo")
    List<ClientProformaLineEntity> findByProformaIdOrderByLineNo(@Param("proformaId") Long proformaId);

    /** Position ids that already have a live line, among the given ones. */
    @Query("SELECT l.position.id FROM ClientProformaLineEntity l "
            + "WHERE l.position.id IN :positionIds AND l.active = true")
    List<Long> findActivePositionIds(@Param("positionIds") Collection<Long> positionIds);

    /** List totals: rows of {@code [proformaId, lineCount]} for the given proformas. */
    @Query("SELECT l.proforma.id, COUNT(l) FROM ClientProformaLineEntity l "
            + "WHERE l.proforma.id IN :proformaIds GROUP BY l.proforma.id")
    List<Object[]> countLinesByProformaIds(@Param("proformaIds") Collection<Long> proformaIds);

    /** List totals: rows of {@code [proformaId, sumNet, sumVat, sumGross]} for the given proformas. */
    @Query("SELECT l.proforma.id, SUM(l.net), SUM(l.vat), SUM(l.gross) FROM ClientProformaLineEntity l "
            + "WHERE l.proforma.id IN :proformaIds GROUP BY l.proforma.id")
    List<Object[]> sumTotalsByProformaIds(@Param("proformaIds") Collection<Long> proformaIds);

    /** R24.5: ACTIVE lines of the given positions with their proforma fetched (one batch query). */
    @Query("SELECT l FROM ClientProformaLineEntity l JOIN FETCH l.proforma "
            + "WHERE l.position.id IN :positionIds AND l.active = true")
    List<ClientProformaLineEntity> findActiveWithProformaByPositionIds(
            @Param("positionIds") Collection<Long> positionIds);

    /** Releases the positions of a cancelled proforma (R2.4). */
    @Modifying
    @Query("UPDATE ClientProformaLineEntity l SET l.active = false WHERE l.proforma.id = :proformaId")
    int deactivateByProformaId(@Param("proformaId") Long proformaId);
}
