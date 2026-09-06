package com.foremen.controller.model;

public record WorkCategoryUpdateResponse(Long id, String code, Integer orderNo, String nameRU, String namePL,
                                         boolean active) {}
