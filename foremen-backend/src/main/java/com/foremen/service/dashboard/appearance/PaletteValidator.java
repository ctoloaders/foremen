package com.foremen.service.dashboard.appearance;

import com.foremen.exception.ForemenApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates a user dashboard palette (FOR-10 R5.8): 1..8 distinct {@code #RRGGBB} colors. */
@Component
public class PaletteValidator {

    public static final int MAX_COLORS = 8;
    private static final Pattern HEX = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    /** @return true when the palette is a non-empty list of up to 8 distinct (case-insensitive) hex colors. */
    public static boolean isValid(List<String> palette) {
        if (palette == null || palette.isEmpty() || palette.size() > MAX_COLORS) {
            return false;
        }
        Set<String> seen = new HashSet<>();
        for (String c : palette) {
            if (c == null || !HEX.matcher(c).matches() || !seen.add(c.toUpperCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    /** Returns the normalized (upper-case) palette or throws 400 {@code error.dashboard.appearance.palette.invalid}. */
    public List<String> validate(List<String> palette) {
        if (!isValid(palette)) {
            throw new ForemenApiException(HttpStatus.BAD_REQUEST, "error.dashboard.appearance.palette.invalid");
        }
        List<String> out = new ArrayList<>(palette.size());
        for (String c : palette) {
            out.add(c.toUpperCase(Locale.ROOT));
        }
        return out;
    }
}
