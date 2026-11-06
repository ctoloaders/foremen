package com.foremen.service.roomsimport.extract;

import com.foremen.service.roomsimport.model.InputInfo;
import com.foremen.service.roomsimport.model.Severity;
import com.foremen.service.roomsimport.model.Warning;
import com.foremen.service.roomsimport.model.WarningCodes;

import java.util.List;
import java.util.Map;

/**
 * Output of one input extractor (design B-extract).
 *
 * @param text            extracted text, or null when the input yields only images
 * @param pages           image pages (images and rasterized PDF pages)
 * @param pageCount       total PDF pages, else null
 * @param rasterizedPages number of PDF pages rendered to images, else null
 * @param warning         {@code FILE_UNREADABLE} when the input could not be parsed
 */
public record ExtractedInput(String fileName, InputKind kind, String text, List<PageImage> pages,
                             Integer pageCount, Integer rasterizedPages, Warning warning) {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_UNREADABLE = "UNREADABLE";

    public ExtractedInput {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    public boolean readable() {
        return warning == null;
    }

    public static ExtractedInput ofText(String fileName, InputKind kind, String text) {
        return new ExtractedInput(fileName, kind, text, List.of(), null, null, null);
    }

    public static ExtractedInput unreadable(String fileName, InputKind kind) {
        return new ExtractedInput(fileName, kind, null, List.of(), null, null,
                new Warning(WarningCodes.FILE_UNREADABLE, Severity.WARNING, null, null,
                        Map.of("fileName", fileName == null ? "" : fileName), null));
    }

    public InputInfo toInfo() {
        return new InputInfo(fileName, kind.name(), pageCount, rasterizedPages,
                readable() ? STATUS_OK : STATUS_UNREADABLE);
    }
}
