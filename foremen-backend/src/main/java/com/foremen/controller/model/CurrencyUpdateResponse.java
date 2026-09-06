package com.foremen.controller.model;

public record CurrencyUpdateResponse(Long id, String code, String symbol, String nameRU, String namePL, boolean active) {}
