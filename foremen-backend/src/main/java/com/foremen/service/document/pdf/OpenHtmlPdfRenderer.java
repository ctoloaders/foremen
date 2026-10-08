package com.foremen.service.document.pdf;

import java.io.ByteArrayOutputStream;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.foremen.exception.ForemenApiException;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

/**
 * FOR-05-08 (Requirements 1.5, 3.6): the default {@link PdfRenderer} backed by OpenHTMLtoPDF
 * (PDFBox output). This class is the ONLY place the concrete HTML→PDF library is referenced, so the
 * dependency stays isolated behind the {@link PdfRenderer} seam and the signing lifecycle never
 * couples to it.
 *
 * <p>Rendering is deterministic and purely in-memory: the supplied self-contained XHTML is rendered
 * to a {@code ByteArrayOutputStream} and returned as bytes. A render failure (malformed XHTML, an
 * I/O fault) surfaces as {@code 500 error.document.render.failed} rather than leaking the library's
 * exception type.
 */
@Component
public class OpenHtmlPdfRenderer implements PdfRenderer {

    /** 500 when the HTML→PDF render fails. */
    private static final String RENDER_FAILED = "error.document.render.failed";

    @Override
    public byte[] renderToPdf(String xhtml) {
        if (xhtml == null || xhtml.isBlank()) {
            throw new ForemenApiException(HttpStatus.INTERNAL_SERVER_ERROR, RENDER_FAILED);
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(xhtml, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new ForemenApiException(HttpStatus.INTERNAL_SERVER_ERROR, RENDER_FAILED);
        }
    }
}
