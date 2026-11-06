package com.foremen.service.roomsimport;

/** Stable message / failure-reason codes of the rooms auto-import (design "Error handling"). */
public final class RoomsImportErrors {

    private RoomsImportErrors() {
    }

    public static final String IN_PROGRESS = "error.roomsimport.in_progress";
    public static final String NOT_CANCELLABLE = "error.roomsimport.not_cancellable";
    public static final String UNAVAILABLE = "error.roomsimport.unavailable";
    public static final String INPUT_EMPTY = "error.roomsimport.input.empty";
    public static final String FILE_TYPE = "error.roomsimport.file.type";
    public static final String FILE_SIZE = "error.roomsimport.file.size";
    public static final String FILE_COUNT = "error.roomsimport.file.count";
    public static final String PDF_PAGES = "error.roomsimport.pdf.pages";
    public static final String NOT_FOUND = "error.entity.not.found";

    public static final String FAILED_TIMEOUT = "error.roomsimport.failed.timeout";
    public static final String FAILED_LLM_UNAVAILABLE = "error.roomsimport.failed.llm_unavailable";
    public static final String FAILED_NO_READABLE_INPUT = "error.roomsimport.failed.no_readable_input";
    public static final String FAILED_PDF_PAGES = "error.roomsimport.failed.pdf_pages";
    public static final String FAILED_INTERNAL = "error.roomsimport.failed.internal";

    /** Key namespace of a job: {@code rooms-import/{jobId}}. */
    public static String jobNamespace(Long jobId) {
        return "rooms-import/" + jobId;
    }
}
