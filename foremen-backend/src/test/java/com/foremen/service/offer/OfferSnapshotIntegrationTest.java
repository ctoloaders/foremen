package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.CurrencyDao;
import com.foremen.dao.EstimateDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.OfferService;
import com.foremen.service.offer.OfferSnapshotViews.Comparison;
import com.foremen.service.offer.OfferSnapshotViews.SnapshotView;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-05-07 Batch 2 (task 14): offer snapshot integration tests against a real PostgreSQL
 * (Testcontainers; JSONB column): snapshot capture + immutability after estimate changes, custom and
 * default offer names, CLIENT redaction (no base prices, DRAFT offer hidden), staff view with base
 * prices, comparison diff, and rejection of a cross-project comparison.
 *
 * <p>Repeatability: every fixture row carries a per-run id; {@link #cleanUp()} removes them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
class OfferSnapshotIntegrationTest {

    private static final AtomicLong COUNTER = new AtomicLong();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test")
            .withUrlParam("stringtype", "unspecified");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private OfferService offerService;
    @Autowired
    private OfferSnapshotService offerSnapshotService;
    @Autowired
    private ProjectDao projectDao;
    @Autowired
    private EstimateDao estimateDao;
    @Autowired
    private CurrencyDao currencyDao;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @PersistenceContext
    private EntityManager entityManager;

    private final String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        tx().executeWithoutResult(status -> {
            String p = "%" + runId + "%";
            entityManager.createQuery("delete from OfferEntity o where o.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from EstimateLineEntity l where l.estimate.id in "
                    + "(select e.id from EstimateEntity e where e.project.name like :p)").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from EstimateEntity e where e.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from ProjectEntity pr where pr.name like :p").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from WorkItemEntity wi where wi.code like :p").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from WorkCategoryEntity wc where wc.code like :p").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from MeasurementUnitEntity u where u.code like :p").setParameter("p", p).executeUpdate();
        });
    }

    @Test
    @DisplayName("snapshot is captured at prepare and stays unchanged after the estimate changes")
    void snapshotIsImmutableAfterEstimateChange() {
        Fx f = fixture("A", new BigDecimal("10.00"), new BigDecimal("5.00"));
        authenticateAdmin();
        OfferEntity offer = offerService.prepareOffer(f.projectId, "  Premium  ");

        assertThat(offer.getName()).isEqualTo("Premium");
        SnapshotView before = offerSnapshotService.snapshot(offer.getId(), false, true, false);
        assertThat(before.groups()).hasSize(1);
        assertThat(before.groups().get(0).rows().get(0).quantity()).isEqualByComparingTo("5.00");
        assertThat(before.groups().get(0).rows().get(0).valueNet()).isEqualByComparingTo("50.00");
        assertThat(before.totalNet()).isEqualByComparingTo("50.00");

        // The estimate changes after the offer was prepared.
        tx().executeWithoutResult(s -> {
            EstimateLineEntity line = entityManager.find(EstimateLineEntity.class, f.lineId);
            line.setQuantity(new BigDecimal("99.00"));
            line.setUnitPrice(new BigDecimal("77.00"));
        });

        SnapshotView after = offerSnapshotService.snapshot(offer.getId(), false, true, false);
        assertThat(after.groups().get(0).rows().get(0).quantity()).isEqualByComparingTo("5.00");
        assertThat(after.groups().get(0).rows().get(0).unitPrice()).isEqualByComparingTo("10.00");
        assertThat(after.totalNet()).isEqualByComparingTo("50.00");
        assertThat(after.capturedAt()).isEqualTo(before.capturedAt());
    }

    @Test
    @DisplayName("a blank name falls back to the default 'Offer N'")
    void defaultName() {
        Fx f = fixture("B", new BigDecimal("10.00"), new BigDecimal("1.00"));
        authenticateAdmin();
        OfferEntity first = offerService.prepareOffer(f.projectId, " ");
        assertThat(first.getName()).isEqualTo("Offer 1");
        offerService.withdraw(first.getId());
        OfferEntity second = offerService.prepareOffer(f.projectId);
        assertThat(second.getName()).isEqualTo("Offer 2");
    }

    @Test
    @DisplayName("CLIENT never receives base prices and cannot see a DRAFT offer; staff gets base prices")
    void clientRedaction() throws Exception {
        Fx f = fixture("C", new BigDecimal("10.00"), new BigDecimal("5.00"));
        // The labour price was adjusted: base 10.00 -> effective 12.00.
        tx().executeWithoutResult(s ->
                entityManager.find(EstimateLineEntity.class, f.lineId).setUnitPriceOverride(new BigDecimal("12.00")));
        authenticateAdmin();
        OfferEntity offer = offerService.prepareOffer(f.projectId, "Client view");

        // DRAFT offer is hidden from the client as 404.
        assertThatThrownBy(() -> offerSnapshotService.snapshot(offer.getId(), true, false, false))
                .isInstanceOf(ForemenApiException.class);
        assertThat(offerSnapshotService.listByProject(f.projectId, true)).isEmpty();
        assertThat(offerSnapshotService.listByProject(f.projectId, false)).hasSize(1);

        tx().executeWithoutResult(s ->
                entityManager.find(OfferEntity.class, offer.getId()).setStatus(OfferStatus.SENT));

        ObjectMapper mapper = new ObjectMapper();
        String clientJson = mapper.writeValueAsString(
                offerSnapshotService.snapshot(offer.getId(), true, true, false));
        assertThat(clientJson).contains("\"unitPrice\"").doesNotContain("baseUnitPrice").doesNotContain("baseValueNet");

        SnapshotView staff = offerSnapshotService.snapshot(offer.getId(), false, true, false);
        OfferSnapshotViews.RowView row = staff.groups().get(0).rows().get(0);
        assertThat(row.unitPrice()).isEqualByComparingTo("12.00");
        assertThat(row.baseUnitPrice()).isEqualByComparingTo("10.00");
        // A staff role outside the allow-list (includeBase=false) is redacted too.
        assertThat(offerSnapshotService.snapshot(offer.getId(), false, false, false)
                .groups().get(0).rows().get(0).baseUnitPrice()).isNull();

        // The comparison carries no base prices at all.
        String compareJson = mapper.writeValueAsString(
                offerSnapshotService.compare(offer.getId(), offer.getId(), true, false));
        assertThat(compareJson).doesNotContain("base");
    }

    @Test
    @DisplayName("compare reports changed works between two offers of one project")
    void compareDiff() {
        Fx f = fixture("D", new BigDecimal("10.00"), new BigDecimal("5.00"));
        authenticateAdmin();
        OfferEntity left = offerService.prepareOffer(f.projectId, "Left");
        offerService.withdraw(left.getId());
        tx().executeWithoutResult(s ->
                entityManager.find(EstimateLineEntity.class, f.lineId).setQuantity(new BigDecimal("8.00")));
        OfferEntity right = offerService.prepareOffer(f.projectId, "Right");

        Comparison c = offerSnapshotService.compare(left.getId(), right.getId(), false, false);
        assertThat(c.added()).isEmpty();
        assertThat(c.removed()).isEmpty();
        assertThat(c.changed()).hasSize(1);
        assertThat(c.changed().get(0).leftQuantity()).isEqualByComparingTo("5.00");
        assertThat(c.changed().get(0).rightQuantity()).isEqualByComparingTo("8.00");
        assertThat(c.changed().get(0).deltaValueNet()).isEqualByComparingTo("30.00");
        assertThat(c.deltaTotalNet()).isEqualByComparingTo("30.00");
        assertThat(c.left().name()).isEqualTo("Left");
        assertThat(c.right().name()).isEqualTo("Right");
    }

    @Test
    @DisplayName("compare of offers from different projects is rejected")
    void compareAcrossProjectsRejected() {
        Fx a = fixture("E1", new BigDecimal("10.00"), new BigDecimal("1.00"));
        Fx b = fixture("E2", new BigDecimal("10.00"), new BigDecimal("1.00"));
        authenticateAdmin();
        OfferEntity oa = offerService.prepareOffer(a.projectId, null);
        OfferEntity ob = offerService.prepareOffer(b.projectId, null);
        assertThatThrownBy(() -> offerSnapshotService.compare(oa.getId(), ob.getId(), false, false))
                .isInstanceOf(ForemenApiException.class)
                .hasMessageContaining("error.offer.compare.different.project");
    }

    @Test
    @DisplayName("an offer without a snapshot (legacy) has no snapshot view")
    void legacyOfferHasNoSnapshot() {
        Fx f = fixture("F", new BigDecimal("10.00"), new BigDecimal("1.00"));
        authenticateAdmin();
        OfferEntity offer = offerService.prepareOffer(f.projectId, null);
        tx().executeWithoutResult(s -> entityManager.createNativeQuery(
                "update offers set snapshot = null where id = :id").setParameter("id", offer.getId()).executeUpdate());
        assertThat(offerSnapshotService.listByProject(f.projectId, false).get(0).hasSnapshot()).isFalse();
        assertThatThrownBy(() -> offerSnapshotService.snapshot(offer.getId(), false, true, false))
                .isInstanceOf(ForemenApiException.class)
                .hasMessageContaining("error.offer.snapshot.missing");
    }

    // --- fixtures ---

    private void authenticateAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin-" + runId, "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private static final class Fx {
        Long projectId;
        Long lineId;
    }

    private Fx fixture(String tag, BigDecimal unitPrice, BigDecimal quantity) {
        Fx f = new Fx();
        String id = runId + "-" + tag + "-" + COUNTER.incrementAndGet();
        tx().executeWithoutResult(s -> {
            CurrencyEntity currency = currencyDao.findByCode("PLN").orElseGet(() -> {
                CurrencyEntity c = new CurrencyEntity();
                c.setCode("PLN");
                c.setSymbol("zl");
                c.setNameRU("Zloty");
                c.setNamePL("Zloty");
                c.setActive(true);
                return currencyDao.save(c);
            });
            ProjectEntity project = new ProjectEntity();
            project.setName("Snapshot IT " + id);
            project.setStatus(ProjectStatus.DRAFT);
            projectDao.save(project);
            EstimateEntity estimate = new EstimateEntity();
            estimate.setProject(project);
            estimate.setCurrency(currency);
            estimate.setStatus(EstimateStatus.DRAFT);
            estimateDao.save(estimate);

            MeasurementUnitEntity unit = new MeasurementUnitEntity();
            unit.setCode("M2_" + id);
            unit.setNameRU("m2");
            unit.setNamePL("m2");
            unit.setActive(true);
            entityManager.persist(unit);
            WorkCategoryEntity category = new WorkCategoryEntity();
            category.setCode("CAT_" + id);
            category.setOrderNo(1);
            category.setNameRU("Cat");
            category.setNamePL("Cat");
            category.setActive(true);
            entityManager.persist(category);
            WorkItemEntity item = new WorkItemEntity();
            item.setWorkCategory(category);
            item.setUnit(unit);
            item.setNameRU("Work " + id);
            item.setNamePL("Work " + id);
            item.setCode("WI_" + id);
            item.setActive(true);
            entityManager.persist(item);

            EstimateLineEntity line = new EstimateLineEntity();
            line.setEstimate(estimate);
            line.setWorkItem(item);
            line.setUnit(unit);
            line.setLineNo(1);
            line.setUnitPrice(unitPrice);
            line.setQuantity(quantity);
            line.setValueNet(unitPrice.multiply(quantity));
            entityManager.persist(line);
            entityManager.flush();
            f.projectId = project.getId();
            f.lineId = line.getId();
        });
        return f;
    }
}
