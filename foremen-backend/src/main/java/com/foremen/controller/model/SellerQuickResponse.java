package com.foremen.controller.model;

/** FOR-11-00 (R20) — the seller and whether it was newly created (false = existing NIP hit). */
public record SellerQuickResponse(MaterialSellerDtoModel seller, boolean created) {}
