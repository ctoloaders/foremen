package com.foremen.service.offer;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import com.foremen.dao.CurrencyDao;
import com.foremen.dao.EstimateDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.RoleResourceEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.service.permission.PermissionCache;
import com.foremen.dao.model.CurrencyEntity;
import com.foremen.dao.model.EstimateEntity;
import com.foremen.dao.model.EstimateLineEntity;
import com.foremen.dao.model.EstimateLineRoomQtyEntity;
import com.foremen.dao.model.EstimateStatus;
import com.foremen.dao.model.MeasurementUnitEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.RoomTypeEntity;
import com.foremen.dao.model.WorkCategoryEntity;
import com.foremen.dao.model.WorkItemEntity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-05-07b task 1: shared Testcontainers + fixture support for the bug condition exploration tests.
 * Repeatability: every row carries a per-run id and {@link #cleanUp()} removes the rows.
 */
abstract class OfferExplorationSupport {

    private static final AtomicLong COUNTER = new AtomicLong();

    /**
     * Singleton container shared by all subclasses and never stopped per class (Ryuk removes it at JVM exit).
     * Not annotated with @Container: the JUnit extension would stop it after each class while the cached
     * Spring context (holding the JDBC URL) survives, breaking every later class in the same Gradle run.
     */
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("foremen_test")
            .withUsername("test")
            .withPassword("test")
            .withUrlParam("stringtype", "unspecified");

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
    protected ProjectDao projectDao;
    @Autowired
    protected EstimateDao estimateDao;
    @Autowired
    protected CurrencyDao currencyDao;
    @Autowired
    protected PlatformTransactionManager transactionManager;
    @Autowired
    protected RoleDao roleDao;
    @Autowired
    protected UserDao userDao;
    @Autowired
    protected PermissionCache permissionCache;
    @PersistenceContext
    protected EntityManager entityManager;

    protected final String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    protected TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    protected void authenticateAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin-" + runId, "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }


    protected void authenticateAs(Object principal, String authority) {
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(principal), "n/a", List.of(new SimpleGrantedAuthority(authority))));
    }

    /**
     * Get-or-creates the shared CLIENT role with an OFFERS READ grant (Liquibase is off in this profile, so the
     * ABAC matrix is empty) and a run-scoped CLIENT user; returns the user id. The shared role, resource and
     * operation rows are intentionally kept (their codes cannot be run-scoped).
     */
    protected Long createClientUser() {
        Long id = tx().execute(s -> {
            RoleEntity role = roleDao.findByCode("CLIENT").orElseGet(() -> {
                RoleEntity r = new RoleEntity();
                r.setCode("CLIENT");
                r.setNameRU("Client");
                r.setNamePL("Klient");
                entityManager.persist(r);
                return r;
            });
            ResourceEntity resource = entityManager.createQuery(
                    "select r from ResourceEntity r where r.code = 'OFFERS'", ResourceEntity.class)
                    .getResultStream().findFirst().orElseGet(() -> {
                        ResourceEntity r = new ResourceEntity();
                        r.setCode("OFFERS");
                        r.setNameRU("Offers");
                        r.setNamePL("Oferty");
                        entityManager.persist(r);
                        return r;
                    });
            OperationEntity read = entityManager.createQuery(
                    "select o from OperationEntity o where o.code = 'READ'", OperationEntity.class)
                    .getResultStream().findFirst().orElseGet(() -> {
                        OperationEntity o = new OperationEntity();
                        o.setCode("READ");
                        o.setNameRU("Read");
                        o.setNamePL("Odczyt");
                        entityManager.persist(o);
                        return o;
                    });
            boolean granted = role.getRoleResources().stream()
                    .anyMatch(rr -> rr.getResource().getCode().equals("OFFERS")
                            && rr.getOperations().stream().anyMatch(op -> op.getCode().equals("READ")));
            if (!granted) {
                RoleResourceEntity rr = new RoleResourceEntity();
                rr.setRole(role);
                rr.setResource(resource);
                rr.getOperations().add(read);
                role.getRoleResources().add(rr);
            }
            UserEntity user = new UserEntity();
            user.setName("Client " + runId);
            user.setEmail("client+" + runId + "-" + COUNTER.incrementAndGet() + "@example.com");
            user.setRole(role);
            user.setActive(true);
            user.setStatus(UserStatus.ACTIVE);
            user.setLocale(java.util.Locale.forLanguageTag("ru"));
            userDao.save(user);
            return user.getId();
        });
        permissionCache.invalidate("CLIENT");
        return id;
    }

    @AfterEach
    void cleanUpExploration() {
        SecurityContextHolder.clearContext();
        tx().executeWithoutResult(status -> {
            String p = "%" + runId + "%";
            entityManager.createQuery("delete from UserEntity u where u.email like :e")
                    .setParameter("e", "client+" + runId + "-%").executeUpdate();
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

    /** Project + estimate (no lines) + a work item and a room, ready for {@link #addLine(Fx, BigDecimal, BigDecimal)}. */
    protected Fx emptyEstimateFixture(String tag) {
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
            project.setName("Exploration " + id);
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
            entityManager.flush();
            f.projectId = project.getId();
            f.estimateId = estimate.getId();
            f.workItemId = item.getId();
            f.unitId = unit.getId();
            f.roomId = room.getId();
        });
        return f;
    }

    /** Adds one estimate line with a single room quantity cell (the filled-estimate shape of project 1531). */
    protected void addLine(Fx f, BigDecimal unitPrice, BigDecimal quantity) {
        tx().executeWithoutResult(s -> {
            EstimateLineEntity line = new EstimateLineEntity();
            line.setEstimate(entityManager.getReference(EstimateEntity.class, f.estimateId));
            line.setWorkItem(entityManager.getReference(WorkItemEntity.class, f.workItemId));
            line.setUnit(entityManager.getReference(MeasurementUnitEntity.class, f.unitId));
            line.setLineNo(1);
            line.setUnitPrice(unitPrice);
            line.setQuantity(quantity);
            line.setValueNet(unitPrice.multiply(quantity));
            entityManager.persist(line);
            EstimateLineRoomQtyEntity cell = new EstimateLineRoomQtyEntity();
            cell.setLine(line);
            cell.setRoom(entityManager.getReference(RoomEntity.class, f.roomId));
            cell.setQuantity(quantity);
            entityManager.persist(cell);
            entityManager.flush();
        });
    }

    protected static final class Fx {
        Long projectId;
        Long estimateId;
        Long workItemId;
        Long unitId;
        Long roomId;
    }
}
