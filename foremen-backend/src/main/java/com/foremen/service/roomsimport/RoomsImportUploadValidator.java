package com.foremen.service.roomsimport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.foremen.config.roomsimport.RoomsImportProperties;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.roomsimport.extract.ContentSniffer;
import com.foremen.service.roomsimport.extract.DetectedType;
import com.foremen.service.roomsimport.extract.InputKind;

/**
 * FOR-05-02 (Requirements 1.1, 1.4, 1.5, 14.3) — request validation of a start call, cheapest checks
 * first: empty input, file count, per-file size, type (content sniffing <b>and</b> extension), total
 * PDF pages. Nothing is stored here. Limits come from {@link RoomsImportProperties#limits()}.
 */
@Component
public class RoomsImportUploadValidator {

    /** Accepted file extensions (Requirement 1.4). */
    static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "tif", "tiff",
            "pdf", "docx", "doc", "xlsx", "xls", "txt", "csv", "md");

    private final RoomsImportProperties properties;

    public RoomsImportUploadValidator(RoomsImportProperties properties) {
        this.properties = properties;
    }

    /**
     * @param prompt the optional prompt
     * @param files  the uploaded parts (may be {@code null}); empty parts are ignored
     * @return the non-empty files to hand to the service
     * @throws ForemenApiException 400 / 413 / 415 with the offending file or the limit in {@code params}
     */
    public List<MultipartFile> validate(String prompt, List<MultipartFile> files) {
        List<MultipartFile> real = new ArrayList<>();
        if (files != null) {
            for (MultipartFile f : files) {
                if (f != null && !f.isEmpty()) {
                    real.add(f);
                }
            }
        }
        if ((prompt == null || prompt.isBlank()) && real.isEmpty()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, RoomsImportErrors.INPUT_EMPTY);
        }

        RoomsImportProperties.Limits limits = properties.limits();
        if (real.size() > limits.maxFiles()) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, RoomsImportErrors.FILE_COUNT, limits.maxFiles())
                    .withParams(Map.of("limit", limits.maxFiles(), "count", real.size()));
        }
        long maxBytes = limits.maxFileSize().toBytes();
        for (MultipartFile f : real) {
            if (f.getSize() > maxBytes) {
                String name = RoomsImportService.sanitizeFileName(f.getOriginalFilename());
                long maxMb = Math.max(1, maxBytes / (1024 * 1024));
                throw new ForemenApiException(HttpStatus.PAYLOAD_TOO_LARGE, RoomsImportErrors.FILE_SIZE, name, maxMb)
                        .withParams(params("fileName", name, "limitBytes", maxBytes));
            }
        }

        int totalPages = 0;
        for (MultipartFile f : real) {
            String name = RoomsImportService.sanitizeFileName(f.getOriginalFilename());
            byte[] bytes;
            try {
                bytes = f.getBytes();
            } catch (IOException e) {
                throw unsupported(name);
            }
            DetectedType type = ContentSniffer.detect(name, f.getContentType(), bytes);
            String ext = extensionOf(name);
            if (type.kind() == InputKind.UNSUPPORTED || (!ext.isEmpty() && !ALLOWED_EXTENSIONS.contains(ext))) {
                throw unsupported(name);
            }
            if (type.kind() == InputKind.PDF) {
                totalPages += pageCount(bytes);
                if (totalPages > limits.maxPdfPages()) {
                    throw new ForemenApiException(HttpStatus.BAD_REQUEST, RoomsImportErrors.PDF_PAGES,
                            limits.maxPdfPages())
                            .withParams(params("limit", limits.maxPdfPages(), "pages", totalPages));
                }
            }
        }
        return real;
    }

    private static ForemenApiException unsupported(String fileName) {
        return new ForemenApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, RoomsImportErrors.FILE_TYPE, fileName)
                .withParams(params("fileName", fileName));
    }

    /** Page count of a PDF; an unreadable PDF counts as 0 here (the pipeline reports it as unreadable). */
    private static int pageCount(byte[] bytes) {
        try (PDDocument doc = PDDocument.load(bytes)) {
            return doc.getNumberOfPages();
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    static String extensionOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }
}
