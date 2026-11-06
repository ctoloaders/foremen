package com.foremen.service.roomsimport.extract;

import com.foremen.config.roomsimport.RoomsImportProperties;
import com.foremen.service.roomsimport.model.WarningCodes;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FOR-05-02 task 2.5 — input extractors and content sniffing (Requirements 1.4, 1.5, 6.1-6.5, 14.3). */
class RoomsInputExtractorsTest {

    private final RoomsInputExtractors extractors = new RoomsInputExtractors(
            new RoomsImportProperties(true, null, new RoomsImportProperties.Limits(10, null, 3, 40),
                    null, 0, null));

    private static byte[] fixture(String name) throws Exception {
        try (InputStream in = RoomsInputExtractorsTest.class.getResourceAsStream("/roomsimport/" + name)) {
            return in.readAllBytes();
        }
    }

    // ---- text -------------------------------------------------------------------------------

    @Test
    void utf8TextIsDecoded() throws Exception {
        ExtractedInput r = extractors.extract("a.txt", "text/plain", fixture("utf8-pl.txt"));
        assertThat(r.readable()).isTrue();
        assertThat(r.kind()).isEqualTo(InputKind.TEXT);
        assertThat(r.text()).contains("Łazienka 2,1 x 1,8");
    }

    @Test
    void windows1250PolishIsDetected() throws Exception {
        ExtractedInput r = extractors.extract("pl.txt", "text/plain", fixture("cp1250-pl.txt"));
        assertThat(r.text()).contains("Łazienka").contains("łóżko");
    }

    @Test
    void windows1251CyrillicIsDetected() throws Exception {
        ExtractedInput r = extractors.extract("ru.txt", null, fixture("cp1251-ru.txt"));
        assertThat(r.text()).contains("Гостиная");
    }

