package com.foremen.service.offer;

import java.text.Normalizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.foremen.dao.OfferDao;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.document.pdf.PdfRenderer;
import com.foremen.service.offer.OfferSnapshotViews.SnapshotView;

import lombok.RequiredArgsConstructor;

/**
 * FOR-05-07b task 3.6: renders an offer PDF on demand from the stored snapshot. Visibility, the
 * missing-snapshot 404 and CLIENT redaction come from {@link OfferSnapshotService#snapshot} (the PDF
 * is always built from the redacted view without base prices, for every role). Nothing is stored.
 */
@Service
@RequiredArgsConstructor
public class OfferPdfService {

    private static final Logger log = LoggerFactory.getLogger(OfferPdfService.class);

    static final String PDF_FAILED = "error.offer.pdf.failed";

    private final OfferDao offerDao;
    private final OfferSnapshotService snapshotService;
    private final OfferHtmlBuilder htmlBuilder;
    private final PdfRenderer pdfRenderer;

    /** The rendered document and its download file name. */
    public record OfferPdf(byte[] content, String fileName) {
    }

    @Transactional(readOnly = true)
    public OfferPdf render(Long offerId, boolean client, boolean ru) {
        SnapshotView view = snapshotService.snapshot(offerId, client, false, ru);
        OfferEntity offer = offerDao.findById(offerId).orElseThrow(
                () -> new ForemenApiException(HttpStatus.NOT_FOUND, "error.entity.not.found", "offerId", offerId));
        ProjectEntity project = offer.getProject();
        String address = project != null && project.getFormattedAddress() != null
                && !project.getFormattedAddress().isBlank() ? project.getFormattedAddress()
                : (project != null ? project.getAddress() : null);
        String date = offer.getCreatedDate() != null ? offer.getCreatedDate().toLocalDate().toString() : null;
        OfferHtmlBuilder.Header header = new OfferHtmlBuilder.Header(
                project != null ? project.getName() : null, address, date);
        try {
            byte[] pdf = pdfRenderer.renderToPdf(htmlBuilder.build(view, header, ru));
            return new OfferPdf(pdf, fileName(view.name(), offerId));
        } catch (RuntimeException e) {
            log.warn("Offer {} PDF failed: {}", offerId, e.toString());
            throw new ForemenApiException(HttpStatus.BAD_GATEWAY, PDF_FAILED);
        }
    }

    /** {@code <ascii-slug-of-name>-<id>.pdf}; the slug falls back to {@code offer}. */
    public static String fileName(String name, Long offerId) {
        String ascii = name == null ? "" : Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("[^\\p{ASCII}]", "").toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return (ascii.isEmpty() ? "offer" : ascii) + "-" + offerId + ".pdf";
    }
}
