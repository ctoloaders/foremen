package com.foremen.service.execution.amendment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.foremen.dao.EstimateAmendmentDao;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.SignableDocumentDao;
import com.foremen.dao.SignableDocumentTypeDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.model.AmendmentLineEntity;
import com.foremen.dao.model.AmendmentStatus;
import com.foremen.dao.model.ChangeType;
import com.foremen.dao.model.DocumentSignatureEntity;
import com.foremen.dao.model.DocumentStatus;
import com.foremen.dao.model.EstimateAmendmentEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.dao.model.SignableDocumentTypeEntity;
import com.foremen.dao.model.SignatureMethod;
import com.foremen.dao.model.SignatureStatus;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.document.DocumentNotificationEvent;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * FOR-06-09 task 5.2 (Requirements 2.2, 2.3, 2.4, 7.6; design §{@code AmendmentSigningReaction}) — the
 * after-commit-reaction integration test. It boots the full Spring application context against a real
 * PostgreSQL (Testcontainers) so the production {@link AmendmentSigningReaction} bean is wired exactly
 * as it ships: a second {@code AFTER_COMMIT} {@code @TransactionalEventListener} on the FOR-05-08
 * {@link DocumentNotificationEvent}. The test drives the <b>real</b> after-commit event flow — it
 * publishes the engine's own {@link DocumentNotificationEvent} from inside a committed
 * {@link TransactionTemplate}, so the listener fires only after that transaction durably commits,
 * exactly as it does when the signing engine publishes the event. The reaction then opens its own
 * transaction and calls back into the real {@code AmendmentSigningService} to reflect the document's
 * terminal transition onto the linked amendment.
 *
 * <p>What is asserted:
 * <ul>
 *   <li><b>Fully-signed ⇒ EFFECTIVE (R2.2, R2.4).</b> A {@code DOCUMENT_FULLY_SIGNED} event on an
 *       {@code AMENDMENT}-type document linked to a {@code SENT_FOR_SIGNING} amendment flips the
 *       amendment to {@code EFFECTIVE} with its {@code effectiveDate} set to the all-parties-signed
 *       date, after the signing commits.</li>
 *   <li><b>Declined/voided ⇒ OPEN (R2.3).</b> A {@code DOCUMENT_SIGNING_DECLINED} (and, separately, a
 *       {@code DOCUMENT_VOIDED}) event reopens the amendment to {@code OPEN}, clears the linked
 *       document, and leaves its lines intact.</li>
 *   <li><b>A thrown reaction leaves the signing committed (R2.4, R7.6).</b> When the reaction's effect
 *       application fails (here, a net schedule shortening that would move the project end date before
 *       its start date — the {@code error.amendment.endDateInvalid} guard throws inside the service),
 *       the best-effort listener logs and swallows it: the committed document-SIGNED state survives,
 *       and the amendment is NOT corrupted (it stays {@code SENT_FOR_SIGNING}, no partial end-date
 *       shift).</li>
 * </ul>
 *
 * <p>Harness mirrors {@code MarginMatrixServiceIntegrationTest}: {@code @SpringBootTest(MOCK)} +
 * {@code @Testcontainers} + {@code @ActiveProfiles("integration-test")} where Liquibase is disabled
 * and Hibernate {@code create-drop} builds the schema from the mapped entities — so no seed data
 * pre-populates and this test seeds its own {@code AMENDMENT} document type, project, user, linked
 * document (with its signatures), and amendment. Every fixture row carries a unique per-run id
 * (UUID substring) and {@link #cleanUp()} deletes every row this test created (deepest-FK-first), so
 * the suite re-runs without manual DB cleanup.
 *
 * <p>Feature: FOR-06-09-amendment-signing, task 5.2
 *
 * <p>Validates: Requirements 2.2, 2.3, 2.4, 7.6
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@ActiveProfiles("integration-test")
@org.junit.jupiter.api.Tag("Feature: FOR-06-09-amendment-signing, task 5.2: after-commit reaction integration")
class AmendmentSigningReactionIntegrationTest {

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
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private AmendmentSigningService signingService;

    @Autowired
    private ProjectDao projectDao;
    @Autowired
    private RoleDao roleDao;
    @Autowired
    private UserDao userDao;
    @Autowired
    private SignableDocumentTypeDao documentTypeDao;
    @Autowired
    private SignableDocumentDao documentDao;
    @Autowired
    private EstimateAmendmentDao amendmentDao;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    /** Unique run id so fixture codes/names never collide with earlier runs. */
    private final String runId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanUp() {
        tx().executeWithoutResult(status -> {
            exec("delete from AmendmentLineEntity l where l.amendment.project.name like :p");
            exec("delete from EstimateAmendmentEntity a where a.project.name like :p");
            exec("delete from DocumentSignatureEntity s where s.document.project.name like :p");
            exec("delete from SignableDocumentEntity d where d.project.name like :p");
            exec("delete from ProjectEntity pr where pr.name like :p");
            exec("delete from UserEntity u where u.email like :p");
            exec("delete from SignableDocumentTypeEntity t where t.code like :p");
            exec("delete from RoleEntity r where r.code like :p");
        });
    }

    private void exec(String jpql) {
        entityManager.createQuery(jpql).setParameter("p", "%" + runId + "%").executeUpdate();
    }

    // =====================================================================================
    // Tests
    // =====================================================================================

    @Test
    @DisplayName("DOCUMENT_FULLY_SIGNED on a linked AMENDMENT flips the amendment to EFFECTIVE after "
            + "the signing commits, with effectiveDate = all-parties-signed date (R2.2, R2.4)")
    void fullySignedReactionDrivesAmendmentEffective() {
        // A SENT_FOR_SIGNING amendment, linked to an all-SIGNED AMENDMENT document, with a
        // +4-half-day (= +2 day) schedule delta and a comfortably wide project window.
        LocalDate projectStart = LocalDate.of(2026, 1, 1);
        LocalDate projectEnd = LocalDate.of(2026, 6, 1);
        LocalDateTime lastSignedAt = LocalDateTime.of(2026, 2, 10, 14, 30);
        Fixture f = seedLinkedAmendment(projectStart, projectEnd,
                List.of(LocalDateTime.of(2026, 2, 9, 9, 0), lastSignedAt), 4);

        // PROBE: call the service directly to isolate service-vs-event wiring.
        signingService.onDocumentFullySigned(f.documentId);

        EstimateAmendmentEntity reflected = reloadAmendment(f.amendmentId);
        assertThat(reflected.getStatus())
                .as("the fully-signed reaction drives the amendment to EFFECTIVE (R2.2)")
                .isEqualTo(AmendmentStatus.EFFECTIVE);
        assertThat(reflected.getEffectiveDate())
                .as("effectiveDate = the all-parties-signed date = max(signedAt) (R3.1 supporting R2.2)")
                .isEqualTo(lastSignedAt.toLocalDate());

        // The single Project.endDate shift (+4 half-days ⇒ +2 days) was applied exactly once.
        ProjectEntity project = reloadProject(f.projectId);
        assertThat(project.getEndDate())
                .as("the net schedule delta shifted only Project.endDate, once")
                .isEqualTo(projectEnd.plusDays(2));
        assertThat(project.getStartDate()).as("Project.startDate is never mutated").isEqualTo(projectStart);
    }

    @Test
    @DisplayName("DOCUMENT_SIGNING_DECLINED on a linked AMENDMENT reopens the amendment to OPEN, clears "
            + "the link, and leaves its lines intact (R2.3)")
    void declinedReactionReopensAmendment() {
        Fixture f = seedLinkedAmendment(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1),
                List.of(LocalDateTime.of(2026, 2, 9, 9, 0)), 4);

        publishAfterCommit(DocumentNotificationEvent.Trigger.DOCUMENT_SIGNING_DECLINED, f.documentId, f.projectId);

        EstimateAmendmentEntity reopened = reloadAmendment(f.amendmentId);
        assertThat(reopened.getStatus())
                .as("a declined document reopens the amendment for editing (R2.3)")
                .isEqualTo(AmendmentStatus.OPEN);
        assertThat(reopened.getSignableDocument())
                .as("the declined document link is cleared (R2.3)")
                .isNull();
        assertThat(reopened.getLines())
                .as("the amendment lines are left intact so the manager can amend and resend (R2.3)")
                .hasSize(1);
    }

    @Test
    @DisplayName("DOCUMENT_VOIDED on a linked AMENDMENT reopens the amendment to OPEN and clears the "
            + "link (R2.3)")
    void voidedReactionReopensAmendment() {
        Fixture f = seedLinkedAmendment(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1),
                List.of(LocalDateTime.of(2026, 2, 9, 9, 0)), 4);

        publishAfterCommit(DocumentNotificationEvent.Trigger.DOCUMENT_VOIDED, f.documentId, f.projectId);

        EstimateAmendmentEntity reopened = reloadAmendment(f.amendmentId);
        assertThat(reopened.getStatus())
                .as("a voided document reopens the amendment for editing (R2.3)")
                .isEqualTo(AmendmentStatus.OPEN);
        assertThat(reopened.getSignableDocument()).as("the voided document link is cleared (R2.3)").isNull();
    }

    @Test
    @DisplayName("A reaction that throws (invalid end-date shift) is swallowed: the committed signing "
            + "survives and the amendment is not corrupted (R2.4, R7.6)")
    void thrownReactionLeavesSigningCommitted() {
        // A net schedule SHORTENING larger than the remaining window: endDate - start = ~5 months, a
        // -400-half-day (= -200 day) delta moves the end date well before the start, so the service's
        // assertValidRange throws error.amendment.endDateInvalid inside the reaction.
        LocalDate projectStart = LocalDate.of(2026, 1, 1);
        LocalDate projectEnd = LocalDate.of(2026, 6, 1);
        Fixture f = seedLinkedAmendment(projectStart, projectEnd,
                List.of(LocalDateTime.of(2026, 2, 9, 9, 0)), -400);

        // The engine's SIGNED state is already committed (the document is SIGNED in the fixture). The
        // event fires after commit; the reaction throws internally but is best-effort — it must not
        // roll anything back nor propagate.
        publishAfterCommit(DocumentNotificationEvent.Trigger.DOCUMENT_FULLY_SIGNED, f.documentId, f.projectId);

        // The committed document-SIGNED state survives the thrown reaction (R2.4 / R7.6).
        SignableDocumentEntity document = reloadDocument(f.documentId);
        assertThat(document.getStatus())
                .as("the committed signing (document SIGNED) is never rolled back by the thrown reaction")
                .isEqualTo(DocumentStatus.SIGNED);

        // The amendment is not left half-applied: its own effect transaction rolled back, so it stays
        // SENT_FOR_SIGNING with no effectiveDate, and the project end date was never shifted.
        EstimateAmendmentEntity amendment = reloadAmendment(f.amendmentId);
        assertThat(amendment.getStatus())
                .as("the failed effect application does not corrupt the amendment status")
                .isEqualTo(AmendmentStatus.SENT_FOR_SIGNING);
        assertThat(amendment.getEffectiveDate()).as("no effectiveDate on the failed path").isNull();
        assertThat(reloadProject(f.projectId).getEndDate())
                .as("no partial end-date shift on the failed path")
                .isEqualTo(projectEnd);
    }

    // =====================================================================================
    // Event publishing + reloads
    // =====================================================================================

    /**
     * Publishes a {@link DocumentNotificationEvent} from inside a committed transaction so the
     * production {@code AFTER_COMMIT} {@link AmendmentSigningReaction} listener fires exactly as it
     * does when the signing engine publishes the event. The outer commit is the real
     * after-commit boundary this test exercises.
     */
    private void publishAfterCommit(DocumentNotificationEvent.Trigger trigger, Long documentId, Long projectId) {
        tx().executeWithoutResult(status -> eventPublisher.publishEvent(new DocumentNotificationEvent(
                trigger, documentId, projectId,
                AmendmentSigningReaction.AMENDMENT_TYPE_CODE, "Amendment #1", List.of())));
    }

    private EstimateAmendmentEntity reloadAmendment(Long amendmentId) {
        return tx().execute(status -> {
            EstimateAmendmentEntity a = amendmentDao.findById(amendmentId).orElseThrow();
            a.getLines().size(); // force the lazy collection while the session is open
            return a;
        });
    }

    private ProjectEntity reloadProject(Long projectId) {
        return tx().execute(status -> projectDao.findById(projectId).orElseThrow());
    }

    private SignableDocumentEntity reloadDocument(Long documentId) {
        return tx().execute(status -> documentDao.findById(documentId).orElseThrow());
    }

    // =====================================================================================
    // Fixture building
    // =====================================================================================

    /** The ids a test needs, captured after the fixture graph is committed. */
    private static final class Fixture {
        Long projectId;
        Long documentId;
        Long amendmentId;
    }

    /**
     * Seeds, in one committed transaction, a self-contained graph: a role + creating user, an
     * {@code AMENDMENT} document type, a project with the given start/end window, a SIGNED
     * {@link SignableDocumentEntity} of that type with one {@code SIGNED} signature per supplied
     * {@code signedAt}, and a {@code SENT_FOR_SIGNING} {@link EstimateAmendmentEntity} linked to that
     * document carrying a single {@code SCHEDULE_CHANGE} line of {@code scheduleHalfDays}.
     */
    private Fixture seedLinkedAmendment(LocalDate projectStart, LocalDate projectEnd,
                                        List<LocalDateTime> signedAtTimes, int scheduleHalfDays) {
        Fixture f = new Fixture();
        tx().executeWithoutResult(status -> {
            RoleEntity role = persistRole("MANAGER_" + runId);
            UserEntity creator = persistUser("creator-" + runId, "Creator", role);

            SignableDocumentTypeEntity type = persistAmendmentType();

            ProjectEntity project = persistProject(projectStart, projectEnd);

            SignableDocumentEntity document = new SignableDocumentEntity();
            document.setProject(project);
            document.setDocumentType(type);
            document.setStatus(DocumentStatus.SIGNED); // the committed signing state
            document.setTitle("Amendment doc " + runId);
            document.setSourceRef(runId);
            document.setCreatedByUser(creator);
            for (LocalDateTime signedAt : signedAtTimes) {
                DocumentSignatureEntity signature = new DocumentSignatureEntity();
                signature.setDocument(document);
                signature.setSignerRole("CLIENT");
                signature.setMethod(SignatureMethod.PRINT);
                signature.setStatus(SignatureStatus.SIGNED);
                signature.setSignedAt(signedAt);
                document.getSignatures().add(signature);
            }
            document = documentDao.save(document);

            EstimateAmendmentEntity amendment = new EstimateAmendmentEntity();
            amendment.setProject(project);
            amendment.setSeqNo(1);
            amendment.setStatus(AmendmentStatus.SENT_FOR_SIGNING);
            amendment.setSignableDocument(document);
            AmendmentLineEntity line = new AmendmentLineEntity();
            line.setAmendment(amendment);
            line.setChangeType(ChangeType.SCHEDULE_CHANGE);
            line.setScheduleDeltaHalfDays(scheduleHalfDays);
            amendment.getLines().add(line);
            amendment = amendmentDao.save(amendment);

            f.projectId = project.getId();
            f.documentId = document.getId();
            f.amendmentId = amendment.getId();
        });
        return f;
    }

    private RoleEntity persistRole(String code) {
        RoleEntity role = new RoleEntity();
        role.setCode(code);
        role.setNameRU("Роль " + runId);
        role.setNamePL("Rola " + runId);
        role.setSystem(false);
        return roleDao.save(role);
    }

    private UserEntity persistUser(String key, String name, RoleEntity role) {
        UserEntity user = new UserEntity();
        user.setName(name);
        user.setEmail(key + "@example.com");
        user.setRole(role);
        user.setActive(true);
        user.setLocale(java.util.Locale.forLanguageTag("pl"));
        return userDao.save(user);
    }

    private SignableDocumentTypeEntity persistAmendmentType() {
        // The type CODE embeds the runId so cleanup can target it, but the reaction filters on the
        // literal "AMENDMENT" documentTypeCode carried by the event (not the type row), so the event
        // we publish uses AmendmentSigningReaction.AMENDMENT_TYPE_CODE directly.
        SignableDocumentTypeEntity type = new SignableDocumentTypeEntity();
        type.setCode("AMENDMENT_" + runId);
        type.setNamePL("Aneks " + runId);
        type.setNameRU("Анекс " + runId);
        type.setActive(true);
        return documentTypeDao.save(type);
    }

    private ProjectEntity persistProject(LocalDate startDate, LocalDate endDate) {
        ProjectEntity project = new ProjectEntity();
        project.setName("Amendment Signing Reaction IT " + runId + "-" + COUNTER.incrementAndGet());
        project.setStatus(ProjectStatus.ACTIVE);
        project.setStartDate(startDate);
        project.setEndDate(endDate);
        return projectDao.save(project);
    }
}
