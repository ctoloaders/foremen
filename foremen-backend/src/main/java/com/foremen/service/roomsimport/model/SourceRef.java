package com.foremen.service.roomsimport.model;

/** Where a draft room was recognised: source file name and optional (1-based) page. */
public record SourceRef(String fileName, Integer page) {}
