package com.foremen.service.clientmessage;

import static com.foremen.config.persistence.LocaleAttributeConverter.PL;
import static com.foremen.config.persistence.LocaleAttributeConverter.RU;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.foremen.dao.ClientMessageDao;
import com.foremen.dao.ClientMessageTranslationDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.model.AuthorSide;
import com.foremen.dao.model.ClientMessageEntity;
import com.foremen.dao.model.ClientMessageTranslationEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.ProjectStatus;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.clientmessage.mapper.ClientMessageDtoMapper;
import com.foremen.service.model.mapper.ClientMessageServiceMapper;
import com.foremen.service.translation.MessageTranslationService;

import jakarta.persistence.EntityManager;

/**
 * FOR-06-12 task 10.1 — plain Mockito unit tests for {@link ClientMessageService}, the per-project
 * client&harr;staff conversation lifecycle service. The DAOs, the media/translation seams, the
 * mappers, and the {@link EntityManager} are mocked; the caller user id / role authority is driven
 * through {@link SecurityContextHolder}, mirroring {@code WorkReportServiceTest} /
 * {@code WorkerPayoutServiceTest}. The real {@link ClientMessageDtoMapper} is composed (a pure
 * component) so the author-locale projection returned by {@code post}/{@code markRead} is the
 * production one.
 *
 * <p>Covers the design's service-side correctness cases:
 * <ul>
 *   <li><b>authorSide derivation (R2.1)</b> — a CLIENT project role posts a {@code CLIENT}-side
 *       message; any other role (MANAGER) posts a {@code STAFF}-side message; never from the
 *       request;</li>
 *   <li><b>MANAGER-only reply + mark-read (R3)</b> — a STAFF post by a non-MANAGER is 403
 *       {@code error.clientMessage.reply.notManager}; a {@code markRead} by a non-MANAGER is 403
 *       {@code error.clientMessage.read.notManager}; ADMIN bypasses both;</li>
 *   <li><b>first-read-wins (R5.1/R5.2/R5.3)</b> — {@code markRead} sets {@code readAt}/{@code readBy}
 *       only on an unread {@code CLIENT}-side message; an already-read or {@code STAFF}-side message
 *       is a no-op;</li>
 *   <li><b>empty-post (R2.2)</b> — blank text AND no media is 400 {@code error.clientMessage.empty};</li>
 *   <li><b>translation fallback (R6.3/R10.4)</b> — an empty {@code translate} skips that locale; a
 *       {@code translate} that throws is swallowed and never surfaces;</li>
 *   <li><b>unread-count projection (R5.4)</b> — a MANAGER reader sees the unread {@code CLIENT}-side
 *       count; a non-MANAGER reader sees zero.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClientMessageServiceTest {

    private static final Long PROJECT_ID = 42L;
    private static final Long CLIENT_ID = 100L;
    private static final Long MANAGER_ID = 200L;
    private static final Long FOREMAN_ID = 300L;
    private static final Long ADMIN_ID = 1L;

    @Mock
    private ClientMessageDao clientMessageDao;
    @Mock
    private ClientMessageTranslationDao translationDao;
    @Mock
    private ProjectMemberDao projectMemberDao;
    @Mock
    private ProjectAccessCache projectAccessCache;
    @Mock
    private ClientMessageMediaService mediaService;
    @Mock
    private MessageTranslationService translationService;
    @Mock
    private ClientMessageServiceMapper serviceMapper;
    @Mock
    private AuditLogDao auditLogDao;
    @Mock
    private EntityManager entityManager;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    // The read projection is pure; compose the REAL mapper so post()/markRead() return a production DTO.
    private final ClientMessageDtoMapper dtoMapper = new ClientMessageDtoMapper();

    private ClientMessageService service;

    @BeforeEach
    void setUp() {
        service = new ClientMessageService(
                clientMessageDao, translationDao, projectMemberDao, projectAccessCache,
                mediaService, translationService, dtoMapper, serviceMapper, auditLogDao,
                entityManager, eventPublisher);

        // save() echoes its argument (assigning an id so the committed entity is the one under test).
        lenient().when(clientMessageDao.save(any(ClientMessageEntity.class)))
                .thenAnswer(inv -> {
                    ClientMessageEntity m = inv.getArgument(0);
                    if (m.getId() == null) {
                        m.setId(999L);
                    }
                    return m;
                });
        // getReference(type, id) returns a lightweight stub carrying only its id.
        lenient().when(entityManager.getReference(any(), any()))
                .thenAnswer(inv -> referenceStub(inv.getArgument(0), (Long) inv.getArgument(1)));
        // find(UserEntity, id) is used to resolve the caller's preferred locale; return a stub (PL).
        lenient().when(entityManager.find(eq(UserEntity.class), any()))
                .thenAnswer(inv -> user((Long) inv.getArgument(1), PL));
        // No media by default.
        lenient().when(mediaService.signedUrls(any(ClientMessageEntity.class)))
                .thenReturn(java.util.Map.of());
        lenient().when(mediaService.attachAll(any(), any(), any())).thenReturn(List.of());
        // detectLocale defaults to the author-preferred (PL) unless the test overrides it.
        lenient().when(translationService.detectLocale(any(), any()))
                .thenAnswer(inv -> inv.getArgument(1) == null ? PL : inv.getArgument(1));
        lenient().when(translationService.translate(any(), any(), any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // =======================================================================
    // authorSide derivation (R2.1)
    // =======================================================================

    @Nested
    @DisplayName("authorSide derivation (R2.1)")
    class AuthorSideDerivation {

        @Test
        @DisplayName("a CLIENT project role posts a CLIENT-side message")
        void clientRoleProducesClientSide() {
            authenticate(CLIENT_ID, "ROLE_CLIENT");
            stubActiveProject();
            stubProjectAccess(CLIENT_ID);
            stubMemberRole(CLIENT_ID, "CLIENT");

            service.post(PROJECT_ID, new PostMessageRequest("dzień dobry", null), null);

            assertThat(capturedSave().getAuthorSide()).isEqualTo(AuthorSide.CLIENT);
        }

        @Test
        @DisplayName("a MANAGER project role posts a STAFF-side message")
        void managerRoleProducesStaffSide() {
            authenticate(MANAGER_ID, "ROLE_MANAGER");
            stubActiveProject();
            stubProjectAccess(MANAGER_ID);
            stubMemberRole(MANAGER_ID, "MANAGER");

            service.post(PROJECT_ID, new PostMessageRequest("odpowiedź", null), null);

            assertThat(capturedSave().getAuthorSide()).isEqualTo(AuthorSide.STAFF);
        }

        @Test
        @DisplayName("authorSide is derived from the role, never from the request")
        void authorSideIgnoresRequest() {
            authenticate(CLIENT_ID, "ROLE_CLIENT");
            stubActiveProject();
            stubProjectAccess(CLIENT_ID);
            stubMemberRole(CLIENT_ID, "CLIENT");

            // PostMessageRequest carries no authorSide at all; the side comes solely from the role.
            service.post(PROJECT_ID, new PostMessageRequest("pytanie", null), null);

            assertThat(capturedSave().getAuthorSide()).isEqualTo(AuthorSide.CLIENT);
        }
    }

    // =======================================================================
    // MANAGER-only reply + mark-read (R3)
    // =======================================================================

    @Nested
    @DisplayName("MANAGER-only reply + mark-read (R3)")
    class ManagerOnlyAuthority {

        @Test
        @DisplayName("a non-MANAGER staff reply is rejected 403 error.clientMessage.reply.notManager (R3.2)")
        void foremanReplyRejected() {
            authenticate(FOREMAN_ID, "ROLE_FOREMAN");
            stubActiveProject();
            stubProjectAccess(FOREMAN_ID);
            stubMemberRole(FOREMAN_ID, "FOREMAN"); // admin staff, not MANAGER -> STAFF side

            assertApiError(() -> service.post(PROJECT_ID, new PostMessageRequest("reply", null), null),
                    HttpStatus.FORBIDDEN, "error.clientMessage.reply.notManager");
            verify(clientMessageDao, never()).save(any());
        }

        @Test
        @DisplayName("an ADMIN project-role caller may reply (STAFF side, bypasses the MANAGER rule)")
        void adminMayReply() {
            authenticate(ADMIN_ID, "ROLE_ADMIN");
            stubActiveProject();
            stubProjectAccess(ADMIN_ID);
            // post() grants the STAFF reply when the author's project role code is ADMIN (or MANAGER):
            // isManagerOrAdmin("ADMIN") bypasses the MANAGER-only rule.
            stubMemberRole(ADMIN_ID, "ADMIN");

            service.post(PROJECT_ID, new PostMessageRequest("admin reply", null), null);

            assertThat(capturedSave().getAuthorSide()).isEqualTo(AuthorSide.STAFF);
        }

        @Test
        @DisplayName("a global security ADMIN with NO project membership may reply (STAFF side)")
        void securityAdminWithoutMembershipMayReply() {
            authenticate(ADMIN_ID, "ROLE_ADMIN");
            stubActiveProject();
            stubProjectAccess(ADMIN_ID);
            // Intentionally NO stubMemberRole: the ADMIN is not a project member, so
            // projectRoleCode resolves to null and the post is STAFF-side. The reply guard must
            // still bypass through the global security ADMIN authority (isCallerAdmin()), not
            // only the project-role code -- consistent with markRead.

            service.post(PROJECT_ID, new PostMessageRequest("admin reply, no membership", null), null);

            assertThat(capturedSave().getAuthorSide()).isEqualTo(AuthorSide.STAFF);
        }

        @Test
        @DisplayName("a non-MANAGER markRead is rejected 403 error.clientMessage.read.notManager (R3.3)")
        void foremanMarkReadRejected() {
            authenticate(FOREMAN_ID, "ROLE_FOREMAN");
            ClientMessageEntity msg = clientMessage(500L, AuthorSide.CLIENT, null);
            stubProjectAccessForMessage();
            when(clientMessageDao.findById(500L)).thenReturn(Optional.of(msg));
            stubMemberRole(FOREMAN_ID, "FOREMAN");

            assertApiError(() -> service.markRead(500L),
                    HttpStatus.FORBIDDEN, "error.clientMessage.read.notManager");
            assertThat(msg.getReadAt()).isNull();
        }

        @Test
        @DisplayName("an ADMIN caller may markRead (bypasses the MANAGER rule)")
        void adminMayMarkRead() {
            authenticate(ADMIN_ID, "ROLE_ADMIN");
            ClientMessageEntity msg = clientMessage(500L, AuthorSide.CLIENT, null);
            when(clientMessageDao.findById(500L)).thenReturn(Optional.of(msg));

            service.markRead(500L);

            assertThat(msg.getReadAt()).isNotNull();
        }
    }

    // =======================================================================
    // first-read-wins (R5.1 / R5.2 / R5.3)
    // =======================================================================

    @Nested
    @DisplayName("first-read-wins (R5)")
    class FirstReadWins {

        @Test
        @DisplayName("a MANAGER's first read of an unread CLIENT message sets readAt + readBy (R5.1)")
        void firstReadSetsReadState() {
            authenticate(MANAGER_ID, "ROLE_MANAGER");
            ClientMessageEntity msg = clientMessage(500L, AuthorSide.CLIENT, null);
            when(clientMessageDao.findById(500L)).thenReturn(Optional.of(msg));
            stubProjectAccessForMessage();
            stubMemberRole(MANAGER_ID, "MANAGER");

            service.markRead(500L);

            assertThat(msg.getReadAt()).isNotNull();
            assertThat(msg.getReadBy()).isNotNull();
            assertThat(msg.getReadBy().getId()).isEqualTo(MANAGER_ID);
        }

        @Test
        @DisplayName("an already-read CLIENT message is a no-op: readAt/readBy unchanged (R5.2)")
        void alreadyReadIsNoOp() {
            authenticate(MANAGER_ID, "ROLE_MANAGER");
            OffsetDateTime originalReadAt = OffsetDateTime.now().minusDays(1);
            ClientMessageEntity msg = clientMessage(500L, AuthorSide.CLIENT, originalReadAt);
            UserEntity firstReader = user(777L, PL);
            msg.setReadBy(firstReader);
            when(clientMessageDao.findById(500L)).thenReturn(Optional.of(msg));
            stubProjectAccessForMessage();
            stubMemberRole(MANAGER_ID, "MANAGER");

            service.markRead(500L);

            assertThat(msg.getReadAt()).isEqualTo(originalReadAt);
            assertThat(msg.getReadBy().getId()).isEqualTo(777L); // first read wins
            verify(clientMessageDao, never()).save(any());
        }

        @Test
        @DisplayName("a STAFF-side message carries no unread state: markRead is a no-op (R5.3)")
        void staffMessageIsNoOp() {
            authenticate(MANAGER_ID, "ROLE_MANAGER");
            ClientMessageEntity msg = clientMessage(500L, AuthorSide.STAFF, null);
            when(clientMessageDao.findById(500L)).thenReturn(Optional.of(msg));
            stubProjectAccessForMessage();
            stubMemberRole(MANAGER_ID, "MANAGER");

            service.markRead(500L);

            assertThat(msg.getReadAt()).isNull();
            verify(clientMessageDao, never()).save(any());
        }
    }

    // =======================================================================
    // empty-post (R2.2)
    // =======================================================================

    @Nested
    @DisplayName("empty-post (R2.2)")
    class EmptyPost {

        @Test
        @DisplayName("blank text AND no media is rejected 400 error.clientMessage.empty")
        void blankTextNoMediaRejected() {
            authenticate(CLIENT_ID, "ROLE_CLIENT");
            stubProjectAccess(CLIENT_ID);
            stubMemberRole(CLIENT_ID, "CLIENT");

            assertApiError(() -> service.post(PROJECT_ID, new PostMessageRequest("   ", null), null),
                    HttpStatus.BAD_REQUEST, "error.clientMessage.empty");
            verify(clientMessageDao, never()).save(any());
        }

        @Test
        @DisplayName("a null request body is rejected 400 error.clientMessage.empty when no media")
        void nullBodyNoMediaRejected() {
            authenticate(CLIENT_ID, "ROLE_CLIENT");
            stubProjectAccess(CLIENT_ID);
            stubMemberRole(CLIENT_ID, "CLIENT");

            assertApiError(() -> service.post(PROJECT_ID, null, null),
                    HttpStatus.BAD_REQUEST, "error.clientMessage.empty");
            verify(clientMessageDao, never()).save(any());
        }
    }

    // =======================================================================
    // translation fallback (R6.3 / R10.4)
    // =======================================================================

    @Nested
    @DisplayName("translation fallback — empty skipped, failure swallowed (R6.3, R10.4)")
    class TranslationFallback {

        @Test
        @DisplayName("the original-locale mirror is always persisted (machine=false)")
        void originalMirrorPersisted() {
            authenticate(CLIENT_ID, "ROLE_CLIENT");
            stubActiveProject();
            stubProjectAccess(CLIENT_ID);
            stubMemberRole(CLIENT_ID, "CLIENT");
            when(translationService.detectLocale(any(), any())).thenReturn(PL);

            service.post(PROJECT_ID, new PostMessageRequest("dzień dobry", null), null);

            ClientMessageTranslationEntity mirror = capturedTranslations().stream()
                    .filter(t -> !t.isMachine())
                    .findFirst()
                    .orElseThrow();
            assertThat(mirror.getLocale()).isEqualTo(PL);
            assertThat(mirror.getText()).isEqualTo("dzień dobry");
        }

        @Test
        @DisplayName("an empty translate() result leaves the other locale untranslated (original-only)")
        void emptyTranslateSkipsLocale() {
            authenticate(CLIENT_ID, "ROLE_CLIENT");
            stubActiveProject();
            stubProjectAccess(CLIENT_ID);
            stubMemberRole(CLIENT_ID, "CLIENT");
            when(translationService.detectLocale(any(), any())).thenReturn(PL);
            when(translationService.translate(any(), eq(PL), any())).thenReturn(Optional.empty());

            service.post(PROJECT_ID, new PostMessageRequest("dzień dobry", null), null);

            // only the machine=false original mirror is stored; no machine=true row for RU.
            assertThat(capturedTranslations()).noneMatch(ClientMessageTranslationEntity::isMachine);
        }

        @Test
        @DisplayName("a translate() that throws is swallowed — post succeeds, no RU row, never rethrown")
        void translateFailureSwallowed() {
            authenticate(CLIENT_ID, "ROLE_CLIENT");
            stubActiveProject();
            stubProjectAccess(CLIENT_ID);
            stubMemberRole(CLIENT_ID, "CLIENT");
            when(translationService.detectLocale(any(), any())).thenReturn(PL);
            when(translationService.translate(any(), eq(PL), any()))
                    .thenThrow(new RuntimeException("provider down"));

            // must not throw.
            service.post(PROJECT_ID, new PostMessageRequest("dzień dobry", null), null);

            assertThat(capturedTranslations()).noneMatch(ClientMessageTranslationEntity::isMachine);
            // the post still committed + the notification event was published.
            verify(clientMessageDao).save(any());
            verify(eventPublisher).publishEvent(any(ClientMessageNotificationEvent.class));
        }

        @Test
        @DisplayName("a present translate() result is persisted as a machine=true row for the other locale")
        void presentTranslatePersistedMachineTrue() {
            authenticate(CLIENT_ID, "ROLE_CLIENT");
            stubActiveProject();
            stubProjectAccess(CLIENT_ID);
            stubMemberRole(CLIENT_ID, "CLIENT");
            when(translationService.detectLocale(any(), any())).thenReturn(PL);
            when(translationService.translate(eq("dzień dobry"), eq(PL), any()))
                    .thenReturn(Optional.of("добрый день"));

            service.post(PROJECT_ID, new PostMessageRequest("dzień dobry", null), null);

            ClientMessageTranslationEntity machine = capturedTranslations().stream()
                    .filter(ClientMessageTranslationEntity::isMachine)
                    .findFirst()
                    .orElseThrow();
            assertThat(machine.getLocale()).isEqualTo(RU);
            assertThat(machine.getText()).isEqualTo("добрый день");
        }
    }

    // =======================================================================
    // unread-count projection (R5.4)
    // =======================================================================

    @Nested
    @DisplayName("per-reader unread count (R5.4)")
    class UnreadCount {

        @Test
        @DisplayName("a MANAGER reader counts unread CLIENT-side messages")
        void managerSeesUnreadCount() {
            authenticate(MANAGER_ID, "ROLE_MANAGER");
            stubProjectAccess(MANAGER_ID);
            stubMemberRole(MANAGER_ID, "MANAGER");
            when(clientMessageDao.findThreadByProjectId(PROJECT_ID)).thenReturn(List.of(
                    clientMessage(1L, AuthorSide.CLIENT, null),              // unread
                    clientMessage(2L, AuthorSide.CLIENT, OffsetDateTime.now()), // read
                    clientMessage(3L, AuthorSide.STAFF, null)));             // staff, not counted

            ThreadView view = service.listThread(PROJECT_ID, PL);

            assertThat(view.unreadCount()).isEqualTo(1L);
        }

        @Test
        @DisplayName("a non-MANAGER reader sees unread count 0 (only MANAGER participates in the marker)")
        void nonManagerSeesZero() {
            authenticate(FOREMAN_ID, "ROLE_FOREMAN");
            stubProjectAccess(FOREMAN_ID);
            stubMemberRole(FOREMAN_ID, "FOREMAN");
            when(clientMessageDao.findThreadByProjectId(PROJECT_ID)).thenReturn(List.of(
                    clientMessage(1L, AuthorSide.CLIENT, null),
                    clientMessage(2L, AuthorSide.CLIENT, null)));

            ThreadView view = service.listThread(PROJECT_ID, PL);

            assertThat(view.unreadCount()).isZero();
        }
    }

    // =======================================================================
    // helpers
    // =======================================================================

    private void stubActiveProject() {
        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT_ID);
        project.setStatus(ProjectStatus.ACTIVE);
        lenient().when(entityManager.find(ProjectEntity.class, PROJECT_ID)).thenReturn(project);
    }

    private void stubProjectAccess(Long userId) {
        lenient().when(projectAccessCache.get(userId)).thenReturn(Set.of(PROJECT_ID));
    }

    private void stubProjectAccessForMessage() {
        // markRead resolves access through the inherited by-id guard (ProjectScopedService): it
        // (1) reads the caller's accessible set from the cache, and (2) resolves the message's
        // owning project id via a Criteria query over the mocked EntityManager. Stub both so a
        // non-ADMIN caller's project-scope check passes and reaches the MANAGER-only / first-read
        // logic under test.
        lenient().when(projectAccessCache.get(any())).thenReturn(Set.of(PROJECT_ID));
        stubGetProjectIdReturns(PROJECT_ID);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubGetProjectIdReturns(Long projectId) {
        jakarta.persistence.criteria.CriteriaBuilder cb =
                org.mockito.Mockito.mock(jakarta.persistence.criteria.CriteriaBuilder.class);
        jakarta.persistence.criteria.CriteriaQuery cq =
                org.mockito.Mockito.mock(jakarta.persistence.criteria.CriteriaQuery.class);
        jakarta.persistence.criteria.Root root =
                org.mockito.Mockito.mock(jakarta.persistence.criteria.Root.class);
        jakarta.persistence.criteria.Path path =
                org.mockito.Mockito.mock(jakarta.persistence.criteria.Path.class);
        jakarta.persistence.criteria.Join join =
                org.mockito.Mockito.mock(jakarta.persistence.criteria.Join.class);
        jakarta.persistence.criteria.Predicate predicate =
                org.mockito.Mockito.mock(jakarta.persistence.criteria.Predicate.class);
        jakarta.persistence.TypedQuery typedQuery =
                org.mockito.Mockito.mock(jakarta.persistence.TypedQuery.class);

        lenient().when(entityManager.getCriteriaBuilder()).thenReturn(cb);
        lenient().when(cb.createQuery(Long.class)).thenReturn(cq);
        lenient().when(cq.from(any(Class.class))).thenReturn(root);
        // resolvePath("project.id") traverses root.join("project").get("id"); return paths freely.
        lenient().when(root.join(any(String.class))).thenReturn(join);
        lenient().when(root.get(any(String.class))).thenReturn(path);
        lenient().when(join.get(any(String.class))).thenReturn(path);
        lenient().when(path.as(any(Class.class))).thenReturn(path);
        lenient().when(cq.select(any())).thenReturn(cq);
        lenient().when(cb.equal(any(), any(Object.class))).thenReturn(predicate);
        lenient().when(cq.where(any(jakarta.persistence.criteria.Predicate.class))).thenReturn(cq);
        lenient().when(entityManager.createQuery(cq)).thenReturn(typedQuery);
        lenient().when(typedQuery.setMaxResults(org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(typedQuery);
        lenient().when(typedQuery.getResultList()).thenReturn(List.of(projectId));
    }

    private void stubMemberRole(Long userId, String roleCode) {
        RoleEntity role = new RoleEntity();
        role.setCode(roleCode);
        ProjectMemberEntity member = new ProjectMemberEntity();
        member.setProjectRole(role);
        lenient().when(projectMemberDao.findByUserIdAndProjectId(userId, PROJECT_ID))
                .thenReturn(Optional.of(member));
    }

    private ClientMessageEntity clientMessage(Long id, AuthorSide side, OffsetDateTime readAt) {
        ClientMessageEntity m = new ClientMessageEntity();
        m.setId(id);
        m.setAuthorSide(side);
        m.setOriginalText("text-" + id);
        m.setOriginalLocale(PL);
        m.setReadAt(readAt);
        ProjectEntity project = new ProjectEntity();
        project.setId(PROJECT_ID);
        project.setStatus(ProjectStatus.ACTIVE);
        m.setProject(project);
        m.setAuthor(user(CLIENT_ID, PL));
        return m;
    }

    private ClientMessageEntity capturedSave() {
        ArgumentCaptor<ClientMessageEntity> captor = ArgumentCaptor.forClass(ClientMessageEntity.class);
        verify(clientMessageDao).save(captor.capture());
        return captor.getValue();
    }

    private List<ClientMessageTranslationEntity> capturedTranslations() {
        ArgumentCaptor<ClientMessageTranslationEntity> captor =
                ArgumentCaptor.forClass(ClientMessageTranslationEntity.class);
        verify(translationDao, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    private static UserEntity user(Long id, Locale locale) {
        UserEntity u = new UserEntity();
        u.setId(id);
        u.setLocale(locale);
        return u;
    }

    @SuppressWarnings("unchecked")
    private static <T> T referenceStub(Class<?> type, Long id) {
        try {
            Object stub = type.getDeclaredConstructor().newInstance();
            type.getMethod("setId", Long.class).invoke(stub, id);
            return (T) stub;
        } catch (Exception e) {
            throw new IllegalStateException("cannot build reference stub for " + type, e);
        }
    }

    private static void authenticate(Long userId, String authority) {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                String.valueOf(userId), "pw", List.of(new SimpleGrantedAuthority(authority)));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static void assertApiError(Runnable action, HttpStatus status, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ForemenApiException.class)
                .satisfies(ex -> {
                    ForemenApiException api = (ForemenApiException) ex;
                    assertThat(api.getStatus().value()).isEqualTo(status.value());
                    assertThat(api.getMessageCode()).isEqualTo(code);
                });
    }
}
