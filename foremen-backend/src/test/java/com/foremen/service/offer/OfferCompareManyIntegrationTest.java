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
import org.springframework.http.HttpStatus;
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
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.OfferService;
import com.foremen.service.offer.OfferSnapshotViews.MultiComparison;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-05-07b Round 2 (D4, task 7.5): multi-offer comparison against a real PostgreSQL. Covers the
 * 2..10 id bound, one project, snapshot required, CLIENT 404 for DRAFT, per-offer values and rooms,
 * and CLIENT redaction (no base prices).
 *
 * <p>Repeatability: every fixture row carries a per-run id; {@link #cleanUp()} removes them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
class OfferCompareManyIntegrationTest {

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
    private OfferMultiComparisonService comparisonService;
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
            entityManager.createQuery("delete from EstimateLineRoomQtyEntity q where q.line.estimate.id in "
                    + "(select e.id from EstimateEntity e where e.project.name like :p)").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from EstimateLineEntity l where l.estimate.id in "
                    + "(select e.id from EstimateEntity e where e.project.name like :p)").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from EstimateEntity e where e.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from RoomEntity r where r.project.id in "
                    + "(select pr.id from ProjectEntity pr where pr.name like :p)").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from ProjectEntity pr where pr.name like :p").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from RoomTypeEntity rt where rt.code like :p").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from WorkItemEntity wi where wi.code like :p").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from WorkCategoryEntity wc where wc.code like :p").setParameter("p", p).executeUpdate();
            entityManager.createQuery("delete from MeasurementUnitEntity u where u.code like :p").setParameter("p", p).executeUpdate();
        });
    }

    @Test
    @DisplayName("three offers: per-offer totals, group values, work quantity, value and rooms in request order")
    void comparesThreeOffers() {
        Fx f = fixture("A", new BigDecimal("10.00"), new BigDecimal("5.00"));
        authenticateAdmin();
        OfferEntity o1 = offerService.prepareOffer(f.projectId, "One");
        setQuantity(f, "8.00");
        OfferEntity o2 = offerService.prepareOffer(f.projectId, "Two");
        setQuantity(f, "2.00");
        OfferEntity o3 = offerService.prepareOffer(f.projectId, "Three");

        MultiComparison c = comparisonService.compareMany(List.of(o2.getId(), o1.getId(), o3.getId()), false, false);

        assertThat(c.offers()).extracting(OfferSnapshotViews.MultiOffer::id)
                .containsExactly(o2.getId(), o1.getId(), o3.getId());
        assertThat(c.offers()).extracting(OfferSnapshotViews.MultiOffer::name).containsExactly("Two", "One", "Three");
        assertThat(c.offers().get(0).inactive()).isFalse();
        assertThat(c.offers().get(0).totalNet()).isEqualByComparingTo("80.00");
        assertThat(c.groups()).hasSize(1);
        assertThat(c.groups().get(0).categoryCode()).startsWith("CAT_");
        assertThat(c.groups().get(0).values()).extracting(OfferSnapshotViews.OfferValue::valueNet)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("80.00"), new BigDecimal("50.00"), new BigDecimal("20.00"));
        assertThat(c.works()).hasSize(1);
        List<OfferSnapshotViews.WorkOffer> perOffer = c.works().get(0).offers();
        assertThat(perOffer).extracting(OfferSnapshotViews.WorkOffer::offerId)
                .containsExactly(o2.getId(), o1.getId(), o3.getId());
        assertThat(perOffer.get(1).quantity()).isEqualByComparingTo("5.00");
        assertThat(perOffer.get(1).valueNet()).isEqualByComparingTo("50.00");
        assertThat(perOffer.get(1).rooms()).hasSize(1);
        assertThat(perOffer.get(1).rooms().get(0).label()).isEqualTo("Kitchen");
        assertThat(perOffer.get(1).rooms().get(0).quantity()).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("id count outside 2..10 or duplicated ids answer 400")
    void idCountBounds() {
        authenticateAdmin();
        assertBadRequest(List.of(1L));
        assertBadRequest(List.of(1L, 1L));
        assertBadRequest(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L));
    }

    @Test
    @DisplayName("offers of different projects answer 400; an offer without a snapshot answers 400")
    void projectAndSnapshotRules() {
        Fx a = fixture("B1", new BigDecimal("10.00"), new BigDecimal("1.00"));
        Fx b = fixture("B2", new BigDecimal("10.00"), new BigDecimal("1.00"));
        authenticateAdmin();
        OfferEntity oa1 = offerService.prepareOffer(a.projectId, "A1");
        OfferEntity oa2 = offerService.prepareOffer(a.projectId, "A2");
        OfferEntity ob = offerService.prepareOffer(b.projectId, "B");

        assertThatThrownBy(() -> comparisonService.compareMany(List.of(oa1.getId(), ob.getId()), false, false))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("error.offer.compare.different.project");
                });

        tx().executeWithoutResult(s -> entityManager.createNativeQuery(
                "update offers set snapshot = null where id = :id").setParameter("id", oa2.getId()).executeUpdate());
        assertThatThrownBy(() -> comparisonService.compareMany(List.of(oa1.getId(), oa2.getId()), false, false))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("error.offer.compare.many.snapshot.missing");
                });
    }

    @Test
    @DisplayName("CLIENT gets 404 for a DRAFT offer, and redacted data once the offers are sent")
    void clientRedaction() throws Exception {
        Fx f = fixture("C", new BigDecimal("10.00"), new BigDecimal("5.00"));
        tx().executeWithoutResult(s ->
                entityManager.find(EstimateLineEntity.class, f.lineId).setUnitPriceOverride(new BigDecimal("12.00")));
        authenticateAdmin();
        OfferEntity o1 = offerService.prepareOffer(f.projectId, "One");
        OfferEntity o2 = offerService.prepareOffer(f.projectId, "Two");
        List<Long> ids = List.of(o1.getId(), o2.getId());

        assertThatThrownBy(() -> comparisonService.compareMany(ids, true, false))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));

        tx().executeWithoutResult(s -> ids.forEach(id ->
                entityManager.find(OfferEntity.class, id).setStatus(OfferStatus.SENT)));
        String clientJson = new ObjectMapper().writeValueAsString(comparisonService.compareMany(ids, true, false));
        assertThat(clientJson).contains("\"valueNet\"").doesNotContain("base");
    }

    // --- helpers ---

    private void assertBadRequest(List<Long> ids) {
        assertThatThrownBy(() -> comparisonService.compareMany(ids, false, false))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("error.offer.compare.many.count");
                });
    }

    private void setQuantity(Fx f, String quantity) {
        tx().executeWithoutResult(s -> {
            EstimateLineEntity line = entityManager.find(EstimateLineEntity.class, f.lineId);
            line.setQuantity(new BigDecimal(quantity));
            line.setValueNet(line.getUnitPrice().multiply(new BigDecimal(quantity)));
            entityManager.find(EstimateLineRoomQtyEntity.class, f.cellId).setQuantity(new BigDecimal(quantity));
        });
    }

    private void authenticateAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin-" + runId, "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    private static final class Fx {
        Long projectId;
        Long lineId;
        Long cellId;
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
            project.setName("CompareMany IT " + id);
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
            RoomTypeEntity roomType = new RoomTypeEntity();
            roomType.setCode("RT_" + id);
            roomType.setNameRU("Room");
            roomType.setNamePL("Room");
            roomType.setActive(true);
            entityManager.persist(roomType);
            RoomEntity room = new RoomEntity();
            room.setProject(project);
            room.setRoomType(roomType);
            room.setLabel("Kitchen");
            entityManager.persist(room);
            EstimateLineRoomQtyEntity cell = new EstimateLineRoomQtyEntity();
            cell.setLine(line);
            cell.setRoom(room);
            cell.setQuantity(quantity);
            entityManager.persist(cell);
            entityManager.flush();
            f.projectId = project.getId();
            f.lineId = line.getId();
            f.cellId = cell.getId();
        });
        return f;
    }
}
