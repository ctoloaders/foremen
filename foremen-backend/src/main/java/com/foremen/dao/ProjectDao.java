package com.foremen.dao;

import java.util.Collection;
import java.util.List;

import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import org.springframework.stereotype.Repository;

@Repository
public interface ProjectDao extends AdminDao<ProjectEntity, Long> {

    /** FOR-07-04 (Requirement 15.2) — projects in the given statuses (AI classifier project list), by id. */
    List<ProjectEntity> findByStatusInOrderByIdAsc(Collection<ProjectStatus> statuses);

    /** FOR-11-00 — every project id (BUYER company-wide procurement access). */
    @org.springframework.data.jpa.repository.Query("SELECT p.id FROM ProjectEntity p")
    List<Long> findAllIds();

    /** VAT rates with the given rate value (percent), lowest id first; used to resolve the default project rate. */
    @org.springframework.data.jpa.repository.Query(
            "SELECT v FROM VatRateEntity v WHERE v.rate = :rate ORDER BY v.id")
    List<com.foremen.dao.model.VatRateEntity> findVatRatesByRate(
            @org.springframework.data.repository.query.Param("rate") java.math.BigDecimal rate);
}
