package com.foremen.service.roomsimport.model;

/** Per-input summary in the job result. */
public record InputInfo(
        String fileName,
        String kind,
        Integer pages,
        Integer rasterizedPages,
        String status) {}
