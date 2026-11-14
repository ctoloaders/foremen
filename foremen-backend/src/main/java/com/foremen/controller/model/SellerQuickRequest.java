package com.foremen.controller.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** FOR-11-00 (R20) — inline seller creation: name required, NIP optional. */
public record SellerQuickRequest(
    @NotBlank(message = "error.materialSeller.quick.nameRequired")
    @Size(max = 100, message = "error.materialSeller.quick.nameRequired") String name,
    @Size(max = 20) String nip
) {}
