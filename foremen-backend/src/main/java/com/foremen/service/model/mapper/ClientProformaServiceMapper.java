package com.foremen.service.model.mapper;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.ClientProformaLineDao;
import com.foremen.dao.model.BankTransactionLinkEntity;
import com.foremen.service.model.ClientProformaPaymentServiceModel;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.ClientProformaLineEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.model.ClientProformaLineServiceModel;
import com.foremen.service.model.ClientProformaServiceExtendedModel;
import com.foremen.service.model.ClientProformaServiceModel;
import com.foremen.service.procurement.proforma.ProformaStatus;

import jakarta.persistence.EntityManager;

/**
 * Staff service mapper for client proformas (FOR-11-03 R2, R3). Hand-written. The extended model
 * carries the lines (one query); the list model does not. {@link #updateFields} touches only
 * {@code dueDate} and {@code note}: everything else is system-owned or immutable.
 */
@Component
public class ClientProformaServiceMapper implements ServiceToDaoMapper<ClientProformaEntity,
        ClientProformaServiceModel, ClientProformaServiceExtendedModel> {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    private final EntityManager entityManager;
    private final ClientProformaLineDao lineDao;

    private final BankTransactionLinkDao linkDao;

    public ClientProformaServiceMapper(EntityManager entityManager, ClientProformaLineDao lineDao,
                                       BankTransactionLinkDao linkDao) {
        this.entityManager = entityManager;
        this.lineDao = lineDao;
        this.linkDao = linkDao;
    }

    @Override
    public Set<String> getI18nSupportedProperties() {
        return Set.of();
    }

    @Override
    public ClientProformaServiceModel toServiceModel(ClientProformaEntity source) {
        if (source == null) {
            return null;
        }
        ClientProformaServiceModel target = new ClientProformaServiceModel();
        fill(source, target);
        return target;
    }

    @Override
    public ClientProformaServiceExtendedModel toServiceExtendedModel(ClientProformaEntity source) {
        if (source == null) {
            return null;
        }
        ClientProformaServiceExtendedModel target = new ClientProformaServiceExtendedModel();
        fill(source, target);
        List<ClientProformaLineEntity> lines = source.getId() == null
                ? List.of() : lineDao.findByProformaIdOrderByLineNo(source.getId());
        target.setLines(lines.stream().map(ClientProformaServiceMapper::toLine).toList());
        target.setPositionIds(lines.stream().map(l -> l.getPosition().getId()).toList());
        List<BankTransactionLinkEntity> links = source.getId() == null
                ? List.of() : linkDao.findProformaPaymentsByClientProformaId(source.getId());
        target.setPayments(links.stream().map(l -> new ClientProformaPaymentServiceModel(
                l.getId(), l.getTransaction().getBookingDate(), l.getAmount(), l.getTransaction().getTitle()))
                .toList());
        return target;
    }

    private static void fill(ClientProformaEntity s, ClientProformaServiceModel t) {
        t.setId(s.getId());
        if (s.getProject() != null) {
            t.setProjectId(s.getProject().getId());
            t.setProjectName(s.getProject().getName());
        }
        t.setNumber(s.getNumber());
        t.setStatus(s.getStatus());
        t.setCurrency(s.getCurrency());
        t.setIssueDate(s.getIssueDate());
        t.setDueDate(s.getDueDate());
        t.setNote(s.getNote());
        t.setTotalNet(s.getTotalNet());
        t.setTotalVat(s.getTotalVat());
        t.setTotalGross(s.getTotalGross());
        t.setPaidGross(s.getPaidGross());
        t.setPaidAt(s.getPaidAt());
        t.setReleasedAt(s.getReleasedAt());
        t.setPdfLanguage(s.getPdfLanguage());
        t.setHasPdf(s.getPdfObjectKey() != null);
        t.setCancelledAt(s.getCancelledAt());
        t.setCancelReason(s.getCancelReason());
        t.setIssuedById(s.getIssuedBy() == null ? null : s.getIssuedBy().getId());
        t.setOverdue(isOverdue(s));
    }

    /** R2.6: {@code dueDate < Today} while ISSUED or PARTIALLY_PAID. */
    static boolean isOverdue(ClientProformaEntity s) {
        return s.getDueDate() != null
                && (s.getStatus() == ProformaStatus.ISSUED || s.getStatus() == ProformaStatus.PARTIALLY_PAID)
                && s.getDueDate().isBefore(LocalDate.now(WARSAW));
    }

    private static ClientProformaLineServiceModel toLine(ClientProformaLineEntity l) {
        ClientProformaLineServiceModel m = new ClientProformaLineServiceModel();
        m.setId(l.getId());
        m.setPositionId(l.getPosition().getId());
        m.setLineNo(l.getLineNo());
        m.setMaterialId(l.getMaterial() == null ? null : l.getMaterial().getId());
        m.setDescription(l.getDescription());
        m.setSku(l.getSku());
        m.setQuantity(l.getQuantity());
        m.setUnitName(l.getUnitName());
        m.setUnitSaleNet(l.getUnitSaleNet());
        m.setVatRateId(l.getVatRate() == null ? null : l.getVatRate().getId());
        m.setVatRatePercent(l.getVatRatePercent());
        m.setNet(l.getNet());
        m.setVat(l.getVat());
        m.setGross(l.getGross());
        m.setActive(l.isActive());
        return m;
    }

    @Override
    public ClientProformaEntity toCreateDaoModel(ClientProformaServiceExtendedModel s) {
        ClientProformaEntity e = new ClientProformaEntity();
        e.setProject(entityManager.getReference(ProjectEntity.class, s.getProjectId()));
        e.setStatus(ProformaStatus.DRAFT);
        e.setDueDate(s.getDueDate());
        e.setNote(blankToNull(s.getNote()));
        return e;
    }

    @Override
    public void updateFields(ClientProformaServiceExtendedModel s, ClientProformaEntity t) {
        if (s.getDueDate() != null) {
            t.setDueDate(s.getDueDate());
        }
        if (s.getNote() != null) {
            t.setNote(blankToNull(s.getNote()));
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
