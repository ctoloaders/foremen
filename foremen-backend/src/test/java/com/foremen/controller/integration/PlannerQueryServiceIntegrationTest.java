package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchaseRequestEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.procurement.planner.PlannerFilter;
import com.foremen.service.procurement.planner.PlannerFilter.BuyerMode;
import com.foremen.service.procurement.planner.PlannerQueryService;
import com.foremen.service.procurement.planner.PlannerRowDto;
import com.foremen.service.procurement.planner.PlannerRowDto.Action;
import com.foremen.service.procurement.planner.PlannerSliceRules.Slice;

/**
 * FOR-11-01 task 4.1 - {@link PlannerQueryService}: filter composition, sort with nulls last, slice
 * counts, price visibility and {@code allowedActions} per caller.
 *
 * <p>Repeatability: unique per-run data inside a rolled-back transaction.
 *
 * <p>Validates Requirements 1.1-1.9, 2.6, 4.4, 9.2, 9.5.
 */
class PlannerQueryServiceIntegrationTest extends ProcurementIntegrationTestBase {

    @Autowired
    private PlannerQueryService service;

    private static Authentication auth(UserEntity user, String role) {
        return new UsernamePasswordAuthenticationToken(String.valueOf(user.getId()), "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private static PageRequest page(String sortProperty) {
        return PageRequest.of(0, 50, Sort.by(sortProperty));
    }

    private static List<Long> ids(Page<PlannerRowDto> page) {
        return page.getContent().stream().map(PlannerRowDto::positionId).toList();
    }

    private PlannerFilter project(ProjectEntity p) {
        return new PlannerFilter(p.getId(), null, null, null, false, BuyerMode.ALL, Slice.ALL, false, null, false);
    }

    @Test
    @DisplayName("default sort is siteDeliveryDate ascending, nulls last, then id")
    void defaultSort() {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        UserEntity manager = memberUser("MANAGER", project);
        PurchasePositionEntity noDate = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        PurchasePositionEntity late = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        late.setNeedByDate(today().minusDays(3));
        PurchasePositionEntity soon = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        soon.setNeedByDate(today().plusDays(5));
        soon.setPlannedDeliveryDate(today().plusDays(1)); // planned delivery wins over need-by
        em.flush();
        em.clear();

        Page<PlannerRowDto> result = service.plan(project(project), PageRequest.of(0, 50),
                auth(manager, "MANAGER"));

        assertThat(ids(result)).containsExactly(late.getId(), soon.getId(), noDate.getId());
        PlannerRowDto first = result.getContent().get(0);
        assertThat(first.late()).isTrue();
        assertThat(first.urgent()).isTrue();
        assertThat(result.getContent().get(1).siteDeliveryDate()).isEqualTo(today().plusDays(1));
        assertThat(result.getContent().get(2).siteDeliveryDate()).isNull();
        assertThat(result.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("filters compose: status, buyer modes, noRequest, requestId, text with escaped wildcards")
    void filtersCompose() {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        UserEntity manager = memberUser("MANAGER", project);
        UserEntity buyer = user("BUYER");
        PurchaseRequestEntity mine = request(project);
        mine.setAssignedBuyer(buyer);
        PurchaseRequestEntity unassigned = request(project);
        PurchasePositionEntity inMine = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        PurchasePositionEntity inUnassigned = position(project, SourcingPath.AGREED, PositionStatus.ACCEPTED);
        PurchasePositionEntity free = position(project, SourcingPath.PACKAGE, PositionStatus.PLANNED);
        attach(inMine, mine);
        attach(inUnassigned, unassigned);
        free.setNotes("100% cotton_wool");
        em.flush();
        em.clear();
        Authentication buyerAuth = auth(buyer, "BUYER");
        Authentication managerAuth = auth(manager, "MANAGER");
        PageRequest any = PageRequest.of(0, 50);

        assertThat(ids(service.plan(withBuyer(project, BuyerMode.MINE), any, buyerAuth)))
                .containsExactly(inMine.getId());
        assertThat(ids(service.plan(withBuyer(project, BuyerMode.UNASSIGNED), any, managerAuth)))
                .containsExactlyInAnyOrder(inUnassigned.getId(), free.getId());
        assertThat(ids(service.plan(new PlannerFilter(project.getId(), Set.of(PositionStatus.ACCEPTED), null,
                null, false, BuyerMode.ALL, Slice.ALL, false, null, false), any, managerAuth)))
                .containsExactly(inUnassigned.getId());
        assertThat(ids(service.plan(new PlannerFilter(project.getId(), null, null, null, true,
                BuyerMode.ALL, Slice.ALL, false, null, false), any, managerAuth)))
                .containsExactly(free.getId());
        assertThat(ids(service.plan(new PlannerFilter(project.getId(), null, null, mine.getId(), false,
                BuyerMode.ALL, Slice.ALL, false, null, false), any, managerAuth)))
                .containsExactly(inMine.getId());
        assertThat(ids(service.plan(text(project, "100%"), any, managerAuth))).containsExactly(free.getId());
        assertThat(ids(service.plan(text(project, "cotton_w"), any, managerAuth))).containsExactly(free.getId());
        assertThat(ids(service.plan(text(project, "%"), any, managerAuth))).containsExactly(free.getId());
        assertThat(ids(service.plan(text(project, mine.getReferenceNumber().toLowerCase()), any, managerAuth)))
                .containsExactly(inMine.getId());
    }

    @Test
    @DisplayName("hideDelivered excludes RECEIVED; slice and urgent compose; slice counts agree")
    void sliceAndCounts() {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        UserEntity manager = memberUser("MANAGER", project);
        PurchasePositionEntity late = position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        late.setNeedByDate(today().minusDays(1));
        PurchasePositionEntity onSchedule = position(project, SourcingPath.AGREED, PositionStatus.ACCEPTED);
        onSchedule.setNeedByDate(today().plusDays(60));
        PurchasePositionEntity paid = position(project, SourcingPath.AGREED, PositionStatus.CLIENT_PAID);
        PurchasePositionEntity received = position(project, SourcingPath.PACKAGE, PositionStatus.RECEIVED);
        em.flush();
        em.clear();
        Authentication managerAuth = auth(manager, "MANAGER");
        PageRequest any = PageRequest.of(0, 50);

        assertThat(ids(service.plan(new PlannerFilter(project.getId(), null, null, null, false, BuyerMode.ALL,
                Slice.LATE, false, null, false), any, managerAuth))).containsExactly(late.getId());
        assertThat(ids(service.plan(new PlannerFilter(project.getId(), null, null, null, false, BuyerMode.ALL,
                Slice.ALL, true, null, false), any, managerAuth)))
                .containsExactly(late.getId());
        assertThat(ids(service.plan(new PlannerFilter(project.getId(), null, null, null, false, BuyerMode.ALL,
                Slice.ALL, false, null, true), any, managerAuth)))
                .doesNotContain(received.getId()).hasSize(3);

        Map<Slice, Long> counts = service.sliceCounts(new PlannerFilter(project.getId(), null, null, null, false,
                BuyerMode.ALL, Slice.LATE, false, null, false), managerAuth);
        assertThat(counts.get(Slice.ALL)).isEqualTo(4);
        assertThat(counts.get(Slice.LATE)).isEqualTo(1);
        assertThat(counts.get(Slice.PAID)).isEqualTo(1); // CLIENT_PAID on AGREED; the PACKAGE row never counts
    }

    @Test
    @DisplayName("money block only for STAFF_FULL; FOREMAN gets none and no actions")
    void priceVisibilityAndActions() {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        UserEntity manager = memberUser("MANAGER", project);
        UserEntity foreman = memberUser("FOREMAN", project);
        PurchasePositionEntity pos = position(project, SourcingPath.PACKAGE, PositionStatus.PLANNED);
        em.flush();
        em.clear();
        PageRequest any = PageRequest.of(0, 50);

        PlannerRowDto asManager = service.plan(project(project), any, auth(manager, "MANAGER"))
                .getContent().get(0);
        assertThat(asManager.money()).isNotNull();
        assertThat(asManager.money().saleNetTotal()).isEqualByComparingTo("500.00");   // 100 x 5
        assertThat(asManager.money().saleGrossTotal()).isEqualByComparingTo("540.00"); // + 8 %
        assertThat(asManager.money().costNetTotal()).isEqualByComparingTo("425.00");   // actual 85 x 5
        assertThat(asManager.money().marginAmount()).isEqualByComparingTo("68.00");    // 500 - 425 - 7
        assertThat(asManager.money().overCeiling()).isFalse();
        assertThat(asManager.allowedActions()).contains(Action.EDIT, Action.DELETE, Action.SEND_TO_BUYER,
                Action.CANCEL, Action.REPLACE_VARIANT).doesNotContain(Action.REOPEN);
        assertThat(asManager.positionId()).isEqualTo(pos.getId());

        PlannerRowDto asForeman = service.plan(project(project), any, auth(foreman, "FOREMAN"))
                .getContent().get(0);
        assertThat(asForeman.money()).isNull();
        assertThat(asForeman.allowedActions()).isEmpty();
    }

    @Test
    @DisplayName("scope: a member sees only own projects; unknown sort is rejected")
    void scopeAndSortValidation() {
        ProjectEntity mineProject = project(ProjectStatus.ACTIVE);
        ProjectEntity other = project(ProjectStatus.ACTIVE);
        UserEntity manager = memberUser("MANAGER", mineProject);
        PurchasePositionEntity mine = position(mineProject, SourcingPath.AGREED, PositionStatus.PLANNED);
        PurchasePositionEntity foreign = position(other, SourcingPath.AGREED, PositionStatus.PLANNED);
        em.flush();
        em.clear();
        Authentication managerAuth = auth(manager, "MANAGER");

        Page<PlannerRowDto> all = service.plan(PlannerFilter.none(), PageRequest.of(0, 100), managerAuth);
        assertThat(ids(all)).contains(mine.getId()).doesNotContain(foreign.getId());
        assertThat(ids(service.plan(project(other), PageRequest.of(0, 50), managerAuth))).isEmpty();
        assertThatThrownBy(() -> service.plan(PlannerFilter.none(), page("salePrice"), managerAuth))
                .isInstanceOf(ForemenApiException.class);
        assertThat(service.plan(PlannerFilter.none(), page("status"), managerAuth).getTotalElements())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("work link: no material type norms resolves to NONE with a null start date")
    void workLinkNone() {
        ProjectEntity project = project(ProjectStatus.ACTIVE);
        UserEntity manager = memberUser("MANAGER", project);
        position(project, SourcingPath.AGREED, PositionStatus.PLANNED);
        em.flush();
        em.clear();

        PlannerRowDto row = service.plan(project(project), PageRequest.of(0, 50), auth(manager, "MANAGER"))
                .getContent().get(0);
        assertThat(row.workCategorySource().name()).isEqualTo("NONE");
        assertThat(row.effectiveWorkCategoryId()).isNull();
        assertThat(row.workStartDate()).isNull();
        assertThat(row.deliveryRisk()).isFalse();
        assertThat(row.workCategoryCandidates()).isEmpty();
    }

    private PlannerFilter withBuyer(ProjectEntity p, BuyerMode mode) {
        return new PlannerFilter(p.getId(), null, null, null, false, mode, Slice.ALL, false, null, false);
    }

    private PlannerFilter text(ProjectEntity p, String q) {
        return new PlannerFilter(p.getId(), null, null, null, false, BuyerMode.ALL, Slice.ALL, false, q, false);
    }
}
