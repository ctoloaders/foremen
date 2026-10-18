package com.foremen.service.ocr;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * FOR-06-03 (Requirements 3.0, 3.1, 3.2, design §5.2) — the pure, I/O-free helpers the
 * {@code GoogleOcrService} I/O orchestrator (task 4.2) wraps. Every method here is deterministic,
 * side-effect free, and performs no network / file / provider calls, so they are unit- and
 * property-testable in isolation (the property tests are task 3.2a).
 *
 * <p>Responsibilities (all pure):
 * <ul>
 *   <li>{@link #parseCornerResponse(String)} — tolerant corner-detect model-response parse,
 *       validating four points and clamping to {@code [0,1]} (R3.0).</li>
 *   <li>{@link #normalizedToPixelQuad(NormalizedQuad, int, int)} + {@link #quadAreaFraction(PixelQuad, int, int)}
 *       — scale a normalized quad to pixels and compute its area fraction; a degenerate or
 *       {@code < MIN_QUAD_AREA_FRACTION} quad means the orchestrator falls back to the full page
 *       (R3.0).</li>
 *   <li>{@link #concatenatePageTexts(List)} — join per-page OCR texts with {@code --- Page n ---}
 *       separators, empty iff every page is blank (R3.1).</li>
 *   <li>{@link #parsePerLineResponse(String)} — tolerant per-line JSON array parse, dropping lines
 *       with no {@code rawName}, {@code abs()}-normalizing negative amounts, capturing
 *       supplier/total, {@code null} on unparseable (R3.2).</li>
 * </ul>
 *
 * <p>The {@link #CORNER_DETECT_PROMPT} and {@link #PER_LINE_PARSE_PROMPT} constants are the prompts
 * the orchestrator sends to the Google vision / LLM models; they live here (next to the response
 * parsers) so the request shape and the response contract stay in one place.
 */
public final class OcrHelpers {

    private OcrHelpers() {
    }

    /** A detected document quad covering less than this fraction of the page is rejected (R3.0). */
    public static final double MIN_QUAD_AREA_FRACTION = 0.15;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The prompt asking the Google vision model for the uploaded document's four corners in
     * normalized {@code [0,1]} coordinates as JSON only. The response is parsed by
     * {@link #parseCornerResponse(String)} (design §5.2, step 2).
     */
    public static final String CORNER_DETECT_PROMPT = """
            You are given a single photo that contains one receipt or invoice document.
            Find the four corners of the document (the sheet of paper), ignoring the surrounding \
            background, hands, or table.
            Respond with JSON ONLY, no prose, no markdown fences, in exactly this shape:
            {"corners":[{"x":0.0,"y":0.0},{"x":1.0,"y":0.0},{"x":1.0,"y":1.0},{"x":0.0,"y":1.0}]}
            Coordinates are normalized to the image size: x and y are fractions in the range 0.0 to \
            1.0, with the origin at the top-left corner, x increasing to the right and y increasing \
            downwards. Return the corners in order: top-left, top-right, bottom-right, bottom-left.
            If you cannot confidently locate the document, return {"corners":[]}.
            """;

    /**
     * The prompt asking the Google LLM to turn the concatenated OCR text into a JSON array of
     * purchased lines plus the document-level supplier and total. The response is parsed by
     * {@link #parsePerLineResponse(String)} (design §5.2, step 4). Extended from the bot's single
     * receipt summary to one object per purchased line.
     */
    public static final String PER_LINE_PARSE_PROMPT = """
            You are given the raw OCR text of a purchase receipt or invoice.
            Extract every purchased line item. Respond with JSON ONLY, no prose, no markdown fences, \
            in exactly this shape:
            {"supplierName": string|null,
             "documentTotal": number|null,
             "lines": [
               {"rawName": string,
                "quantity": number|null,
                "unitHint": string|null,
                "unitPrice": number|null,
                "lineTotal": number|null,
                "manufacturerHint": string|null}
             ]}
            Rules:
            - One object per purchased item, in the order they appear on the document.
            - rawName is the verbatim product text as printed; never invent or translate it.
            - quantity, unitPrice and lineTotal are plain numbers (no currency symbols, dot decimal \
            separator); use null when the value is not printed.
            - unitHint is the printed unit (e.g. szt, kg, m2) verbatim, or null.
            - manufacturerHint is the printed brand/manufacturer verbatim, or null.
            - supplierName is the store/supplier name, documentTotal is the grand total; use null \
            when not present.
            - Skip non-item rows (subtotals, taxes, payment lines, loyalty points).
            - If there are no purchased items, return "lines": [].
            """;

    // --- Corner-response parse (R3.0) ------------------------------------------------------------

    /**
     * Parse the Google vision corner-detect model response into a {@link NormalizedQuad}. Tolerant of
     * markdown fences and surrounding prose (the JSON object is located by a brace scan). Returns a
     * quad only when exactly four valid numeric corner points are present; every coordinate is
     * clamped into {@code [0,1]}. Returns {@code null} for any missing / malformed / wrong-count
     * input (so the orchestrator falls back to the full page).
     *
     * @param text the raw model response (bare JSON, fenced JSON, or JSON wrapped in prose)
     * @return the clamped four-point quad, or {@code null} when four valid points are not present
     */
    public static NormalizedQuad parseCornerResponse(String text) {
        JsonNode root = extractJson(text);
        if (root == null) {
            return null;
        }
        JsonNode cornersNode = root.isArray() ? root : root.get("corners");
        if (cornersNode == null || !cornersNode.isArray() || cornersNode.size() != 4) {
            return null;
        }
        List<NormalizedQuad.Point> points = new ArrayList<>(4);
        for (JsonNode cornerNode : cornersNode) {
            Double x = readCoordinate(cornerNode, "x", 0);
            Double y = readCoordinate(cornerNode, "y", 1);
            if (x == null || y == null) {
                return null;
            }
            points.add(new NormalizedQuad.Point(clamp01(x), clamp01(y)));
        }
        return new NormalizedQuad(List.copyOf(points));
    }

    /**
     * Read an {@code x}/{@code y} coordinate from a corner node, accepting both the object form
     * ({@code {"x":..,"y":..}}) and the positional array form ({@code [x, y]}). Returns {@code null}
     * when the coordinate is absent or not numeric.
     */
    private static Double readCoordinate(JsonNode cornerNode, String field, int arrayIndex) {
        if (cornerNode == null) {
            return null;
        }
        JsonNode value;
        if (cornerNode.isArray()) {
            value = cornerNode.get(arrayIndex);
        } else {
            value = cornerNode.get(field);
        }
        if (value == null || !value.isNumber()) {
            return null;
        }
        double d = value.asDouble();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return null;
        }
        return d;
    }

    // --- Normalized → pixel quad + area fraction (R3.0) ------------------------------------------

    /**
     * Scale a normalized {@code [0,1]} quad to pixel coordinates at the page's original dimensions.
     *
     * @param quad   the normalized quad (four clamped points)
     * @param width  the page width in pixels ({@code > 0})
     * @param height the page height in pixels ({@code > 0})
     * @return the quad in pixel space
     * @throws IllegalArgumentException if {@code quad} is null or {@code width}/{@code height} are not positive
     */
    public static PixelQuad normalizedToPixelQuad(NormalizedQuad quad, int width, int height) {
        if (quad == null) {
            throw new IllegalArgumentException("quad must not be null");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be positive");
        }
        List<PixelQuad.Point> pixelPoints = new ArrayList<>(quad.points().size());
        for (NormalizedQuad.Point p : quad.points()) {
            pixelPoints.add(new PixelQuad.Point(p.x() * width, p.y() * height));
        }
        return new PixelQuad(List.copyOf(pixelPoints));
    }

    /**
     * The fraction of the page covered by the quad: its shoelace-formula area divided by
     * {@code width * height}, clamped to {@code [0,1]}. A degenerate (zero-area / collinear) quad
     * returns {@code 0.0}. A quad whose fraction is below {@link #MIN_QUAD_AREA_FRACTION} signals the
     * orchestrator to use the full-page fallback (R3.0).
     *
     * @param quad   the quad in pixel space
     * @param width  the page width in pixels ({@code > 0})
     * @param height the page height in pixels ({@code > 0})
     * @return the covered area fraction in {@code [0,1]}
     * @throws IllegalArgumentException if {@code quad} is null or {@code width}/{@code height} are not positive
     */
    public static double quadAreaFraction(PixelQuad quad, int width, int height) {
        if (quad == null) {
            throw new IllegalArgumentException("quad must not be null");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width and height must be positive");
        }
        List<PixelQuad.Point> pts = quad.points();
        if (pts.size() < 3) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 0; i < pts.size(); i++) {
            PixelQuad.Point a = pts.get(i);
            PixelQuad.Point b = pts.get((i + 1) % pts.size());
            sum += (a.x() * b.y()) - (b.x() * a.y());
        }
        double area = Math.abs(sum) / 2.0;
        double fraction = area / ((double) width * (double) height);
        return clamp01(fraction);
    }

    // --- Page-text concatenation (R3.1) ----------------------------------------------------------

    /**
     * Join per-page OCR texts with sequential {@code --- Page n ---} separators, in input order. The
     * page numbering is 1-based and follows the input index (blank pages still consume a page
     * number, preserving positional meaning). Returns the empty string <strong>iff</strong> every
     * page text is blank/whitespace-only (or the list is null/empty).
     *
     * @param pageTexts the per-page OCR texts (may contain nulls / blanks)
     * @return the concatenated multi-page text, or the empty string when all pages are blank
     */
    public static String concatenatePageTexts(List<String> pageTexts) {
        if (pageTexts == null || pageTexts.isEmpty()) {
            return "";
        }
        boolean allBlank = pageTexts.stream().allMatch(t -> t == null || t.isBlank());
        if (allBlank) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pageTexts.size(); i++) {
            String text = pageTexts.get(i) == null ? "" : pageTexts.get(i);
            if (i > 0) {
                sb.append('\n');
            }
            sb.append("--- Page ").append(i + 1).append(" ---\n");
            sb.append(text);
        }
        return sb.toString();
    }

    // --- Per-line response parse (R3.2) ----------------------------------------------------------

    /**
     * Parse the Google LLM per-line response into an {@link OcrParse}. Tolerant of markdown fences
     * and surrounding prose (the JSON is located by a brace/bracket scan). Accepts either the object
     * shape ({@code {"supplierName":..,"documentTotal":..,"lines":[..]}}) or a bare JSON array of
     * line objects. Line objects with no non-blank {@code rawName} are dropped; every numeric amount
     * ({@code quantity}, {@code unitPrice}, {@code lineTotal}) is {@code abs()}-normalized to a
     * non-negative value; {@code supplierName}/{@code documentTotal} are captured when present.
     * Returns {@code null} for unparseable output (so the orchestrator drives the {@code FAILED}
     * transition rather than persisting garbage).
     *
     * @param text the raw model response (bare / fenced / prose-wrapped JSON)
     * @return the parsed result (possibly with an empty {@code lines} list), or {@code null} when unparseable
     */
    public static OcrParse parsePerLineResponse(String text) {
        JsonNode root = extractJson(text);
        if (root == null) {
            return null;
        }
        JsonNode linesNode;
        String supplierName = null;
        BigDecimal documentTotal = null;
        if (root.isArray()) {
            linesNode = root;
        } else if (root.isObject()) {
            linesNode = root.get("lines");
            if (linesNode == null || !linesNode.isArray()) {
                return null;
            }
            supplierName = readText(root.get("supplierName"));
            documentTotal = absDecimal(readDecimal(root.get("documentTotal")));
        } else {
            return null;
        }

        List<ParsedLine> lines = new ArrayList<>();
        for (JsonNode lineNode : linesNode) {
            if (lineNode == null || !lineNode.isObject()) {
                continue;
            }
            String rawName = readText(lineNode.get("rawName"));
            if (rawName == null || rawName.isBlank()) {
                continue;
            }
            lines.add(new ParsedLine(
                    rawName.strip(),
                    absDecimal(readDecimal(lineNode.get("quantity"))),
                    readText(lineNode.get("unitHint")),
                    absDecimal(readDecimal(lineNode.get("unitPrice"))),
                    absDecimal(readDecimal(lineNode.get("lineTotal"))),
                    readText(lineNode.get("manufacturerHint"))));
        }
        return new OcrParse(List.copyOf(lines), supplierName, documentTotal);
    }

    // --- Shared pure utilities -------------------------------------------------------------------

    /**
     * Locate and parse a single JSON value out of a model response that may be bare JSON, JSON
     * wrapped in markdown fences, or JSON surrounded by prose. Strips fences, then — if a direct
     * parse fails — extracts the substring from the first opening brace/bracket to its matching
     * closing one and parses that. Returns {@code null} when no JSON value can be read.
     */
    private static JsonNode extractJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String candidate = stripMarkdownFences(text).strip();
        JsonNode node = tryReadJson(candidate);
        if (node != null) {
            return node;
        }
        String sliced = sliceOutermostJson(candidate);
        if (sliced != null && !sliced.equals(candidate)) {
            return tryReadJson(sliced);
        }
        return null;
    }

    private static JsonNode tryReadJson(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(candidate);
        } catch (Exception e) {
            return null;
        }
    }

    private static final Pattern FENCE = Pattern.compile("(?s)```(?:json)?\\s*(.*?)\\s*```");

    /** Return the first fenced block's content, or the input unchanged when there is no fence. */
    private static String stripMarkdownFences(String text) {
        Matcher m = FENCE.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return text;
    }

    /**
     * Return the substring spanning the first {@code {}/[} to its matching, brace-balanced close
     * (quote- and escape-aware), or {@code null} when no balanced JSON span is present.
     */
    private static String sliceOutermostJson(String text) {
        int start = -1;
        char open = 0;
        char close = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' || c == '[') {
                start = i;
                open = c;
                close = (c == '{') ? '}' : ']';
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static String readText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            String s = node.asText();
            return s.isBlank() ? null : s;
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        return null;
    }

    private static BigDecimal readDecimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isTextual()) {
            String s = node.asText().strip();
            if (s.isEmpty()) {
                return null;
            }
            try {
                return new BigDecimal(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static BigDecimal absDecimal(BigDecimal value) {
        return value == null ? null : value.abs();
    }

    private static double clamp01(double v) {
        if (v < 0.0) {
            return 0.0;
        }
        if (v > 1.0) {
            return 1.0;
        }
        return v;
    }
}
