package com.foremen.service.roomsimport.units;

import com.foremen.service.roomsimport.model.Warning;

import java.util.List;

/**
 * Outcome of {@link PlausibilityCorrector#correct}: a corrected copy of the input (the input is never
 * mutated) and the warnings ({@code UNIT_CORRECTED} INFO / {@code DIMENSION_OUT_OF_RANGE} WARNING).
 */
public record PlausibilityResult(PlausibilityInput corrected, List<Warning> warnings) {}
