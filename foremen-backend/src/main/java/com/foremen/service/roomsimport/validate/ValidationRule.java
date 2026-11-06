package com.foremen.service.roomsimport.validate;

import com.foremen.service.roomsimport.model.Warning;

import java.util.List;
import java.util.function.Function;

/** A pure, deterministic validation rule: context in, warnings out (never throws on typed input). */
@FunctionalInterface
public interface ValidationRule extends Function<ValidationContext, List<Warning>> {}
