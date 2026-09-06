package com.foremen.controller.model;

public record DeliveryStatusCreateResponse(Long id, String code, Integer orderNo, String nameRU, String namePL,
                                           boolean active) {}
