package com.foremen.service.model;

import java.math.BigDecimal;
import java.util.Map;

/**
 * FOR-05-02 Round 11 #3 — how a CALCULATED room metric was obtained: the formula text in the work
 * formula syntax (bare variable tokens, constants, {@code + - * /} and parentheses) and the value
 * actually used for every variable of it. Constants need no entry in {@code variables}.
 *
 * @param formula   e.g. {@code (perimeter - finishGap) * ceilingHeight - doorArea - windowArea}
 * @param variables the resolved value of each variable token used by {@code formula}
 */
public record MetricExplanation(String formula, Map<String, BigDecimal> variables) {
}