    @Test
    void utf16BomAndUtf8BomAreHonoured() {
        byte[] utf16 = concat(new byte[]{(byte) 0xFF, (byte) 0xFE}, "Salon".getBytes(StandardCharsets.UTF_16LE));
        byte[] utf8 = concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, "Salon".getBytes(StandardCharsets.UTF_8));
        assertThat(extractors.extract("a.txt", "text/plain", utf16).text()).isEqualTo("Salon");
        assertThat(extractors.extract("b.txt", "text/plain", utf8).text()).isEqualTo("Salon");
    }

    @Test
    void csvIsTreatedAsText() throws Exception {
        assertThat(extractors.extract("rooms.csv", "text/csv", fixture("rooms.csv")).text())
                .contains("Kitchen,3.2,4.1");
    }

    // ---- office -----------------------------------------------------------------------------

    @Test
    void docxParagraphsAndTablesAreFlattened() throws Exception {
        ExtractedInput r = extractors.extract("plan.docx", null, docx());
        assertThat(r.kind()).isEqualTo(InputKind.DOCX);
        assertThat(r.text()).contains("Plan mieszkania").contains("Salon\t5.2 x 3.8").contains("Kuchnia\t3 x 2");
    }

    @Test
    void legacyDocIsExtracted() throws Exception {
        ExtractedInput r = extractors.extract("plan.doc", "application/msword", fixture("plan.doc"));
        assertThat(r.kind()).isEqualTo(InputKind.DOC);
        assertThat(r.text()).contains("Salon 5,2 x 3,8 m").contains("Lazienka");
    }

    @Test
    void xlsxAndXlsRenderSheetsAsTabSeparatedRowsWithCachedFormulaValues() throws Exception {
        for (boolean xlsx : new boolean[]{true, false}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (Workbook wb = xlsx ? new XSSFWorkbook() : new HSSFWorkbook()) {
                var sheet = wb.createSheet("Rooms");
                Row r0 = sheet.createRow(0);
                r0.createCell(0).setCellValue("Salon");
                r0.createCell(1).setCellValue(5.5);
                r0.createCell(2).setCellValue(4);
                Row r1 = sheet.createRow(1);
                r1.createCell(0).setCellValue("Area");
                r1.createCell(1).setCellFormula("B1*C1");
                wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
                wb.write(out);
            }
            ExtractedInput r = extractors.extract(xlsx ? "r.xlsx" : "r.xls", null, out.toByteArray());
            assertThat(r.kind()).isEqualTo(xlsx ? InputKind.XLSX : InputKind.XLS);
            assertThat(r.text()).contains("Sheet: Rooms").contains("Salon\t5.5\t4").contains("Area\t22");
        }
    }

    // ---- pdf --------------------------------------------------------------------------------

    @Test
    void textPdfPageYieldsTextAndNoRasterization() throws Exception {
        ExtractedInput r = extractors.extract("t.pdf", "application/pdf", pdf(1, true));
        assertThat(r.kind()).isEqualTo(InputKind.PDF);
        assertThat(r.pageCount()).isEqualTo(1);
        assertThat(r.rasterizedPages()).isZero();
        assertThat(r.pages()).isEmpty();
        assertThat(r.text()).contains("Living room 5.2 x 3.8 m");
    }

    @Test
    void lowTextPdfPageIsRasterizedAt200Dpi() throws Exception {
        ExtractedInput r = extractors.extract("scan.pdf", "application/pdf", pdf(2, false));
        assertThat(r.text()).isNull();
        assertThat(r.rasterizedPages()).isEqualTo(2);
        assertThat(r.pages()).hasSize(2);
        PageImage p = r.pages().get(1);
        assertThat(p.page()).isEqualTo(2);
        assertThat(p.mimeType()).isEqualTo("image/png");
        // A4 (letter-ish default PDRectangle) at 200 DPI is well above 1500 px wide
        assertThat(p.width()).isGreaterThan(1500);
        assertThat(p.height()).isGreaterThan(p.width());
        assertThat(ImageIO.read(new ByteArrayInputStream(p.bytes()))).isNotNull();
    }

    @Test
    void pdfOverPageLimitIsRejectedUpFront() throws Exception {
        byte[] big = pdf(4, true); // limit is 3
        assertThatThrownBy(() -> extractors.extract("big.pdf", "application/pdf", big))
                .isInstanceOfSatisfying(PdfPageLimitExceededException.class, e -> {
                    assertThat(e.getPages()).isEqualTo(4);
                    assertThat(e.getLimit()).isEqualTo(3);
                });
    }

    @Test
    void truncatedPdfIsUnreadable() throws Exception {
        byte[] cut = Arrays.copyOf(pdf(1, true), 40);
        assertUnreadable(extractors.extract("cut.pdf", "application/pdf", cut), "cut.pdf");
    }

    // ---- images -----------------------------------------------------------------------------

    @Test
    void pngPassesThroughUnchanged() throws Exception {
        byte[] png = encode(solid(40, 20), "png");
        PageImage p = extractors.extract("p.png", "image/png", png).pages().get(0);
        assertThat(p.bytes()).isEqualTo(png);
        assertThat(p.mimeType()).isEqualTo("image/png");
        assertThat(p.croppable()).isTrue();
        assertThat(p.width()).isEqualTo(40);
    }

    @Test
    void gifBmpAndTiffAreConvertedToPng() throws Exception {
        for (String fmt : new String[]{"gif", "bmp", "tiff"}) {
            ExtractedInput r = extractors.extract("x." + fmt, null, encode(solid(30, 10), fmt));
            assertThat(r.readable()).as(fmt).isTrue();
            PageImage p = r.pages().get(0);
            assertThat(p.mimeType()).as(fmt).isEqualTo("image/png");
            assertThat(ImageIO.read(new ByteArrayInputStream(p.bytes())).getWidth()).isEqualTo(30);
        }
    }

    @Test
    void exifOrientation6RotatesLandscapeJpegToPortrait() throws Exception {
        byte[] jpeg = withExifOrientation(encode(solid(40, 20), "jpg"), 6);
        PageImage p = extractors.extract("rot.jpg", "image/jpeg", jpeg).pages().get(0);
        assertThat(p.width()).isEqualTo(20);
        assertThat(p.height()).isEqualTo(40);
        assertThat(p.mimeType()).isEqualTo("image/png");
    }

    @Test
    void jpegWithoutExifIsForwardedAsIs() throws Exception {
        byte[] jpeg = encode(solid(40, 20), "jpg");
        PageImage p = extractors.extract("a.jpg", "image/jpeg", jpeg).pages().get(0);
        assertThat(p.bytes()).isEqualTo(jpeg);
        assertThat(p.mimeType()).isEqualTo("image/jpeg");
    }

    @Test
    void heicIsForwardedAsIsAndNotCroppable() {
        byte[] heic = new byte[64];
        byte[] head = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'};
        System.arraycopy(head, 0, heic, 0, head.length);
        ExtractedInput r = extractors.extract("a.heic", "image/heic", heic);
        assertThat(r.readable()).isTrue();
        PageImage p = r.pages().get(0);
        assertThat(p.croppable()).isFalse();
        assertThat(p.mimeType()).isEqualTo("image/heic");
        assertThat(p.bytes()).isEqualTo(heic);
    }

    @Test
    void corruptPngIsUnreadable() throws Exception {
        byte[] cut = Arrays.copyOf(encode(solid(40, 20), "png"), 30);
        assertUnreadable(extractors.extract("bad.png", "image/png", cut), "bad.png");
    }

    // ---- sniffing & unreadable --------------------------------------------------------------

    @Test
    void contentWinsOverExtensionAndDeclaredType() throws Exception {
        assertThat(ContentSniffer.detect("evil.txt", "text/plain", encode(solid(10, 10), "png")).kind())
                .isEqualTo(InputKind.IMAGE);
        assertThat(ContentSniffer.detect("evil.png", "image/png", pdf(1, true)).kind()).isEqualTo(InputKind.PDF);
    }

    @Test
    void sniffsDocxAndXlsxFromZipEntries() throws Exception {
        ByteArrayOutputStream x = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            wb.createSheet("s");
            wb.write(x);
        }
        assertThat(ContentSniffer.detect("a.bin", null, docx()).kind()).isEqualTo(InputKind.DOCX);
        assertThat(ContentSniffer.detect("a.bin", null, x.toByteArray()).kind()).isEqualTo(InputKind.XLSX);
    }

    @Test
    void binaryGarbageWithTextExtensionIsUnreadable() throws Exception {
        assertUnreadable(extractors.extract("notes.txt", "text/plain", fixture("corrupt.bin")), "notes.txt");
    }

    @Test
    void emptyAndCorruptOfficeFilesAreUnreadable() {
        assertUnreadable(extractors.extract("e.docx", null, new byte[0]), "e.docx");
        byte[] fakeZip = {'P', 'K', 3, 4, 1, 2, 3, 4, 5, 6, 7, 8};
        assertUnreadable(extractors.extract("f.xlsx", null, fakeZip), "f.xlsx");
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static void assertUnreadable(ExtractedInput r, String name) {
        assertThat(r.readable()).isFalse();
        assertThat(r.warning().code()).isEqualTo(WarningCodes.FILE_UNREADABLE);
        assertThat(r.warning().params()).containsEntry("fileName", name);
        assertThat(r.toInfo().status()).isEqualTo(ExtractedInput.STATUS_UNREADABLE);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] docx() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("Plan mieszkania");
            XWPFTable t = doc.createTable(2, 2);
            t.getRow(0).getCell(0).setText("Salon");
            t.getRow(0).getCell(1).setText("5.2 x 3.8");
            t.getRow(1).getCell(0).setText("Kuchnia");
            t.getRow(1).getCell(1).setText("3 x 2");
            doc.write(out);
        }
        return out.toByteArray();
    }

    private static byte[] pdf(int pages, boolean withText) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                if (withText) {
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.beginText();
                        cs.setFont(PDType1Font.HELVETICA, 12);
                        cs.newLineAtOffset(50, 700);
                        cs.showText("Living room 5.2 x 3.8 m, ceiling height 2.6 m, two windows");
                        cs.endText();
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static BufferedImage solid(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, x < w / 2 ? 0xFF0000 : 0x0000FF);
            }
        }
        return img;
    }

    private static byte[] encode(BufferedImage img, String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(img, format, out)).as(format).isTrue();
        return out.toByteArray();
    }

    /** Inserts an APP1/Exif segment with a single orientation tag right after the SOI marker. */
    private static byte[] withExifOrientation(byte[] jpeg, int orientation) {
        byte[] app1 = {
                (byte) 0xFF, (byte) 0xE1, 0, 34,
                'E', 'x', 'i', 'f', 0, 0,
                'M', 'M', 0, 0x2A, 0, 0, 0, 8,
                0, 1,
                0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0,
                0, 0, 0, 0};
        byte[] out = new byte[jpeg.length + app1.length];
        System.arraycopy(jpeg, 0, out, 0, 2);
        System.arraycopy(app1, 0, out, 2, app1.length);
        System.arraycopy(jpeg, 2, out, 2 + app1.length, jpeg.length - 2);
        return out;
    }
}
