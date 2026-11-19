package com.foremen.controller.model;

import java.math.BigDecimal;
import java.util.Map;

/**
 * FOR-05-02 Round 11 #3 — how a CALCULATED room metric was obtained: the formula text in the work
 * formula syntax and the value actually used for each variable of it.
 */
public record MetricExplanationDto(String formula, Map<String, BigDecimal> variables) {}
