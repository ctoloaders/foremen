package com.foremen.service.roomsimport.extract;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Content sniffing (Requirement 14.3): the kind is derived from magic bytes first; the declared
 * content type and the file extension are only tie-breakers (text, OLE doc vs xls). No Tika.
 */
public final class ContentSniffer {

    private ContentSniffer() {}

    public static DetectedType detect(String fileName, String declaredContentType, byte[] b) {
        if (b == null || b.length == 0) {
            return new DetectedType(InputKind.UNSUPPORTED, null);
        }
        String ext = extension(fileName);
        if (startsWith(b, "%PDF-")) {
            return new DetectedType(InputKind.PDF, "application/pdf");
        }
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return image("image/jpeg");
        }
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return image("image/png");
        }
        if (startsWith(b, "GIF87a") || startsWith(b, "GIF89a")) {
            return image("image/gif");
        }
        if (startsWith(b, "BM") && b.length > 14) {
            return image("image/bmp");
        }
        if (b.length > 4 && ((b[0] == 'I' && b[1] == 'I' && b[2] == 0x2A && b[3] == 0)
                || (b[0] == 'M' && b[1] == 'M' && b[2] == 0 && b[3] == 0x2A))) {
            return image("image/tiff");
        }
        if (b.length > 12 && startsWith(b, "RIFF") && ascii(b, 8, 4).equals("WEBP")) {
            return image("image/webp");
        }
        if (b.length > 12 && ascii(b, 4, 4).equals("ftyp")) {
            String brand = ascii(b, 8, 4).toLowerCase(Locale.ROOT);
            if (brand.startsWith("hei") || brand.equals("mif1") || brand.equals("msf1")) {
                return image(brand.equals("heif") || brand.equals("mif1") ? "image/heif" : "image/heic");
            }
        }
        if (b.length > 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4) {
            return sniffZip(b);
        }
        if (b.length > 8 && (b[0] & 0xFF) == 0xD0 && (b[1] & 0xFF) == 0xCF && (b[2] & 0xFF) == 0x11
                && (b[3] & 0xFF) == 0xE0) {
            return sniffOle(b, ext);
        }
        if (looksLikeText(b) && (isTextExt(ext) || (declaredContentType != null
                && declaredContentType.toLowerCase(Locale.ROOT).startsWith("text/")))) {
            return new DetectedType(InputKind.TEXT, null);
        }
        return new DetectedType(InputKind.UNSUPPORTED, null);
    }

    /** True when the bytes contain no NUL and no excessive control characters. */
    static boolean looksLikeText(byte[] b) {
        int n = Math.min(b.length, 8192);
        // UTF-16 with BOM is text
        if (n >= 2 && (((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE)
                || ((b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF))) {
            return true;
        }
        int control = 0;
        for (int i = 0; i < n; i++) {
            int v = b[i] & 0xFF;
            if (v == 0) {
                return false;
            }
            if (v < 0x20 && v != '\n' && v != '\r' && v != '\t' && v != 0x0C) {
                control++;
            }
        }
        return control * 20 < n;
    }

    private static DetectedType sniffZip(byte[] b) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(b))) {
            ZipEntry e;
            int guard = 0;
            while ((e = zip.getNextEntry()) != null && guard++ < 200) {
                String name = e.getName();
                if (name.startsWith("word/")) {
                    return new DetectedType(InputKind.DOCX, null);
                }
                if (name.startsWith("xl/")) {
                    return new DetectedType(InputKind.XLSX, null);
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        return new DetectedType(InputKind.UNSUPPORTED, null);
    }

    private static DetectedType sniffOle(byte[] b, String ext) {
        try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(b))) {
            if (fs.getRoot().hasEntry("WordDocument")) {
                return new DetectedType(InputKind.DOC, null);
            }
            if (fs.getRoot().hasEntry("Workbook") || fs.getRoot().hasEntry("Book")) {
                return new DetectedType(InputKind.XLS, null);
            }
        } catch (Exception ignored) {
            // fall back to extension below
        }
        if (ext.equals("doc")) {
            return new DetectedType(InputKind.DOC, null);
        }
        if (ext.equals("xls")) {
            return new DetectedType(InputKind.XLS, null);
        }
        return new DetectedType(InputKind.UNSUPPORTED, null);
    }

    private static DetectedType image(String mime) {
        return new DetectedType(InputKind.IMAGE, mime);
    }

    private static boolean isTextExt(String ext) {
        return ext.equals("txt") || ext.equals("csv") || ext.equals("md");
    }

    static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int i = fileName.lastIndexOf('.');
        return i < 0 ? "" : fileName.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean startsWith(byte[] b, String prefix) {
        if (b.length < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (b[i] != (byte) prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static String ascii(byte[] b, int off, int len) {
        return new String(b, off, Math.min(len, b.length - off), StandardCharsets.ISO_8859_1);
    }
}
