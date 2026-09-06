package com.foremen.controller.model;

public record OfferPackageUpdateResponse(Long id, String code, Integer orderNo, String nameRU, String namePL,
                                         boolean active) {}
