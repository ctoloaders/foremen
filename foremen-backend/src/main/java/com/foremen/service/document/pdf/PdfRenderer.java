package com.foremen.service.document.pdf;

/**
 * FOR-05-08 (Requirements 1.5, 3.6): the HTML→PDF rendering seam, deliberately isolated so the
 * concrete PDF library (OpenHTMLtoPDF today) can be swapped without touching
 * {@link com.foremen.service.document.PdfFreezeService} or the signing lifecycle.
 *
 * <p>The single operation takes a well-formed, self-contained XHTML document (the canonical body +
 * the declared form fields rendered as fillable regions) and returns the rendered PDF as raw bytes.
 * Implementations perform no I/O beyond the in-memory render — storage and hashing are the caller's
 * concern ({@code PdfFreezeService}).
 */
public interface PdfRenderer {

    /**
     * Render a self-contained XHTML document to a PDF byte array.
     *
     * @param xhtml a well-formed, self-contained XHTML string (the canonical signing body)
     * @return the rendered PDF document as bytes
     */
    byte[] renderToPdf(String xhtml);
}
