package com.foremen.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

import com.foremen.dao.model.ConsumptionBranch;
import com.foremen.dao.model.WorkItemEntity;
import com.foremen.exception.ForemenApiException;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Unit test of the (operator, value) &rarr; has / has-none matrix of
 * {@link AbstractWorkItemConsumptionCountQueryResolver} and its two concrete subclasses
 * (FOR-04-19-work-catalog-rework, task 1.4).
 *
 * <p><b>Validates: Requirements 10.3</b> (covers 2.5, 2.6, 7.3, 7.4).
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class WorkItemConsumptionCountQueryResolverTest {

    private static final String CONSTRUCTION = WorkItemConstructionConsumptionCountQueryResolver.SEGMENT;
    private static final String FINISHING = WorkItemFinishingConsumptionCountQueryResolver.SEGMENT;

    private final CustomQueryResolverRegistry registry = new CustomQueryResolverRegistry();
    private final WorkItemConstructionConsumptionCountQueryResolver construction =
            new WorkItemConstructionConsumptionCountQueryResolver(registry);
    private final WorkItemFinishingConsumptionCountQueryResolver finishing =
            new WorkItemFinishingConsumptionCountQueryResolver(registry);

    private AbstractWorkItemConsumptionCountQueryResolver resolverFor(String segment) {
        return CONSTRUCTION.equals(segment) ? construction : finishing;
    }

    private static QueryToken.Filter filter(String field, QueryOperator op, String value) {
        return new QueryToken.Filter(field, op, value);
    }

    /** Result of applying a spec to a mocked criteria tree. */
    private record Applied(boolean negated, ConsumptionBranch branch) {}

    private Applied apply(Specification<WorkItemEntity> spec) {
        Root<WorkItemEntity> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Subquery<Long> subquery = mock(Subquery.class);
        Root consumption = mock(Root.class);
        Path anyPath = mock(Path.class);
        Predicate eqPredicate = mock(Predicate.class);
        Predicate andPredicate = mock(Predicate.class);
        Predicate existsPredicate = mock(Predicate.class);
        Predicate notPredicate = mock(Predicate.class);

        when(query.subquery(Long.class)).thenReturn(subquery);
        when(subquery.from(any(Class.class))).thenReturn(consumption);
        when(consumption.get(any(String.class))).thenReturn(anyPath);
        when(anyPath.get(any(String.class))).thenReturn(anyPath);
        when(root.get(any(String.class))).thenReturn(anyPath);
        when(cb.equal(any(), any(Object.class))).thenReturn(eqPredicate);
        when(cb.and(any(Predicate.class), any(Predicate.class))).thenReturn(andPredicate);
        when(cb.exists(subquery)).thenReturn(existsPredicate);
        when(cb.not(existsPredicate)).thenReturn(notPredicate);

        Predicate result = spec.toPredicate(root, query, cb);

        // The branch literal is compared against the consumption "branch" attribute.
        org.mockito.ArgumentCaptor<Object> captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(cb, org.mockito.Mockito.atLeastOnce()).equal(any(), captor.capture());
        ConsumptionBranch branch = captor.getAllValues().stream()
                .filter(ConsumptionBranch.class::isInstance)
                .map(ConsumptionBranch.class::cast)
                .findFirst().orElse(null);

        boolean negated = result == notPredicate;
        if (!negated) {
            assertThat(result).isSameAs(existsPredicate);
            verify(cb, never()).not(any(Predicate.class));
        }
        return new Applied(negated, branch);
    }

    static Stream<Arguments> hasCases() {
        return Stream.of(
                Arguments.of(QueryOperator.GREATER_THAN, "0"),
                Arguments.of(QueryOperator.GT, "0"),
                Arguments.of(QueryOperator.GREATER_THAN_OR_EQUAL, "1"),
                Arguments.of(QueryOperator.GTE, "1"),
                Arguments.of(QueryOperator.NOT_EQUALS, "0"));
    }

    static Stream<Arguments> noneCases() {
        return Stream.of(
                Arguments.of(QueryOperator.EQUALS, "0"),
                Arguments.of(QueryOperator.LESS_THAN, "1"),
                Arguments.of(QueryOperator.LT, "1"),
                Arguments.of(QueryOperator.LESS_THAN_OR_EQUAL, "0"),
                Arguments.of(QueryOperator.LTE, "0"));
    }

    static Stream<Arguments> rejectedCases() {
        return Stream.of(
                Arguments.of(QueryOperator.GREATER_THAN, "1"),
                Arguments.of(QueryOperator.GREATER_THAN, "5"),
                Arguments.of(QueryOperator.GREATER_THAN_OR_EQUAL, "0"),
                Arguments.of(QueryOperator.GREATER_THAN_OR_EQUAL, "2"),
                Arguments.of(QueryOperator.NOT_EQUALS, "1"),
                Arguments.of(QueryOperator.EQUALS, "1"),
                Arguments.of(QueryOperator.EQUALS, "3"),
                Arguments.of(QueryOperator.LESS_THAN, "0"),
                Arguments.of(QueryOperator.LESS_THAN, "2"),
                Arguments.of(QueryOperator.LESS_THAN_OR_EQUAL, "1"),
                Arguments.of(QueryOperator.CONTAINS, "0"),
                Arguments.of(QueryOperator.IN, "0"),
                Arguments.of(QueryOperator.NULL, "0"),
                Arguments.of(QueryOperator.LIKE, "0"));
    }

    @ParameterizedTest(name = "{0} {1} -> has")
    @MethodSource("hasCases")
    @DisplayName("has-at-least-one operators build a plain EXISTS for both branches")
    void hasOperators(QueryOperator op, String value) {
        Applied c = apply(construction.toFilter(filter(CONSTRUCTION, op, value)));
        assertThat(c.negated()).isFalse();
        assertThat(c.branch()).isEqualTo(ConsumptionBranch.construction);

        Applied f = apply(finishing.toFilter(filter(FINISHING, op, value)));
        assertThat(f.negated()).isFalse();
        assertThat(f.branch()).isEqualTo(ConsumptionBranch.finishing);
    }

    @ParameterizedTest(name = "{0} {1} -> none")
    @MethodSource("noneCases")
    @DisplayName("has-none operators build NOT EXISTS for both branches")
    void noneOperators(QueryOperator op, String value) {
        Applied c = apply(construction.toFilter(filter(CONSTRUCTION, op, value)));
        assertThat(c.negated()).isTrue();
        assertThat(c.branch()).isEqualTo(ConsumptionBranch.construction);

        Applied f = apply(finishing.toFilter(filter(FINISHING, op, value)));
        assertThat(f.negated()).isTrue();
        assertThat(f.branch()).isEqualTo(ConsumptionBranch.finishing);
    }

    @ParameterizedTest(name = "{0} {1} -> 400 invalid.field.path")
    @MethodSource("rejectedCases")
    @DisplayName("unsupported operator/value combinations are rejected with 400")
    void unsupportedCombinationsAreRejected(QueryOperator op, String value) {
        for (String segment : new String[] {CONSTRUCTION, FINISHING}) {
            ForemenApiException ex = assertThrows(ForemenApiException.class,
                    () -> resolverFor(segment).toFilter(filter(segment, op, value)));
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(ex.getMessageCode()).isEqualTo("error.query.invalid.field.path");
        }
    }

    @Test
    @DisplayName("non-integer values are rejected with 400 invalid.field")
    void nonIntegerValueIsRejected() {
        for (String raw : new String[] {"abc", "", "1.5", null}) {
            ForemenApiException ex = assertThrows(ForemenApiException.class,
                    () -> construction.toFilter(filter(CONSTRUCTION, QueryOperator.GREATER_THAN, raw)));
            assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(ex.getMessageCode()).isEqualTo("error.query.invalid.field");
        }
    }

    @Test
    @DisplayName("a filter for a foreign segment is rejected with 400 invalid.field.path")
    void foreignSegmentIsRejected() {
        ForemenApiException ex = assertThrows(ForemenApiException.class,
                () -> construction.toFilter(filter(FINISHING, QueryOperator.GREATER_THAN, "0")));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getMessageCode()).isEqualTo("error.query.invalid.field.path");
    }

    @Test
    @DisplayName("sorting by a count segment is not supported (400)")
    void sortingIsRejected() {
        ForemenApiException ex = assertThrows(ForemenApiException.class,
                () -> finishing.toSort(Sort.Order.asc(FINISHING)));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getMessageCode()).isEqualTo("error.query.invalid.field");
    }

    @Test
    @DisplayName("each subclass owns its own segment and registers on the work item entity")
    void segmentsAndRegistration() {
        assertThat(construction.property()).isEqualTo("constructionConsumptionCount");
        assertThat(finishing.property()).isEqualTo("finishingConsumptionCount");
        construction.register();
        finishing.register();
        assertThat(registry.find(WorkItemEntity.class, "constructionConsumptionCount")).isPresent();
        assertThat(registry.find(WorkItemEntity.class, "finishingConsumptionCount")).isPresent();
    }
}
