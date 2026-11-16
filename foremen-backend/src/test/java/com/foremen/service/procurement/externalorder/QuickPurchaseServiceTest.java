package com.foremen.service.procurement.externalorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.dao.ExternalOrderLineDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialSellerEntity;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.model.ExternalOrderServiceExtendedModel;
import com.foremen.service.procurement.access.ProcurementProjectAccess;

import jakarta.persistence.EntityManager;

/** FOR-11-06 task 7.5 (R11.2 - R11.4): grouping, one-project rule, ceiling acknowledgement, refusals. */
class QuickPurchaseServiceTest {

    private PurchasePositionDao positionDao;
    private ExternalOrderLineDao lineDao;
    private ExternalOrderService orderService;
    private QuickPurchaseService service;
    private final List<PurchasePositionEntity> store = new ArrayList<>();
    private long nextOrderId = 100;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        positionDao = mock(PurchasePositionDao.class);
        lineDao = mock(ExternalOrderLineDao.class);
        orderService = mock(ExternalOrderService.class);
        service = new QuickPurchaseService(positionDao, lineDao, orderService,
                mock(ProcurementProjectAccess.class), mock(EntityManager.class));
        when(positionDao.findAllByIdIn(anyCollection())).thenAnswer(i -> {
            java.util.Collection<Long> ids = i.getArgument(0);
            return store.stream().filter(p -> ids.contains(p.getId())).toList();
        });
        when(orderService.create(any(ExternalOrderServiceExtendedModel.class))).thenAnswer(i -> {
            ExternalOrderServiceExtendedModel m = i.getArgument(0);
            m.setId(nextOrderId++);
            return m;
        });
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private PurchasePositionEntity position(long id, long projectId, Long sellerId, String purchase, String sale) {
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        FinishingMaterialEntity material = new FinishingMaterialEntity();
        if (sellerId != null) {
            MaterialSellerEntity seller = new MaterialSellerEntity();
            seller.setId(sellerId);
            material.setSeller(seller);
        }
        PurchasePositionEntity p = new PurchasePositionEntity();
        p.setId(id);
        p.setProject(project);
        p.setStatus(PositionStatus.WITH_BUYER);
        p.setQuantity(new BigDecimal("4"));
        p.setPlannedMaterial(material);
        p.setPlannedPurchasePrice(new BigDecimal(purchase));
        p.setSalePrice(new BigDecimal(sale));
        store.add(p);
        return p;
    }

    private static QuickPurchaseService.Request req(List<Long> ids, Map<Long, Long> sellers, Long dflt,
                                                    Set<Long> ack) {
        return new QuickPurchaseService.Request(ids, sellers, dflt, ack, null);
    }

