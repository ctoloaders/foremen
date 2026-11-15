package com.foremen.service.model.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.foremen.dao.BankTransactionLinkDao;
import com.foremen.dao.ClientProformaLineDao;
import com.foremen.dao.ExternalOrderDocumentDao;
import com.foremen.dao.ExternalOrderLineDao;
import com.foremen.dao.model.ClientProformaEntity;
import com.foremen.dao.model.ClientProformaLineEntity;
import com.foremen.dao.model.ExternalOrderEntity;
import com.foremen.dao.model.ExternalOrderLineEntity;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.service.model.ExternalOrderProformaRefServiceModel;
import com.foremen.service.model.ExternalOrderServiceExtendedModel;
import com.foremen.service.procurement.proforma.ProformaStatus;

import jakarta.persistence.EntityManager;

/**
 * FOR-11-03 task 15.1 (R24.5): the buyer card of an external order references the proformas of its
 * positions through ACTIVE proforma lines, fetched in one batch query.
 *
 * <p>Validates: Requirements 24.5
 */
class ExternalOrderServiceMapperProformasTest {

    private final ExternalOrderLineDao lineDao = mock(ExternalOrderLineDao.class);
    private final ClientProformaLineDao proformaLineDao = mock(ClientProformaLineDao.class);
    private final ExternalOrderServiceMapper mapper = new ExternalOrderServiceMapper(
            mock(EntityManager.class), lineDao, mock(ExternalOrderDocumentDao.class),
            mock(BankTransactionLinkDao.class), proformaLineDao);

    private static ExternalOrderLineEntity orderLine(ExternalOrderEntity order, long positionId) {
        PurchasePositionEntity position = new PurchasePositionEntity();
        position.setId(positionId);
        ExternalOrderLineEntity l = new ExternalOrderLineEntity();
        l.setExternalOrder(order);
        l.setPosition(position);
        l.setQuantity(java.math.BigDecimal.ONE);
        l.setUnitPurchaseNet(new java.math.BigDecimal("10.00"));
        return l;
    }

    private static ClientProformaLineEntity proformaLine(ClientProformaEntity proforma) {
        ClientProformaLineEntity l = new ClientProformaLineEntity();
        l.setProforma(proforma);
        l.setActive(true);
        return l;
    }

    private static ClientProformaEntity proforma(long id, String number, ProformaStatus status) {
        ClientProformaEntity p = new ClientProformaEntity();
        p.setId(id);
        p.setNumber(number);
        p.setStatus(status);
        return p;
    }

    @Test
    void proformasAreDistinctReferencesFromOneBatchQuery() {
        ExternalOrderEntity order = new ExternalOrderEntity();
        order.setId(9L);
        when(lineDao.findByExternalOrderId(9L)).thenReturn(List.of(
                orderLine(order, 1L), orderLine(order, 2L), orderLine(order, 3L)));
        ClientProformaEntity first = proforma(100L, "PF/2026/000100", ProformaStatus.ISSUED);
        ClientProformaEntity second = proforma(101L, null, ProformaStatus.DRAFT);
        when(proformaLineDao.findActiveWithProformaByPositionIds(anyCollection())).thenReturn(List.of(
                proformaLine(first), proformaLine(first), proformaLine(second)));

        ExternalOrderServiceExtendedModel model = mapper.toServiceExtendedModel(order);

        assertThat(model.getProformas()).containsExactly(
                new ExternalOrderProformaRefServiceModel(100L, "PF/2026/000100", ProformaStatus.ISSUED),
                new ExternalOrderProformaRefServiceModel(101L, null, ProformaStatus.DRAFT));
        verify(proformaLineDao, times(1)).findActiveWithProformaByPositionIds(anyCollection());
    }

    @Test
    void orderWithoutLinesHasNoProformasAndNoQuery() {
        ExternalOrderEntity order = new ExternalOrderEntity();
        order.setId(9L);
        when(lineDao.findByExternalOrderId(9L)).thenReturn(List.of());

        ExternalOrderServiceExtendedModel model = mapper.toServiceExtendedModel(order);

        assertThat(model.getProformas()).isEmpty();
        verify(proformaLineDao, never()).findActiveWithProformaByPositionIds(anyCollection());
    }
}
