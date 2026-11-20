package com.foremen.service.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.foremen.dao.OfferDao;
import com.foremen.dao.model.OfferEntity;
import com.foremen.dao.model.OfferStatus;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.document.pdf.OpenHtmlPdfRenderer;
import com.foremen.service.document.pdf.PdfRenderer;

/** FOR-05-07b task 3.6: offer PDF from the snapshot (visibility, redaction, package marker, legacy 404). */
class OfferPdfServiceTest {

    private final OfferDao offerDao = mock(OfferDao.class);
    private final AtomicReference<String> lastHtml = new AtomicReference<>();
    private final PdfRenderer capturing = html -> {
        lastHtml.set(html);
        return new OpenHtmlPdfRenderer().renderToPdf(html);
    };
    private OfferPdfService service;

    @BeforeEach
    void setUp() {
        OfferSnapshotService snapshots = new OfferSnapshotService(offerDao, new OfferVisibilityResolver());
        service = new OfferPdfService(offerDao, snapshots, new OfferHtmlBuilder(), capturing);
    }

    private OfferEntity offer(long id, OfferStatus status, OfferSnapshot snapshot) {
        ProjectEntity project = new ProjectEntity();
        project.setId(7L);
        project.setName("Karola Taylora 15");
        OfferEntity o = new OfferEntity();
        o.setId(id);
        o.setProject(project);
        o.setStatus(status);
        o.setName("Bazowy Łódź");
        o.setSnapshot(snapshot);
        o.setCreatedDate(LocalDateTime.of(2026, 3, 1, 10, 0));
        when(offerDao.findById(id)).thenReturn(Optional.of(o));
        return o;
    }

    private static OfferSnapshot snapshot(String packageCode) {
        OfferSnapshot.Row row = new OfferSnapshot.Row(10L, "Покраска", "Malowanie <ścian>", "M2", "м2", "m2",
                new BigDecimal("12.500"), new BigDecimal("40.00"), new BigDecimal("31.00"),
                new BigDecimal("500.00"), new BigDecimal("387.50"),
                List.of(new OfferSnapshot.Cell(1L, new BigDecimal("12.500"))));
        return new OfferSnapshot(2, "2026-03-01T10:00:00Z", new BigDecimal("8"),
                new BigDecimal("500.00"), new BigDecimal("40.00"), new BigDecimal("540.00"),
                List.of(new OfferSnapshot.Room(1L, "Salon")),
                List.of(new OfferSnapshot.Group(3L, "Отделка", "Wykończenie", List.of(row))),
                packageCode, packageCode == null ? null : "Премиум", packageCode == null ? null : "Premium");
    }

    @Test
    void rendersRealPdfWithMatrixTotalsAndPackageMarker() throws Exception {
        offer(1L, OfferStatus.SENT, snapshot("PREMIUM"));

        OfferPdfService.OfferPdf pdf = service.render(1L, false, false);

        assertThat(new String(pdf.content(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(pdf.fileName()).isEqualTo("bazowy-odz-1.pdf");
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.newDocumentBuilder().parse(new ByteArrayInputStream(lastHtml.get().getBytes(StandardCharsets.UTF_8)));
        assertThat(lastHtml.get())
                .contains("Bazowy Łódź").contains("Karola Taylora 15").contains("2026-03-01")
                .contains("Pakiet:").contains("Premium")
                .contains("Salon").contains("Malowanie &lt;ścian&gt;").contains("Wykończenie")
                .contains("12,5").contains("40,00").contains("500,00").contains("540,00").contains("8%");
        assertThat(lastHtml.get()).doesNotContain("31,00").doesNotContain("387,50");
    }

    @Test
    void noPackageMarkerWhenSnapshotHasNone() {
        offer(2L, OfferStatus.SENT, snapshot(null));

        service.render(2L, false, true);

        assertThat(lastHtml.get()).doesNotContain("Пакет:").contains("Оферта").contains("Отделка");
    }

    @Test
    void clientVariantCarriesNoBasePrices() {
        offer(3L, OfferStatus.SENT, snapshot("PREMIUM"));

        service.render(3L, true, false);

        assertThat(lastHtml.get()).doesNotContain("31,00").doesNotContain("387,50").contains("500,00");
    }

    @Test
    void clientCannotSeeDraftOffer() {
        offer(4L, OfferStatus.DRAFT, snapshot(null));

        assertThatThrownBy(() -> service.render(4L, true, false))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void legacyOfferWithoutSnapshotIsSnapshotMissing() {
        offer(5L, OfferStatus.SENT, null);

        assertThatThrownBy(() -> service.render(5L, false, false))
                .isInstanceOfSatisfying(ForemenApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getMessage()).contains("error.offer.snapshot.missing");
                });
    }

    @Test
    void rendererFailureMapsTo502() {
        OfferSnapshotService snapshots = new OfferSnapshotService(offerDao, new OfferVisibilityResolver());
        OfferPdfService failing = new OfferPdfService(offerDao, snapshots, new OfferHtmlBuilder(), html -> {
            throw new IllegalStateException("boom");
        });
        offer(6L, OfferStatus.SENT, snapshot(null));

        assertThatThrownBy(() -> failing.render(6L, false, false))
                .isInstanceOfSatisfying(ForemenApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void fileNameFallsBackToOffer() {
        assertThat(OfferPdfService.fileName(null, 9L)).isEqualTo("offer-9.pdf");
        assertThat(OfferPdfService.fileName("  !!  ", 9L)).isEqualTo("offer-9.pdf");
    }
}
