package com.foremen.controller.model;

public record DeliveryStatusDtoExtendedModel(Long id, String code, Integer orderNo, String nameRU, String namePL,
                                             boolean active) {}