    private static void assertError(Runnable call, HttpStatus status, String key) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ForemenApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getMessageCode()).isEqualTo(key);
        });
    }

    @Test
    void groupsPositionsBySellerIntoOneDraftOrderEach() {
        position(1, 3, 10L, "50.00", "80.00");
        position(2, 3, 20L, "50.00", "80.00");
        position(3, 3, 10L, "50.00", "80.00");

        QuickPurchaseService.Result result = service.createOrders(req(List.of(1L, 2L, 3L), null, null, null));

        assertThat(result.orders()).extracting(QuickPurchaseService.OrderRef::sellerId)
                .containsExactly(10L, 20L);
        assertThat(result.orders()).extracting(QuickPurchaseService.OrderRef::lineCount).containsExactly(2, 1);
        ArgumentCaptor<ExternalOrderServiceExtendedModel> cap =
                ArgumentCaptor.forClass(ExternalOrderServiceExtendedModel.class);
        verify(orderService, org.mockito.Mockito.times(2)).create(cap.capture());
        assertThat(cap.getAllValues().get(0).getLines()).hasSize(2);
        assertThat(cap.getAllValues().get(0).getLines().get(0).getUnitPurchaseNet())
                .isEqualByComparingTo("50.00");
        assertThat(cap.getAllValues().get(0).getProjectId()).isEqualTo(3L);
    }

    @Test
    void suppliedSellerOverridesTheMaterialSeller() {
        position(1, 3, 10L, "50.00", "80.00");
        position(2, 3, null, "50.00", "80.00");

        QuickPurchaseService.Result result =
                service.createOrders(req(List.of(1L, 2L), Map.of(1L, 30L), 40L, null));

        assertThat(result.orders()).extracting(QuickPurchaseService.OrderRef::sellerId)
                .containsExactly(30L, 40L);
    }

    @Test
    void refusesMixedProjectsBeforeCreatingAnything() {
        position(1, 3, 10L, "50.00", "80.00");
        position(2, 4, 10L, "50.00", "80.00");

        assertError(() -> service.createOrders(req(List.of(1L, 2L), null, null, null)),
                HttpStatus.CONFLICT, QuickPurchaseService.MIXED_PROJECTS);
        verify(orderService, never()).create(any(ExternalOrderServiceExtendedModel.class));
    }

    @Test
    void refusesPositionWithoutAnySeller() {
        position(1, 3, null, "50.00", "80.00");

        assertError(() -> service.createOrders(req(List.of(1L), null, null, null)),
                HttpStatus.CONFLICT, QuickPurchaseService.SELLER_REQUIRED);
    }

    @Test
    void refusesPositionNotWithBuyerOrAlreadyOnLiveOrder() {
        PurchasePositionEntity planned = position(1, 3, 10L, "50.00", "80.00");
        planned.setStatus(PositionStatus.PLANNED);
        assertError(() -> service.createOrders(req(List.of(1L), null, null, null)),
                HttpStatus.CONFLICT, ExternalOrderService.POSITION_NOT_PURCHASABLE);

        planned.setStatus(PositionStatus.WITH_BUYER);
        when(lineDao.existsLiveByPositionId(1L)).thenReturn(true);
        assertError(() -> service.createOrders(req(List.of(1L), null, null, null)),
                HttpStatus.CONFLICT, ExternalOrderService.POSITION_NOT_PURCHASABLE);
    }

    @Test
    void aboveCeilingNeedsAcknowledgementAndListsAllOffendingIds() {
        position(1, 3, 10L, "90.00", "80.00");
        position(2, 3, 10L, "95.00", "80.00");
        position(3, 3, 10L, "50.00", "80.00");

        assertThatThrownBy(() -> service.createOrders(req(List.of(1L, 2L, 3L), null, null, Set.of(1L))))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessageCode()).isEqualTo(ExternalOrderService.ABOVE_CEILING);
                    assertThat(e.getParams()).containsEntry("ids", List.of(2L));
                });
        verify(orderService, never()).create(any(ExternalOrderServiceExtendedModel.class));

        QuickPurchaseService.Result ok =
                service.createOrders(req(List.of(1L, 2L, 3L), null, null, Set.of(1L, 2L)));
        assertThat(ok.orders()).hasSize(1);
    }

    @Test
    void refusesEmptyDuplicateAndMissingPositions() {
        position(1, 3, 10L, "50.00", "80.00");

        assertError(() -> service.createOrders(req(List.of(), null, null, null)),
                HttpStatus.BAD_REQUEST, QuickPurchaseService.POSITIONS_INVALID);
        assertError(() -> service.createOrders(req(List.of(1L, 1L), null, null, null)),
                HttpStatus.BAD_REQUEST, QuickPurchaseService.POSITIONS_INVALID);
        assertError(() -> service.createOrders(req(List.of(1L, 9L), null, null, null)),
                HttpStatus.NOT_FOUND, ExternalOrderService.POSITION_NOT_FOUND);
    }

    @Test
    void refusesPendingPositionWithoutMaterial() {
        position(1, 3, 10L, "50.00", "80.00").setPlannedMaterial(null);

        assertError(() -> service.createOrders(req(List.of(1L), null, null, null)),
                HttpStatus.CONFLICT, com.foremen.service.procurement.PositionLabel.MATERIAL_REQUIRED);
    }
}
