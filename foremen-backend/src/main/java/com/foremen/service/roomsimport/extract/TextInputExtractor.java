package com.foremen.service.roomsimport.extract;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Plain text / CSV / MD with BOM + UTF-8 + windows-1250/1251 detection (no OCR). */
public class TextInputExtractor implements InputExtractor {

    private static final Charset CP1250 = Charset.forName("windows-1250");
    private static final Charset CP1251 = Charset.forName("windows-1251");

    @Override
    public boolean supports(InputKind kind) {
        return kind == InputKind.TEXT;
    }

    @Override
    public ExtractedInput extract(String fileName, byte[] c, DetectedType type) {
        return ExtractedInput.ofText(fileName, InputKind.TEXT, decode(c));
    }

    /** Decodes bytes, picking the charset by BOM, strict UTF-8, then a Polish/Cyrillic heuristic. */
    public static String decode(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            int high = 0;
            for (byte x : b) {
                if ((x & 0x80) != 0) {
                    high++;
                }
            }
            // Cyrillic text is mostly high bytes; Polish text has only sparse diacritics
            boolean cyrillic = b.length > 0 && high * 100 / b.length > 30;
            return new String(b, cyrillic ? CP1251 : CP1250);
        }
    }
}
