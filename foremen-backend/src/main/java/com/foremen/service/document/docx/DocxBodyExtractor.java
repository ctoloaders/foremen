package com.foremen.service.document.docx;

import org.springframework.web.multipart.MultipartFile;

/**
 * FOR-05-08 (Requirement 3a.1; design §Components {@code DocumentTemplateService}, Open Question 4):
 * the DOCX/OOXML toolkit seam. Extracts the plain-text body (carrying its {@code {Token}} merge
 * placeholders) from an uploaded {@code .docx} template so the admin template import never couples
 * the lifecycle to a document-format library.
 *
 * <p>The merge engine's contract is a plain {@code String} body of {@code {Token}} placeholders
 * ({@link com.foremen.service.signing.merge.TemplateMergeEngine}); this seam is the single place
 * where a {@code .docx} is read into that body text, so the concrete OOXML library (Apache POI
 * today) stays isolated behind one interface and can be swapped without touching
 * {@code DocumentTemplateService} or the signing lifecycle.
 */
public interface DocxBodyExtractor {

    /**
     * Extract the plain-text body (with {@code {Token}} placeholders preserved) from a {@code .docx}
     * upload.
     *
     * @param docx the uploaded {@code .docx} file; must be a non-empty OOXML Word document
     * @return the extracted body text, with paragraphs separated by newlines
     * @throws com.foremen.exception.ForemenApiException 400 {@code error.document.template.docx.invalid}
     *         when the upload is empty, not a {@code .docx}, or cannot be parsed
     */
    String extractBody(MultipartFile docx);
}
