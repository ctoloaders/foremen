package com.foremen.controller.model;

public record RoleCreateResponse(Long id, String code, String nameRU, String namePL, String descriptionRU, String descriptionPL, boolean system) {}
