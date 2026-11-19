package com.foremen.controller.model;

/** Row of the standard work set: the work (with its category) assigned to a room type. */
public record StandardWorkSetItemDtoModel(Long id, Long roomTypeId, String roomTypeName,
                                          Long workItemId, String workItemName, String workItemCode,
                                          String workCategoryName) {}
