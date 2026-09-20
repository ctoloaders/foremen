package com.foremen.controller.model;

public record MaterialSellerCreateResponse(Long id, String code, String nameRU, String namePL, boolean active,
                                           String website) {}
