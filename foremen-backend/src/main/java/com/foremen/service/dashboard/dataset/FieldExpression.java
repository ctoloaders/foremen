package com.foremen.service.dashboard.dataset;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;

/**
 * Builds a computed or LOOKUP field expression (a scalar value derived from the root row, for
 * example a correlated subquery or {@code amountGross - paidGross}). Must be a pure function of its
 * arguments: it is invoked once per query that references the field.
 */
@FunctionalInterface
public interface FieldExpression {

    Expression<?> build(CriteriaBuilder cb, CriteriaQuery<?> query, Root<?> root);
}
