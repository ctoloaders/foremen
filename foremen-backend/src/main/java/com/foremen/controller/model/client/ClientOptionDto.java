package com.foremen.controller.model.client;

/** FOR-11-02: one option of a round; {@code money} is null for PACKAGE_CHOICE. */
public record ClientOptionDto(Long optionId, ClientItemDto item, ClientMoneyDto money) {
}
