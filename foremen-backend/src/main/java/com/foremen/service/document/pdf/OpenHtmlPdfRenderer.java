package com.foremen.service.document.pdf;

import java.io.ByteArrayOutputStream;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.foremen.exception.ForemenApiException;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
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
            registerFonts(builder);
            builder.withHtmlContent(xhtml, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new ForemenApiException(HttpStatus.INTERNAL_SERVER_ERROR, RENDER_FAILED);
        }
    }

    /**
     * The PDF base-14 fonts the library falls back to ({@code sans-serif} maps to Helvetica) are WinAnsi only,
     * so Polish letters such as ł, ś, ć, ą and ż are printed as {@code #}. The bundled Liberation Sans (SIL OFL,
     * Arial-compatible metrics, covers Latin Extended-A and Cyrillic) is registered under the family name the
     * templates use ({@code sans-serif}) and under its own name.
     */
    private static void registerFonts(PdfRendererBuilder builder) {
        for (String family : new String[] {"sans-serif", "Liberation Sans"}) {
            useSans(builder, "LiberationSans-Regular.ttf", family, 400, BaseRendererBuilder.FontStyle.NORMAL);
            useSans(builder, "LiberationSans-Bold.ttf", family, 700, BaseRendererBuilder.FontStyle.NORMAL);
            useSans(builder, "LiberationSans-Italic.ttf", family, 400, BaseRendererBuilder.FontStyle.ITALIC);
            useSans(builder, "LiberationSans-BoldItalic.ttf", family, 700, BaseRendererBuilder.FontStyle.ITALIC);
        }
    }

    private static void useSans(PdfRendererBuilder builder, String file, String family, int weight,
                                BaseRendererBuilder.FontStyle style) {
        builder.useFont(() -> {
            try {
                return new ClassPathResource("fonts/liberation/" + file).getInputStream();
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, family, weight, style, true);
    }
}
