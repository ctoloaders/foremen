package com.foremen.controller.model;

public record DeliveryStatusUpdateResponse(Long id, String code, Integer orderNo, String nameRU, String namePL,
                                           boolean active) {}
