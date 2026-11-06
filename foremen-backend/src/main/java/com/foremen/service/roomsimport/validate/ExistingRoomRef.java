package com.foremen.service.roomsimport.validate;

/** Lightweight view of a project's existing room, used only for duplicate warnings (Requirement 21.3). */
public record ExistingRoomRef(Long id, String label, Long roomTypeId) {}
