package com.foremen.service;

import com.foremen.controller.model.Candidate;
import com.foremen.dao.ProjectDao;
import com.foremen.dao.ProjectMemberDao;
import com.foremen.dao.RoleDao;
import com.foremen.dao.UserDao;
import com.foremen.dao.WorkerTypeDao;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.UserStatus;
import com.foremen.dao.model.WorkerKind;
import com.foremen.service.team.TeamBlock;
import com.foremen.service.team.TeamMemberOrdering;
import jakarta.persistence.EntityManager;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based coverage of FOR-05-09 design <b>Property 10 — candidate lookup is sound against its
 * filters</b> for {@link ProjectMemberService#searchCandidates(Long, String, String, String, int, Integer)}.
 *
 * <p><b>Property 10 (service boundary form).</b> The real row filtering (exclude current members,
 * exclude Inactive_Users, {@code term}/{@code role}/{@code block} predicates) lives in
 * {@link UserDao#searchCandidates} JPQL. At the <em>service</em> boundary, soundness means the
 * service <em>never loosens</em> a filter when translating the request into the DAO call, and it maps
 * the DAO rows to {@link Candidate}s faithfully. Concretely, <i>for any</i> candidate universe and
 * <i>any</i> combination of {@code term}, {@code role}, and {@code block} filters:
 *
 * <ol>
 *   <li><b>term translation</b> — a non-null, non-blank {@code term} is always forwarded as a
 *       non-null predicate, trimmed, lower-cased, and wrapped in {@code %…%}; a {@code null} or
 *       whitespace-only {@code term} is forwarded as {@code null} (no term predicate). The service
 *       never drops a supplied term, never passes a bare (unwrapped) term, and never fabricates a
 *       term when none was supplied;</li>
 *   <li><b>role pass-through</b> — a supplied {@code role} is forwarded verbatim as the DAO
 *       {@code roleCode}; a {@code null} {@code role} forwards {@code null};</li>
 *   <li><b>block translation</b> — {@code WORKERS} &rarr; {@code blockRole=WORKER} with
 *       {@code excludeWorkerClient=false}; {@code CLIENTS} &rarr; {@code blockRole=CLIENT} with
 *       {@code excludeWorkerClient=false}; {@code ADMIN_STAFF} &rarr; {@code excludeWorkerClient=true}
 *       with {@code blockRole=null}; no block &rarr; both relaxed ({@code blockRole=null},
 *       {@code excludeWorkerClient=false}). A block request is therefore always strengthened into the
 *       exact DAO predicate and never left out;</li>
 *   <li><b>faithful mapping</b> — every returned {@link Candidate} carries the source
 *       {@link UserEntity}'s {@code userId}, {@code name}, {@code email}, {@code status},
 *       {@code companyRoleCode}, and a {@code block} equal to
 *       {@link TeamMemberOrdering#blockOf(String)} of that role code; the WORKERS-only
 *       {@code workerKind}/{@code contactPerson} are populated only for a WORKERS candidate and
 *       {@code null} otherwise; the total count is preserved; and the {@link Candidate} record carries
 *       no secret field (NIP/tag/worker-type/password/token/rate/cost).</li>
 * </ol>
 *
 * <p>Modelling choice: the DAO is stubbed to return a controlled set, so this test verifies the
 * service's <em>translation</em> of the filters (what it asks the DAO for) and its <em>mapping</em>
 * of the DAO rows — the two halves the service owns. The caller is authenticated as ADMIN so the
 * step-4 project-access check is bypassed and every generated request reaches the DAO.
 *
 * <p>The test follows the mock-based style of {@link ProjectMemberRoleMismatchPropertyTest}:
 * Mockito-mocked DAOs + {@link ProjectAccessCache}, each property {@code try} building and tearing
 * down its own fixture (jqwik does not run JUnit's {@code @BeforeEach}/{@code @AfterEach}).
 *
 * <p><b>Validates: Requirements 11.1, 11.2, 11.3, 11.11</b>
 */
// Feature: FOR-05-09-team-selection, Property 10
@Tag("Feature: FOR-05-09-team-selection, Property 10")
class CandidateLookupSoundnessPropertyTest {

    private static final long PROJECT_ID = 42L;

    /** The six Assignable_Project_Role codes — a valid {@code role} filter must be one of these (Req 11.8). */
    private static final List<String> ASSIGNABLE_CODES =
            List.of("MANAGER", "FOREMAN", "ESTIMATOR", "FINANCIER", "WORKER", "CLIENT");

    /**
     * Role codes a <em>DAO-returned</em> user row may carry. Besides the six assignable codes this
     * adds {@code SUPERVISOR} — a non-standard admin-staff code — so the mapping leg exercises
     * {@link TeamMemberOrdering#blockOf(String)} for a role outside the canonical set (it must still
     * derive {@link TeamBlock#ADMIN_STAFF}). Row role codes are unrelated to the {@code role} filter,
     * which the service validates separately.
     */
    private static final List<String> ROW_ROLE_CODES =
            List.of("MANAGER", "FOREMAN", "ESTIMATOR", "FINANCIER", "WORKER", "CLIENT", "SUPERVISOR");

    /**
     * Property 10. For an arbitrary universe + arbitrary filters, the service forwards the correctly
     * normalized arguments to the DAO (never loosening a filter) and maps every returned row to a
     * faithful {@link Candidate}.
     */
    @Property(tries = 300)
    @Tag("Feature: FOR-05-09-team-selection, Property 10")
    void candidateLookupTranslatesFiltersSoundlyAndMapsRowsFaithfully(
            @ForAll("universe") @Size(max = 8) List<UserRow> universe,
            @ForAll("term") String term,
            @ForAll("roleFilter") String roleFilter,
            @ForAll("blockFilter") String blockFilter,
            @ForAll @IntRange(min = 0, max = 3) int page) {

        Fixture f = new Fixture();
        try {
            List<UserEntity> rows = universe.stream().map(UserRow::toEntity).toList();
            long total = 100L + rows.size(); // an arbitrary total >= page content, preserved by the service
            Pageable requested = PageRequest.of(page, 20);
            Page<UserEntity> daoPage = new PageImpl<>(rows, requested, total);

            when(f.userDao.searchCandidates(eq(PROJECT_ID), any(), any(), any(), anyBoolean(), any()))
                    .thenReturn(daoPage);

            Page<Candidate> result =
                    f.service.searchCandidates(PROJECT_ID, term, roleFilter, blockFilter, page, null);

            // --- (1)(2)(3) the service never loosens a filter: capture what it asked the DAO for ---
            ArgumentCaptor<String> termArg = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> roleArg = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> blockRoleArg = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Boolean> excludeArg = ArgumentCaptor.forClass(Boolean.class);
            verify(f.userDao).searchCandidates(eq(PROJECT_ID), termArg.capture(), roleArg.capture(),
                    blockRoleArg.capture(), excludeArg.capture(), any(Pageable.class));

            // (1) term translation — trimmed/lower-cased/%-wrapped, or null for a blank/absent term.
            String trimmed = term == null ? null : term.trim();
            if (trimmed == null || trimmed.isEmpty()) {
                assertThat(termArg.getValue())
                        .as("a blank/absent term is forwarded as null (no term predicate)")
                        .isNull();
            } else {
                String expected = "%" + trimmed.toLowerCase(Locale.ROOT) + "%";
                assertThat(termArg.getValue())
                        .as("a supplied term is forwarded trimmed, lower-cased, and %%-wrapped")
                        .isEqualTo(expected);
                // soundness: a non-null term is never dropped and never passed bare.
                assertThat(termArg.getValue()).isNotNull().startsWith("%").endsWith("%");
            }

            // (2) role pass-through — forwarded verbatim (or null).
            assertThat(roleArg.getValue())
                    .as("a supplied role is forwarded verbatim as the DAO roleCode")
                    .isEqualTo(roleFilter);

            // (3) block translation — strengthened into the exact DAO predicate, never left out.
            TeamBlock expectedBlock = blockFilter == null
                    ? null
                    : TeamBlock.valueOf(blockFilter.trim().toUpperCase(Locale.ROOT));
            if (expectedBlock == TeamBlock.WORKERS) {
                assertThat(blockRoleArg.getValue()).isEqualTo("WORKER");
                assertThat(excludeArg.getValue()).isFalse();
            } else if (expectedBlock == TeamBlock.CLIENTS) {
                assertThat(blockRoleArg.getValue()).isEqualTo("CLIENT");
                assertThat(excludeArg.getValue()).isFalse();
            } else if (expectedBlock == TeamBlock.ADMIN_STAFF) {
                assertThat(blockRoleArg.getValue()).isNull();
                assertThat(excludeArg.getValue()).isTrue();
            } else {
                assertThat(blockRoleArg.getValue()).isNull();
                assertThat(excludeArg.getValue()).isFalse();
            }

            // --- (4) faithful mapping of DAO rows to Candidates ---
            assertThat(result.getTotalElements())
                    .as("the DAO total count is preserved")
                    .isEqualTo(total);
            assertThat(result.getContent()).hasSameSizeAs(rows);

            for (int i = 0; i < rows.size(); i++) {
                UserEntity src = rows.get(i);
                Candidate c = result.getContent().get(i);
                String roleCode = src.getRole() == null ? null : src.getRole().getCode();
                TeamBlock derivedBlock = TeamMemberOrdering.blockOf(roleCode);

                assertThat(c.userId()).isEqualTo(src.getId());
                assertThat(c.name()).isEqualTo(src.getName());
                assertThat(c.email()).isEqualTo(src.getEmail());
                assertThat(c.status()).isEqualTo(src.getStatus() == null ? null : src.getStatus().name());
                assertThat(c.companyRoleCode()).isEqualTo(roleCode);
                assertThat(c.block())
                        .as("the Candidate's derived block equals blockOf(companyRoleCode)")
                        .isEqualTo(derivedBlock);

                if (derivedBlock == TeamBlock.WORKERS) {
                    // WORKERS: workerKind defaults PERSON when the entity has none; contactPerson passed through.
                    WorkerKind expectedKind = src.getWorkerKind() == null ? WorkerKind.PERSON : src.getWorkerKind();
                    assertThat(c.workerKind()).isEqualTo(expectedKind);
                    assertThat(c.contactPerson()).isEqualTo(src.getContactPerson());
                } else {
                    // Only WORKERS candidates carry worker attributes.
                    assertThat(c.workerKind()).isNull();
                    assertThat(c.contactPerson()).isNull();
                }
            }

            // The Candidate record never carries a secret / internal field (Req 11.5).
            assertThat(Candidate.class.getRecordComponents())
                    .extracting(RecordComponent::getName)
                    .allSatisfy(n -> assertThat(n.toLowerCase(Locale.ROOT))
                            .doesNotContain("nip")
                            .doesNotContain("tag")
                            .doesNotContain("workertype")
                            .doesNotContain("password")
                            .doesNotContain("token")
                            .doesNotContain("rate")
                            .doesNotContain("cost"));
        } finally {
            f.close();
        }
    }

    // --- Generators -----------------------------------------------------------------------------

    /** A term that is often blank/whitespace, sometimes padded, sometimes mixed case. */
    @Provide
    Arbitrary<String> term() {
        Arbitrary<String> nulls = Arbitraries.just(null);
        Arbitrary<String> blanks = Arbitraries.of("", "   ", "\t ");
        Arbitrary<String> words = Arbitraries.strings()
                .withChars("AaBbZz 09Ñé".toCharArray())
                .ofMinLength(1).ofMaxLength(20)
                .map(s -> "  " + s + " "); // padded so trimming is exercised
        return Arbitraries.oneOf(nulls, blanks, words);
    }

    /**
     * A role filter: {@code null} (no filter) or an Assignable_Project_Role code. A non-assignable
     * code is rejected at step 3 before the DAO is reached (Req 11.8, covered by the example test),
     * so Property 10 — which asserts the DAO translation / mapping — draws only valid role filters.
     */
    @Provide
    Arbitrary<String> roleFilter() {
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.of(ASSIGNABLE_CODES));
    }

    /** A block filter: null or one of the three valid block names (sometimes lower-cased / padded). */
    @Provide
    Arbitrary<String> blockFilter() {
        return Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.of("ADMIN_STAFF", "WORKERS", "CLIENTS", "admin_staff", " workers ", "Clients"));
    }

    /** The arbitrary candidate universe the stubbed DAO returns as its page content. */
    @Provide
    Arbitrary<List<UserRow>> universe() {
        return userRow().list().ofMaxSize(8);
    }

    private Arbitrary<UserRow> userRow() {
        Arbitrary<Long> id = Arbitraries.longs().between(1L, 10_000L);
        Arbitrary<String> name = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(16);
        Arbitrary<String> email = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10).map(s -> s + "@example.com"));
        Arbitrary<UserStatus> status = Arbitraries.of(UserStatus.values());
        Arbitrary<String> role = Arbitraries.of(ROW_ROLE_CODES);
        Arbitrary<WorkerKind> kind = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.of(WorkerKind.values()));
        Arbitrary<String> contact = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12));
        return net.jqwik.api.Combinators.combine(id, name, email, status, role, kind, contact)
                .as(UserRow::new);
    }

    /** A plain carrier for generated user data, mapped to a {@link UserEntity} per try. */
    record UserRow(Long id, String name, String email, UserStatus status, String roleCode,
                   WorkerKind workerKind, String contactPerson) {

        UserEntity toEntity() {
            RoleEntity role = new RoleEntity();
            role.setCode(roleCode);
            role.setNamePL(roleCode);
            role.setNameRU(roleCode);
            UserEntity user = new UserEntity();
            user.setId(id);
            user.setName(name);
            user.setEmail(email);
            user.setStatus(status);
            user.setRole(role);
            user.setActive(true);
            user.setWorkerKind(workerKind);
            user.setContactPerson(contactPerson);
            return user;
        }
    }

    // --- Fixture --------------------------------------------------------------------------------

    /**
     * A self-contained set of Mockito mocks + a {@link ProjectMemberService} under test, with the
     * caller authenticated as ADMIN so the project-access gate is bypassed and every generated
     * request reaches the DAO. jqwik re-runs the property method per {@code try} without JUnit's
     * {@code @BeforeEach}/{@code @AfterEach}, so each invocation builds and tears down its own
     * fixture; {@link #close()} clears the security context.
     */
    private static final class Fixture {
        final ProjectMemberDao projectMemberDao = mock(ProjectMemberDao.class);
        final UserDao userDao = mock(UserDao.class);
        final RoleDao roleDao = mock(RoleDao.class);
        final ProjectDao projectDao = mock(ProjectDao.class);
        final WorkerTypeDao workerTypeDao = mock(WorkerTypeDao.class);
        final ProjectAccessCache projectAccessCache = mock(ProjectAccessCache.class);
        final com.foremen.service.model.mapper.ProjectMemberServiceMapper projectMemberServiceMapper =
                mock(com.foremen.service.model.mapper.ProjectMemberServiceMapper.class);
        final com.foremen.service.audit.AuditLogDao auditLogDao =
                mock(com.foremen.service.audit.AuditLogDao.class);
        final EntityManager entityManager = mock(EntityManager.class);
        final com.foremen.service.offer.NotificationService notificationService =
                mock(com.foremen.service.offer.NotificationService.class);

        final ProjectMemberService service = new ProjectMemberService(
                projectMemberDao, userDao, roleDao, projectDao, workerTypeDao,
                projectAccessCache, projectMemberServiceMapper, auditLogDao, entityManager,
                notificationService);

        Fixture() {
            GrantedAuthority admin = new SimpleGrantedAuthority("ROLE_ADMIN");
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("1", "n/a", List.of(admin)));
        }

        void close() {
            SecurityContextHolder.clearContext();
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
    }
}
