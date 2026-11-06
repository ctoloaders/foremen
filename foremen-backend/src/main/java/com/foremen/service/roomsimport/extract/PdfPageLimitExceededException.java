package com.foremen.service.roomsimport.extract;

/** Thrown before any page is processed when a PDF has more pages than allowed (Requirement 1.5). */
public class PdfPageLimitExceededException extends RuntimeException {
    private final String fileName;
    private final int pages;
    private final int limit;

    public PdfPageLimitExceededException(String fileName, int pages, int limit) {
        super("PDF '" + fileName + "' has " + pages + " pages, limit " + limit);
        this.fileName = fileName;
        this.pages = pages;
        this.limit = limit;
    }

    public String getFileName() { return fileName; }
    public int getPages() { return pages; }
    public int getLimit() { return limit; }
}
