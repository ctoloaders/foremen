package com.foremen.service.roomsimport.extract;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF text layer via PDFBox; pages with fewer than {@code minTextChars} characters are rendered at
 * 200 DPI and returned as image pages. The page limit is checked before any page is processed.
 */
public class PdfInputExtractor implements InputExtractor {

    public static final float RASTER_DPI = 200f;

    private final int maxPages;
    private final int minTextChars;

    public PdfInputExtractor(int maxPages, int minTextChars) {
        this.maxPages = maxPages;
        this.minTextChars = minTextChars;
    }

    @Override
    public boolean supports(InputKind kind) {
        return kind == InputKind.PDF;
    }

    @Override
    public ExtractedInput extract(String fileName, byte[] c, DetectedType type) throws Exception {
        try (PDDocument doc = PDDocument.load(c)) {
            int total = doc.getNumberOfPages();
            if (total > maxPages) {
                throw new PdfPageLimitExceededException(fileName, total, maxPages);
            }
            PDFTextStripper stripper = new PDFTextStripper();
            PDFRenderer renderer = new PDFRenderer(doc);
            StringBuilder text = new StringBuilder();
            List<PageImage> pages = new ArrayList<>();
            for (int p = 1; p <= total; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String pageText = stripper.getText(doc).trim();
                if (pageText.length() >= minTextChars) {
                    text.append("Page ").append(p).append(":\n").append(pageText).append("\n\n");
                } else {
                    BufferedImage img = renderer.renderImageWithDPI(p - 1, RASTER_DPI, ImageType.RGB);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    ImageIO.write(img, "png", out);
                    pages.add(new PageImage(out.toByteArray(), "image/png", img.getWidth(),
                            img.getHeight(), true, fileName, p));
                }
            }
            return new ExtractedInput(fileName, InputKind.PDF, text.length() == 0 ? null : text.toString(),
                    pages, total, pages.size(), null);
        }
    }
}
