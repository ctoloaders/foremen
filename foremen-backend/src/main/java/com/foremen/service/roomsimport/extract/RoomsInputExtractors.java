package com.foremen.service.roomsimport.extract;

import com.foremen.config.roomsimport.RoomsImportProperties;

import java.util.List;

/**
 * Facade: sniffs the content, routes to the matching {@link InputExtractor} and converts any
 * parse failure into a {@code FILE_UNREADABLE} result (Requirement 6.5). Only
 * {@link PdfPageLimitExceededException} propagates, because the page limit is a request error.
 */
public class RoomsInputExtractors {

    private final List<InputExtractor> extractors;

    public RoomsInputExtractors(RoomsImportProperties props) {
        this(List.of(new TextInputExtractor(), new OfficeInputExtractor(),
                new PdfInputExtractor(props.limits().maxPdfPages(), props.limits().minPdfTextChars()),
                new ImageInputExtractor()));
    }

    RoomsInputExtractors(List<InputExtractor> extractors) {
        this.extractors = List.copyOf(extractors);
    }

    public ExtractedInput extract(String fileName, String declaredContentType, byte[] content) {
        DetectedType type = ContentSniffer.detect(fileName, declaredContentType, content);
        for (InputExtractor ex : extractors) {
            if (ex.supports(type.kind())) {
                try {
                    return ex.extract(fileName, content, type);
                } catch (PdfPageLimitExceededException e) {
                    throw e;
                } catch (Exception | OutOfMemoryError e) {
                    return ExtractedInput.unreadable(fileName, type.kind());
                }
            }
        }
        return ExtractedInput.unreadable(fileName, type.kind());
    }
}
