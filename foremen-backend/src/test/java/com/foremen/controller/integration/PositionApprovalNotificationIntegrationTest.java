package com.foremen.controller.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import com.foremen.dao.NotificationDao;
import com.foremen.dao.PositionApprovalRoundDao;
import com.foremen.dao.PurchasePositionDao;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.FinishingMaterialEntity;
import com.foremen.dao.model.MaterialCategoryEntity;
import com.foremen.dao.model.MaterialEntity;
import com.foremen.dao.model.MaterialTypeEntity;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.NotificationEntity;
import com.foremen.dao.model.OfferPackageEntity;
import com.foremen.dao.model.PositionKind;
import com.foremen.dao.model.PositionStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.PurchasePositionEntity;
import com.foremen.dao.model.PurchaseRequestEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.SourcingPath;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.dao.model.VatRateEntity;
import com.foremen.service.offer.NotificationService;
import com.foremen.service.procurement.approval.PositionApprovalService;
import com.foremen.service.procurement.approval.PositionApprovalService.AcceptCommand;
import com.foremen.service.procurement.approval.PositionApprovalService.AnalogCommand;
import com.foremen.service.procurement.approval.PositionApprovalService.Decider;
import com.foremen.service.procurement.approval.PositionApprovalService.SendChoiceCommand;
import com.foremen.service.procurement.approval.PositionApprovalService.SendCommand;
import com.foremen.service.procurement.approval.PositionApprovalService.SuggestCommand;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-11-02 task 8.3 - {@code PositionApprovalNotificationEmitter} end to end: the AFTER_COMMIT listener
 * fires when {@link PositionApprovalService} operations really commit and creates the right
 * notifications (types, ACTIVE-member recipients by role, de-duplication, actor exclusion, on-behalf =
 * none, deep links); a failing notification never rolls back the business transaction.
 *
 * <p>This class is deliberately <b>not</b> {@code @Transactional}: AFTER_COMMIT only fires on a real commit
 * and {@code NotificationService.create} runs in REQUIRES_NEW, so fixtures must be committed. It owns its
 * PostgreSQL container so it cannot disturb the rolled-back suites.
 *
 * <p>Repeatability: every fixture entity carries a per-run unique suffix; {@link #cleanUp()} deletes the
 * created notifications and deactivates the created users (so the "all active BUYER users" fallback of
 * later runs is not polluted).
 *
 * <p>Validates Requirements 15.1, 15.2, 15.3, 15.4, 15.5, 22.2.
 */
@SpringBootTest
@ActiveProfiles("integration-test")
@WithMockUser(username = "admin@foremen.com", roles = "ADMIN")
class PositionApprovalNotificationIntegrationTest {

    private static final String CLIENT = "CLIENT";
    private static final String MANAGER = "MANAGER";
    private static final String BUYER = "BUYER";

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private PositionApprovalService service;
    @Autowired
    private NotificationDao notificationDao;
    @Autowired
    private PurchasePositionDao positionDao;
    @Autowired
    private PositionApprovalRoundDao roundDao;
    @Autowired
    private PlatformTransactionManager txManager;
    @MockitoSpyBean
    private NotificationService notificationService;
    @PersistenceContext
    private EntityManager em;

    private TransactionTemplate tx;
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private int seq;
    private final List<Long> createdUsers = new ArrayList<>();

    // ------------------------------------------------------------------ send

    @Test
    @DisplayName("send: one decisionRequested per ACTIVE client per operation, tab link, nobody else")
    void sendNotifiesActiveClientsOnce() {
        Fixture f = fixture();
        PurchasePositionEntity p1 = agreed(f);
        PurchasePositionEntity p2 = agreed(f);

        service.send(new SendCommand(List.of(p1.getId(), p2.getId()), null, null));

        String tab = tab(f.project);
        for (UserEntity c : List.of(f.client1, f.client2)) {
            assertThat(of(c, "notification.position.decisionRequested")).hasSize(1)
                    .allSatisfy(n -> {
                        assertThat(n.getDeepLink()).isEqualTo(tab);
                        assertThat(n.isRead()).isFalse();
                    });
        }
        assertThat(all(f.inactiveClient, f.manager, f.inactiveManager, f.otherClient, f.buyerUser)).isEmpty();
    }

    @Test
    @DisplayName("sendChoice: decisionRequested to the clients")
    void sendChoiceNotifiesClients() {
        Fixture f = fixture();
        PurchasePositionEntity pkg = position(f, SourcingPath.PACKAGE);

        service.sendChoice(new SendChoiceCommand(pkg.getId(),
                List.of(material(f.project).getId(), material(f.project).getId()), null, null));

        assertThat(of(f.client1, "notification.position.decisionRequested")).hasSize(1);
        assertThat(of(f.client2, "notification.position.decisionRequested")).hasSize(1);
        assertThat(all(f.manager, f.otherClient)).isEmpty();
    }

    // ------------------------------------------------------------- analog

    @Test
    @DisplayName("proposeAnalog: analogProposed to the ACTIVE clients with the position deep link")
    void analogNotifiesClients() {
        Fixture f = fixture();
        PurchasePositionEntity p = agreed(f);
        service.send(new SendCommand(List.of(p.getId()), null, null));

        service.proposeAnalog(p.getId(), new AnalogCommand(material(f.project).getId(), new BigDecimal("150.00"),
                new BigDecimal("90.00"), null, null, null));

        for (UserEntity c : List.of(f.client1, f.client2)) {
            assertThat(of(c, "notification.position.analogProposed")).hasSize(1)
                    .allSatisfy(n -> assertThat(n.getDeepLink()).isEqualTo(posLink(f.project, p)));
        }
        assertThat(of(f.inactiveClient, "notification.position.analogProposed")).isEmpty();
        assertThat(of(f.manager, "notification.position.analogProposed")).isEmpty();
    }

    // --------------------------------------------------- accept / reject

    @Test
    @DisplayName("client accept: accepted to ACTIVE managers only, with the position link")
    void acceptNotifiesManagers() {
        Fixture f = fixture();
        PurchasePositionEntity p = agreed(f);
        Long roundId = service.send(new SendCommand(List.of(p.getId()), null, null)).get(0);

        service.accept(roundId, new AcceptCommand(null, new BigDecimal("100.00"), null),
                Decider.client(f.client1.getId()));

        assertThat(of(f.manager, "notification.position.accepted")).hasSize(1)
                .allSatisfy(n -> assertThat(n.getDeepLink()).isEqualTo(posLink(f.project, p)));
        assertThat(of(f.inactiveManager, "notification.position.accepted")).isEmpty();
        assertThat(of(f.client1, "notification.position.accepted")).isEmpty();
        assertThat(of(f.otherManager, "notification.position.accepted")).isEmpty();
        assertThat(positionDao.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PositionStatus.ACCEPTED);
    }

    @Test
    @DisplayName("client reject: rejected to ACTIVE managers only")
    void rejectNotifiesManagers() {
        Fixture f = fixture();
        PurchasePositionEntity p = agreed(f);
        Long roundId = service.send(new SendCommand(List.of(p.getId()), null, null)).get(0);

        service.reject(roundId, "too expensive", Decider.client(f.client2.getId()));

        assertThat(of(f.manager, "notification.position.rejected")).hasSize(1)
                .allSatisfy(n -> assertThat(n.getDeepLink()).isEqualTo(posLink(f.project, p)));
        assertThat(of(f.inactiveManager, "notification.position.rejected")).isEmpty();
        assertThat(of(f.client2, "notification.position.rejected")).isEmpty();
    }

    @Test
    @DisplayName("decision on behalf of the client notifies nobody")
    void onBehalfNotifiesNobody() {
        Fixture f = fixture();
        PurchasePositionEntity p = agreed(f);
        Long roundId = service.send(new SendCommand(List.of(p.getId()), null, null)).get(0);
        int before = countAll(f);

        service.accept(roundId, new AcceptCommand(null, new BigDecimal("100.00"), "phone call"),
                Decider.staffOnBehalf(f.manager.getId()));

        assertThat(countAll(f)).isEqualTo(before);
        assertThat(positionDao.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PositionStatus.ACCEPTED);

        PurchasePositionEntity p2 = agreed(f);
        Long round2 = service.send(new SendCommand(List.of(p2.getId()), null, null)).get(0);
        int before2 = countAll(f);
        service.reject(round2, "phone call", Decider.staffOnBehalf(f.manager.getId()));
        assertThat(countAll(f)).isEqualTo(before2);
    }

    // ------------------------------------------------------------ suggest

    @Test
    @DisplayName("suggestion: suggestionReceived to ACTIVE managers with the position link")
    void suggestionNotifiesManagers() {
        Fixture f = fixture();
        PurchasePositionEntity p = agreed(f);
        service.send(new SendCommand(List.of(p.getId()), null, null));

        service.suggest(p.getId(), new SuggestCommand("https://shop.example.com/item/1", "this one"),
                Decider.client(f.client1.getId()));

        assertThat(of(f.manager, "notification.position.suggestionReceived")).hasSize(1)
                .allSatisfy(n -> assertThat(n.getDeepLink()).isEqualTo(posLink(f.project, p)));
        assertThat(of(f.inactiveManager, "notification.position.suggestionReceived")).isEmpty();
        assertThat(of(f.client1, "notification.position.suggestionReceived")).isEmpty();
    }

    // --------------------------------------------------------- client own

    @Test
    @DisplayName("clientOwn with an assigned buyer: managers + that buyer, de-duplicated, actor excluded")
    void clientOwnWithAssignedBuyer() {
        Fixture f = fixture();
        PurchasePositionEntity p = agreed(f);
        // the assigned buyer is also an ACTIVE project MANAGER -> must receive exactly one notification
        assignBuyer(p, f.manager);
        service.send(new SendCommand(List.of(p.getId()), null, null));

        service.declareClientOwn(p.getId(), "we buy it", Decider.client(f.client1.getId()));

        assertThat(of(f.manager, "notification.position.clientOwn")).hasSize(1)
                .allSatisfy(n -> assertThat(n.getDeepLink()).isEqualTo(posLink(f.project, p)));
        assertThat(of(f.inactiveManager, "notification.position.clientOwn")).isEmpty();
        assertThat(of(f.client1, "notification.position.clientOwn")).isEmpty();
        assertThat(of(f.buyerUser, "notification.position.clientOwn")).isEmpty(); // assigned buyer wins
    }

    @Test
    @DisplayName("clientOwn without a buyer: falls back to active BUYER users, actor excluded")
    void clientOwnBuyerFallback() {
        Fixture f = fixture();
        PurchasePositionEntity p = agreed(f);
        service.send(new SendCommand(List.of(p.getId()), null, null));

        // the acting client is itself a global BUYER user -> excluded as the actor
        service.declareClientOwn(p.getId(), null, Decider.client(f.clientBuyer.getId()));

        assertThat(of(f.buyerUser, "notification.position.clientOwn")).hasSize(1);
        assertThat(of(f.manager, "notification.position.clientOwn")).hasSize(1);
        assertThat(of(f.clientBuyer, "notification.position.clientOwn")).isEmpty();
        assertThat(of(f.client1, "notification.position.clientOwn")).isEmpty();
    }

    // -------------------------------------------------- failure isolation

    @Test
    @DisplayName("every notification failing does not roll back the business transaction")
    void failingNotificationDoesNotRollBack() {
        Fixture f = fixture();
        PurchasePositionEntity p = agreed(f);
        doThrow(new IllegalStateException("boom")).when(notificationService)
                .create(any(), any(), any(), any());

        List<Long> rounds = service.send(new SendCommand(List.of(p.getId()), null, null));

        assertThat(rounds).hasSize(1);
        assertThat(positionDao.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PositionStatus.PROPOSED);
        assertThat(roundDao.findById(rounds.get(0))).isPresent();
        assertThat(all(f.client1, f.client2)).isEmpty();
    }

    @Test
    @DisplayName("one failing recipient does not stop the others")
    void failingRecipientDoesNotBlockOthers() {
        Fixture f = fixture();
        PurchasePositionEntity p = agreed(f);
        doThrow(new IllegalStateException("boom")).when(notificationService)
                .create(eq(f.client1.getId()), any(), any(), any());

        service.send(new SendCommand(List.of(p.getId()), null, null));

        assertThat(positionDao.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(PositionStatus.PROPOSED);
        assertThat(all(f.client1)).isEmpty();
        assertThat(of(f.client2, "notification.position.decisionRequested")).hasSize(1);
    }

    // ================================================================ fixtures

    private record Fixture(ProjectEntity project, UserEntity client1, UserEntity client2, UserEntity inactiveClient,
                           UserEntity otherClient, UserEntity otherManager, UserEntity manager,
                           UserEntity inactiveManager, UserEntity buyerUser, UserEntity clientBuyer) {
    }

    private Fixture fixture() {
        tx = new TransactionTemplate(txManager);
        return tx.execute(s -> {
            ProjectEntity project = project();
            ProjectEntity other = project();
            UserEntity client1 = user(CLIENT);
            UserEntity client2 = user(CLIENT);
            UserEntity inactiveClient = user(CLIENT);
            UserEntity otherClient = user(CLIENT);
            UserEntity manager = user(MANAGER);
            UserEntity inactiveManager = user(MANAGER);
            UserEntity otherManager = user(MANAGER);
            UserEntity buyerUser = user(BUYER);
            UserEntity clientBuyer = user(BUYER);
            member(client1, project, CLIENT, AssignmentStatus.ACTIVE);
            member(client2, project, CLIENT, AssignmentStatus.ACTIVE);
            member(inactiveClient, project, CLIENT, AssignmentStatus.INACTIVE);
            member(otherClient, other, CLIENT, AssignmentStatus.ACTIVE);
            member(manager, project, MANAGER, AssignmentStatus.ACTIVE);
            member(inactiveManager, project, MANAGER, AssignmentStatus.INACTIVE);
            member(otherManager, other, MANAGER, AssignmentStatus.ACTIVE);
            member(clientBuyer, project, CLIENT, AssignmentStatus.ACTIVE);
            return new Fixture(project, client1, client2, inactiveClient, otherClient, otherManager, manager,
                    inactiveManager, buyerUser, clientBuyer);
        });
    }

    private PurchasePositionEntity agreed(Fixture f) {
        return position(f, SourcingPath.AGREED);
    }

    private PurchasePositionEntity position(Fixture f, SourcingPath path) {
        return tx.execute(s -> {
            FinishingMaterialEntity planned = material(f.project);
            VatRateEntity vat = vat();
            PurchasePositionEntity pos = new PurchasePositionEntity();
            pos.setProject(em.getReference(ProjectEntity.class, f.project.getId()));
            pos.setKind(PositionKind.FINISHING);
            pos.setSourcingPath(path);
            pos.setStatus(PositionStatus.PLANNED);
            pos.setPlannedMaterial(planned);
            pos.setQuantity(new BigDecimal("5.000"));
            pos.setUnit(planned.getUnit());
            pos.setPlannedPurchasePrice(new BigDecimal("80.00"));
            pos.setSalePrice(new BigDecimal("100.00"));
            pos.setPurchaseVatRate(vat);
            pos.setSaleVatRate(vat);
            em.persist(pos);
            em.flush();
            return pos;
        });
    }

    private void assignBuyer(PurchasePositionEntity position, UserEntity buyer) {
        tx.executeWithoutResult(s -> {
            PurchasePositionEntity pos = em.find(PurchasePositionEntity.class, position.getId());
            PurchaseRequestEntity r = new PurchaseRequestEntity();
            r.setProject(pos.getProject());
            r.setReferenceNumber("REQ-" + run + "-" + (++seq));
            r.setAssignedBuyer(em.getReference(UserEntity.class, buyer.getId()));
            em.persist(r);
            pos.setPurchaseRequest(r);
            em.flush();
        });
    }

    private ProjectEntity project() {
        ProjectEntity p = new ProjectEntity();
        p.setName("PAN project " + run + "-" + (++seq));
        p.setStatus(ProjectStatus.ACTIVE);
        em.persist(p);
        em.flush();
        return p;
    }

    private RoleEntity role(String code) {
        List<RoleEntity> existing = em.createQuery("select r from RoleEntity r where r.code = :c", RoleEntity.class)
                .setParameter("c", code).getResultList();
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        RoleEntity r = new RoleEntity();
        r.setCode(code);
        r.setNameRU(code);
        r.setNamePL(code);
        r.setSystem(true);
        em.persist(r);
        em.flush();
        return r;
    }

    private UserEntity user(String roleCode) {
        UserEntity u = new UserEntity();
        u.setName("PAN user " + run + "-" + (++seq));
        u.setEmail("pan+" + run + "-" + seq + "@example.com");
        u.setRole(role(roleCode));
        u.setActive(true);
        u.setStatus(UserStatus.ACTIVE);
        u.setLocale(Locale.forLanguageTag("ru"));
        em.persist(u);
        em.flush();
        createdUsers.add(u.getId());
        return u;
    }

    private void member(UserEntity user, ProjectEntity project, String roleCode, AssignmentStatus status) {
        ProjectMemberEntity pm = new ProjectMemberEntity();
        pm.setUser(user);
        pm.setProjectId(project.getId());
        pm.setProjectRole(role(roleCode));
        pm.setAssignmentStatus(status);
        em.persist(pm);
        em.flush();
    }

    private VatRateEntity vat() {
        VatRateEntity v = new VatRateEntity();
        v.setCode("pan_vat_" + run + "_" + (++seq));
        v.setRate(new BigDecimal("23"));
        v.setNameRU("23%");
        v.setNamePL("23%");
        v.setActive(true);
        em.persist(v);
        return v;
    }

    private FinishingMaterialEntity material(ProjectEntity project) {
        return tx.execute(s -> {
            String u = run + "_" + (++seq);
            MaterialCategoryEntity category = new MaterialCategoryEntity();
            category.setCode("pan_cat_" + u);
            category.setNameRU("Категория");
            category.setNamePL("Kategoria");
            category.setActive(true);
            em.persist(category);
            MaterialEntity material = new MaterialEntity();
            material.setCode("pan_mat_" + u);
            material.setNameRU("Пол");
            material.setNamePL("Podłoga");
            material.setActive(true);
            em.persist(material);
            MaterialTypeEntity type = new MaterialTypeEntity();
            type.setCode("pan_type_" + u);
            type.setNameRU("Тип");
            type.setNamePL("Typ");
            type.setActive(true);
            em.persist(type);
            MeasurementUnitEntity unit = new MeasurementUnitEntity();
            unit.setCode("pan_unit_" + u);
            unit.setNameRU("м2");
            unit.setNamePL("m2");
            unit.setActive(true);
            em.persist(unit);
            OfferPackageEntity pkg = new OfferPackageEntity();
            pkg.setCode("pan_pkg_" + u);
            pkg.setOrderNo(1);
            pkg.setNameRU("Пакет");
            pkg.setNamePL("Pakiet");
            pkg.setActive(true);
            em.persist(pkg);
            FinishingMaterialEntity m = new FinishingMaterialEntity();
            m.setCategory(category);
            m.setMaterial(material);
            m.setType(type);
            m.setUnit(unit);
            m.setPackages(new HashSet<>(Set.of(pkg)));
            m.setProject(em.getReference(ProjectEntity.class, project.getId()));
            m.setModel("Model " + u);
            m.setRetailNet(new BigDecimal("10.00"));
            m.setCostNet(BigDecimal.ZERO);
            m.setActive(true);
            em.persist(m);
            em.flush();
            return m;
        });
    }

    // ================================================================ asserts

    private List<NotificationEntity> of(UserEntity user, String type) {
        return notificationDao.findByRecipientIdOrderByCreatedDateDesc(user.getId()).stream()
                .filter(n -> type.equals(n.getType())).toList();
    }

    private List<NotificationEntity> all(UserEntity... users) {
        List<NotificationEntity> result = new ArrayList<>();
        for (UserEntity u : users) {
            result.addAll(notificationDao.findByRecipientIdOrderByCreatedDateDesc(u.getId()));
        }
        return result;
    }

    private int countAll(Fixture f) {
        return all(f.client1, f.client2, f.inactiveClient, f.otherClient, f.manager, f.inactiveManager,
                f.otherManager, f.buyerUser, f.clientBuyer).size();
    }

    private static String tab(ProjectEntity p) {
        return "/projects/" + p.getId() + "/positionApprovals";
    }

    private static String posLink(ProjectEntity p, PurchasePositionEntity pos) {
        return tab(p) + "?position=" + pos.getId();
    }

    @AfterEach
    void cleanUp() {
        if (createdUsers.isEmpty()) {
            return;
        }
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            em.createQuery("delete from NotificationEntity n where n.recipient.id in :ids")
                    .setParameter("ids", createdUsers).executeUpdate();
            em.createQuery("update UserEntity u set u.active = false where u.id in :ids")
                    .setParameter("ids", createdUsers).executeUpdate();
        });
        createdUsers.clear();
    }
}
