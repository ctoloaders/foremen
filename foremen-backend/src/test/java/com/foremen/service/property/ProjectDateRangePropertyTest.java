package com.foremen.service.property;

import java.time.LocalDate;

import com.foremen.dao.ProjectDao;
import com.foremen.dao.RoleDao;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ClientRegistrationService;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.ProjectMemberService;
import com.foremen.service.ProjectService;
import com.foremen.service.audit.AuditLogDao;
import com.foremen.service.google.GooglePlacesService;
import com.foremen.service.model.mapper.ProjectServiceMapper;

import jakarta.persistence.EntityManager;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.time.api.Dates;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Property-based test for the date-range guard enforced by
 * {@link ProjectService#validateDateRange(LocalDate, LocalDate)} (Requirements 3.6, 8.8).
 *
 * <p>The cross-field rule is a pure function of the two supplied dates, so these tests instantiate
 * {@link ProjectService} with Mockito mocks for every collaborator and call {@code validateDateRange}
 * directly. No collaborator is touched by the guard, so the mocks need no stubbing.
 *
 * Property 5: End date must not precede start date. For any generated {@code (startDate, endDate)}
 * pair: when both are present and {@code endDate < startDate}, {@code validateDateRange} throws
 * {@link ForemenApiException} (HTTP 400, code {@code error.project.date.range}); otherwise
 * ({@code endDate >= startDate}, or either date {@code null}) it does not throw.
 *
 * Feature: FOR-04-13-project, Property 5
 */
@Tag("Feature: FOR-04-13-project, Property 5")
class ProjectDateRangePropertyTest {

    private static final String DATE_RANGE_CODE = "error.project.date.range";

    private static ProjectService newService() {
        return new ProjectService(
                mock(ProjectDao.class),
                mock(ProjectServiceMapper.class),
                mock(ProjectAccessCache.class),
                mock(AuditLogDao.class),
                mock(EntityManager.class),
                mock(ProjectMemberService.class),
                mock(ClientRegistrationService.class),
                mock(RoleDao.class),
                mock(GooglePlacesService.class));
    }

    /** Any calendar date within a broad window (jqwik-time). */
    @Provide
    Arbitrary<LocalDate> dates() {
        return Dates.dates().between(LocalDate.of(1970, 1, 1), LocalDate.of(2100, 12, 31));
    }

    /** A date, or {@code null} — to exercise the open-ended (single-bound) cases. */
    @Provide
    Arbitrary<LocalDate> nullableDates() {
        return Arbitraries.oneOf(
                Arbitraries.just((LocalDate) null),
                Dates.dates().between(LocalDate.of(1970, 1, 1), LocalDate.of(2100, 12, 31)));
    }

    /** Ordered pairs where {@code endDate} is strictly before {@code startDate} (the reject case). */
    @Provide
    Arbitrary<LocalDate[]> endBeforeStartPairs() {
        return Combinators.combine(dates(), dates())
                .as((a, b) -> a.isBefore(b) ? new LocalDate[] {b, a} : new LocalDate[] {a, b})
                .filter(pair -> pair[1].isBefore(pair[0]));
    }

    /** Ordered pairs where {@code endDate} is not before {@code startDate} (the accept case). */
    @Provide
    Arbitrary<LocalDate[]> endNotBeforeStartPairs() {
        return Combinators.combine(dates(), dates())
                .as((a, b) -> a.isBefore(b) ? new LocalDate[] {a, b} : new LocalDate[] {b, a})
                .filter(pair -> !pair[1].isBefore(pair[0]));
    }

    // Feature: FOR-04-13-project, Property 5 (reject branch)
    // Both dates present and endDate strictly before startDate => HTTP 400 error.project.date.range.
    // Validates: Requirements 3.6, 8.8
    @Property(tries = 100)
    void endBeforeStartIsRejected(@ForAll("endBeforeStartPairs") LocalDate[] pair) {
        LocalDate startDate = pair[0];
        LocalDate endDate = pair[1];
        ProjectService service = newService();

        assertThatThrownBy(() -> service.validateDateRange(startDate, endDate))
                .isInstanceOfSatisfying(ForemenApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getMessageCode()).isEqualTo(DATE_RANGE_CODE);
                });
    }

    // Feature: FOR-04-13-project, Property 5 (accept branch, both present)
    // Both dates present and endDate >= startDate => no throw.
    // Validates: Requirements 3.6, 8.8
    @Property(tries = 100)
    void endNotBeforeStartIsAccepted(@ForAll("endNotBeforeStartPairs") LocalDate[] pair) {
        LocalDate startDate = pair[0];
        LocalDate endDate = pair[1];
        ProjectService service = newService();

        assertThatCode(() -> service.validateDateRange(startDate, endDate))
                .doesNotThrowAnyException();
    }

    // Feature: FOR-04-13-project, Property 5 (open-ended branch)
    // At least one date null (open-ended range) => never throws, regardless of the other bound.
    // Validates: Requirements 3.6, 8.8
    @Property(tries = 100)
    void openEndedRangeIsAccepted(@ForAll("nullableDates") LocalDate startDate,
                                  @ForAll("nullableDates") LocalDate endDate) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            return; // covered by endBeforeStartIsRejected; skip the reject case here
        }
        ProjectService service = newService();

        assertThatCode(() -> service.validateDateRange(startDate, endDate))
                .doesNotThrowAnyException();
    }
}
