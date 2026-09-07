package com.foremen.controller.model;

import com.foremen.dao.model.MeasureSource;

import java.math.BigDecimal;

/**
 * A single room metric paired with its source flag, as exposed by the room list/read DTOs.
 *
 * <p>{@code value} is the numeric metric (m² or mb depending on the metric) and {@code source}
 * records whether it was derived from geometry ({@link MeasureSource#CALCULATED}) or entered by
 * hand ({@link MeasureSource#MANUAL}). Both may be {@code null} when the metric is absent.
 */
public record MeasureValueDto(BigDecimal value, MeasureSource source) {}
