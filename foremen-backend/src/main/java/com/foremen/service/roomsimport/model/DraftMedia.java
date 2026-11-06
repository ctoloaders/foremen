package com.foremen.service.roomsimport.model;

/** Reference to a draft media object of a job (e.g. the cropped room picture). */
public record DraftMedia(String mediaId, String kind, String contentType, String previewUrl) {}
