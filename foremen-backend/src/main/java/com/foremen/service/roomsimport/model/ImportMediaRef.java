package com.foremen.service.roomsimport.model;

/** Reference to a draft media object, sent with a room create request. */
public record ImportMediaRef(Long jobId, String mediaId) {}
