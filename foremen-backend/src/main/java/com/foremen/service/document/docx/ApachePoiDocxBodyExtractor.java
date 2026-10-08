package com.foremen.service.document.docx;

import java.io.InputStream;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.foremen.exception.ForemenApiException;

/**
 * FOR-05-08 (Requirement 3a.1): the default {@link DocxBodyExtractor} backed by Apache POI (OOXML).
 * This class is the ONLY place the concrete OOXML library is referenced, so the dependency stays
 * isolated behind the {@link DocxBodyExtractor} seam and {@code DocumentTemplateService} / the
 * signing lifecycle never couple to it.
 *
 * <p>Extraction is purely in-memory: the uploaded {@code .docx} is opened as an {@link XWPFDocument}
 * and its text is pulled via {@link XWPFWordExtractor}, which preserves the {@code {Token}} merge
 * placeholders as plain text. A malformed / non-{@code .docx} upload surfaces as {@code 400
 * error.document.template.docx.invalid} rather than leaking the library's exception type.
 */
@Component
public class ApachePoiDocxBodyExtractor implements DocxBodyExtractor {

    /** 400 when the uploaded template is empty, not a {@code .docx}, or cannot be parsed. */
    static final String DOCX_INVALID = "error.document.template.docx.invalid";

    @Override
    public String extractBody(MultipartFile docx) {
        if (docx == null || docx.isEmpty()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, DOCX_INVALID);
        }
        try (InputStream in = docx.getInputStream();
             XWPFDocument document = new XWPFDocument(in);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            String text = extractor.getText();
            return text == null ? "" : text;
        } catch (Exception e) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, DOCX_INVALID);
        }
    }
}
