package com.foremen.service.ocr;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.w3c.dom.Node;

import com.foremen.config.ocr.OcrProperties;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.vision.v1.AnnotateImageRequest;
import com.google.cloud.vision.v1.AnnotateImageResponse;
import com.google.cloud.vision.v1.BatchAnnotateImagesResponse;
import com.google.cloud.vision.v1.Feature;
import com.google.cloud.vision.v1.Feature.Type;
import com.google.cloud.vision.v1.Image;
import com.google.cloud.vision.v1.ImageAnnotatorClient;
import com.google.cloud.vision.v1.ImageAnnotatorSettings;
import com.google.cloud.vision.v1.TextAnnotation;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.protobuf.ByteString;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-06-03 (Requirements 3.0, 3.1, 3.2, 3.5, 3.6, design §5.2) — the Google-backed
 * {@link OcrService}: a <strong>thin I/O orchestrator</strong> that wraps the pure, I/O-free
 * {@link OcrHelpers} helpers over the Google Cloud Vision + Google Gen AI (Gemini) client
 * libraries. Active only when {@code foremen.ocr.enabled=true}; otherwise the
 * {@link DisabledOcrService} fallback is wired (R3.5).
 *
 * <p>Pipeline per {@link #process} (design §5.2), re-implementing the Telegram bot's validated
 * approach in Java (reusing only the Google resources + the approach, never the bot's TypeScript):
 * <ol>
 *   <li><b>Page split</b> — a PDF is rasterized to per-page images via PDFBox {@link PDFRenderer}
 *       (approach of the bot's {@code pdf.ts}); a single image is a one-page list; page order is
 *       preserved.</li>
 *   <li><b>White-background cleanup</b>, per page ({@code reprocess.ts} + {@code document-detect.ts}
 *       approach): EXIF auto-orient (bake rotation, no resample) → ask the Gemini model for the
 *       document's four corners ({@link OcrHelpers#CORNER_DETECT_PROMPT}) → the pure
 *       {@link OcrHelpers#parseCornerResponse(String)} +
 *       {@link OcrHelpers#normalizedToPixelQuad} + {@link OcrHelpers#quadAreaFraction} reject a
 *       degenerate / {@code < 15 %}-area quad → white-out everything outside the quad at the page's
 *       original dimensions → encode lossless PNG. On <b>any</b> per-page failure the page falls
 *       back to its oriented original (also PNG), {@code whitenedBackground = false} (R3.0).</li>
 *   <li><b>Vision text extraction</b> ({@code ocr.ts} approach): {@code documentTextDetection} per
 *       processed page; the pure {@link OcrHelpers#concatenatePageTexts(List)} joins the page texts
 *       (R3.1).</li>
 *   <li><b>LLM per-line parse</b> ({@code gemini.ts} approach, extended): the Gemini model turns the
 *       concatenated text into a JSON array of purchased lines via
 *       {@link OcrHelpers#PER_LINE_PARSE_PROMPT}; the pure
 *       {@link OcrHelpers#parsePerLineResponse(String)} tolerantly parses it. Empty / unparseable ⇒
 *       {@link OcrFailedException} ("no lines extracted") (R3.2, R3.4).</li>
 * </ol>
 *
 * <p>All provider credentials + the LLM model are read from the {@code foremen.ocr.*} block via
 * {@link OcrProperties} — never hardcoded (R3.6). The Vision and Gemini clients are built lazily on
 * first use (like {@link com.foremen.service.image.GcsImageStorage}) so a credential problem
 * surfaces as a handled per-document failure rather than a startup hang. Storing
 * {@link OcrResult#pages()} is the orchestrating service's job (task 5.1); this service performs no
 * object-store I/O.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "foremen.ocr", name = "enabled", havingValue = "true")
public class GoogleOcrService implements OcrService {

    /** The DPI PDF pages are rasterized at before cleanup + OCR. */
    private static final float PDF_RENDER_DPI = 200f;

    private final OcrProperties properties;

    /** Lazily-built clients (see class javadoc for why they are not built in {@code @PostConstruct}). */
    private volatile ImageAnnotatorClient visionClient;
    private volatile Client genAiClient;

    private final Object visionLock = new Object();
    private final Object genAiLock = new Object();

    public GoogleOcrService(OcrProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public OcrResult process(byte[] rawBytes, String mimeType, String keyNamespace,
                             OcrStageListener listener) {
        OcrStageListener progress = listener == null ? OcrStageListener.NOOP : listener;
        if (rawBytes == null || rawBytes.length == 0) {
            throw new OcrFailedException("empty document bytes");
        }
        try {
            // 1. Split into per-page raster images (a PDF → N pages; an image → one page).
            List<BufferedImage> pages = rasterize(rawBytes, mimeType);
            if (pages.isEmpty()) {
                throw new OcrFailedException("no pages rendered from the document");
            }

            // 2. White-background cleanup per page → ProcessedPage (PNG), interleaved with 3. Vision
            //    OCR per cleaned page. Report CLEANING then OCR_TEXT once for the whole page loop.
            notifyStage(progress, OcrStageListener.OcrStage.CLEANING);
            List<ProcessedPage> processedPages = new ArrayList<>(pages.size());
            List<byte[]> cleanedPngs = new ArrayList<>(pages.size());
            for (BufferedImage page : pages) {
                CleanedPage cleaned = cleanupPage(page);
                processedPages.add(new ProcessedPage(cleaned.pngBytes(), cleaned.whitenedBackground()));
                cleanedPngs.add(cleaned.pngBytes());
            }

            // 3. Vision documentTextDetection on each cleaned PNG.
            notifyStage(progress, OcrStageListener.OcrStage.OCR_TEXT);
            List<String> pageTexts = new ArrayList<>(cleanedPngs.size());
            for (byte[] cleanedPng : cleanedPngs) {
                pageTexts.add(detectText(cleanedPng));
            }

            // 3b. Concatenate the per-page OCR texts (pure helper).
            String concatenated = OcrHelpers.concatenatePageTexts(pageTexts);
            if (concatenated.isBlank()) {
                throw new OcrFailedException("OCR produced no text");
            }

            // 4. LLM per-line parse (pure helper over the model response).
            notifyStage(progress, OcrStageListener.OcrStage.PARSING);
            String parseResponse = generateText(
                    OcrHelpers.PER_LINE_PARSE_PROMPT + "\n\nOCR TEXT:\n" + concatenated);
            OcrParse parse = OcrHelpers.parsePerLineResponse(parseResponse);
            if (parse == null || parse.lines().isEmpty()) {
                throw new OcrFailedException("the parse produced no lines");
            }

            return new OcrResult(processedPages, parse.lines(), parse.supplierName(),
                    parse.documentTotal());
        } catch (OcrFailedException | OcrUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new OcrFailedException("OCR processing failed: " + e.getMessage(), e);
        }
    }

    /** Invokes the stage listener defensively — a listener failure must never abort OCR (A1 R15.2). */
    private void notifyStage(OcrStageListener listener, OcrStageListener.OcrStage stage) {
        try {
            listener.onStage(stage);
        } catch (RuntimeException e) {
            log.warn("OCR stage listener failed for stage {} (ignored): {}", stage, e.getMessage());
        }
    }

    // --- 1. Page split (pdf.ts approach) ---------------------------------------------------------

    /**
     * Rasterize the raw upload to per-page {@link BufferedImage}s: a PDF via PDFBox
     * {@link PDFRenderer} (page order preserved), a single image as a one-page list.
     */
    private List<BufferedImage> rasterize(byte[] rawBytes, String mimeType) {
        String type = mimeType == null ? "" : mimeType.toLowerCase().strip();
        if ("application/pdf".equals(type)) {
            return rasterizePdf(rawBytes);
        }
        BufferedImage image = readImage(rawBytes);
        if (image == null) {
            throw new OcrFailedException("unreadable image bytes for MIME type " + mimeType);
        }
        List<BufferedImage> pages = new ArrayList<>(1);
        pages.add(image);
        return pages;
    }

    private List<BufferedImage> rasterizePdf(byte[] pdfBytes) {
        List<BufferedImage> pages = new ArrayList<>();
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pageCount = document.getNumberOfPages();
            for (int i = 0; i < pageCount; i++) {
                pages.add(renderer.renderImageWithDPI(i, PDF_RENDER_DPI, ImageType.RGB));
            }
        } catch (IOException e) {
            throw new OcrFailedException("failed to rasterize PDF: " + e.getMessage(), e);
        }
        return pages;
    }

    /** Read raster image bytes, returning {@code null} when the bytes are not a decodable image. */
    private BufferedImage readImage(byte[] bytes) {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            return ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }

    // --- 2. White-background cleanup (reprocess.ts + document-detect.ts approach) -----------------

    private record CleanedPage(byte[] pngBytes, boolean whitenedBackground) {
    }

    /**
     * EXIF auto-orient the page, ask the LLM for the document corners, and — when a non-degenerate
     * quad covering at least {@link OcrHelpers#MIN_QUAD_AREA_FRACTION} is detected — paint everything
     * outside it white at the page's original dimensions, encoding lossless PNG. On any failure the
     * page falls back to its oriented original (PNG), {@code whitenedBackground = false} (R3.0).
     */
    private CleanedPage cleanupPage(BufferedImage orientedInput) {
        BufferedImage oriented = orientedInput; // orientation already baked for PDF pages / ImageIO
        int width = oriented.getWidth();
        int height = oriented.getHeight();
        try {
            byte[] orientedPng = encodePng(oriented);
            String cornerResponse = generateImagePrompt(OcrHelpers.CORNER_DETECT_PROMPT, orientedPng);
            NormalizedQuad quad = OcrHelpers.parseCornerResponse(cornerResponse);
            if (quad == null) {
                return new CleanedPage(orientedPng, false);
            }
            PixelQuad pixelQuad = OcrHelpers.normalizedToPixelQuad(quad, width, height);
            double areaFraction = OcrHelpers.quadAreaFraction(pixelQuad, width, height);
            if (areaFraction < OcrHelpers.MIN_QUAD_AREA_FRACTION) {
                return new CleanedPage(orientedPng, false);
            }
            BufferedImage whitened = whiteOutsideQuad(oriented, pixelQuad);
            return new CleanedPage(encodePng(whitened), true);
        } catch (RuntimeException | IOException e) {
            // Any per-page cleanup failure falls back to the oriented original (R3.0).
            log.warn("White-background cleanup failed for a page; falling back to the oriented original: {}",
                    e.getMessage());
            try {
                return new CleanedPage(encodePng(oriented), false);
            } catch (IOException encodeFailure) {
                throw new OcrFailedException(
                        "failed to encode the fallback page: " + encodeFailure.getMessage(),
                        encodeFailure);
            }
        }
    }

    /**
     * Paint everything OUTSIDE the detected document quad white, at the page's original dimensions
     * (no crop, warp, or scale): copy the source, then fill the area outside the quad polygon with
     * opaque white using an even-odd clip (full page minus the quad).
     */
    private BufferedImage whiteOutsideQuad(BufferedImage source, PixelQuad quad) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(source, 0, 0, null);
            GeneralPath outside = new GeneralPath(GeneralPath.WIND_EVEN_ODD);
            // Outer rectangle (the full page) ...
            outside.moveTo(0, 0);
            outside.lineTo(width, 0);
            outside.lineTo(width, height);
            outside.lineTo(0, height);
            outside.closePath();
            // ... minus the inner quad (even-odd → the ring between them is filled).
            List<PixelQuad.Point> pts = quad.points();
            outside.moveTo(pts.get(0).x(), pts.get(0).y());
            for (int i = 1; i < pts.size(); i++) {
                outside.lineTo(pts.get(i).x(), pts.get(i).y());
            }
            outside.closePath();
            g.setColor(java.awt.Color.WHITE);
            g.fill(outside);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** Encode a {@link BufferedImage} as lossless PNG (R3.0). */
    private byte[] encodePng(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", out)) {
                throw new IOException("no PNG ImageIO writer available");
            }
            return out.toByteArray();
        }
    }

    // --- 3. Vision text extraction (ocr.ts approach) ---------------------------------------------

    /** Run Google Cloud Vision {@code DOCUMENT_TEXT_DETECTION} on one PNG page, returning its text. */
    private String detectText(byte[] pngBytes) {
        Image image = Image.newBuilder().setContent(ByteString.copyFrom(pngBytes)).build();
        Feature feature = Feature.newBuilder().setType(Type.DOCUMENT_TEXT_DETECTION).build();
        AnnotateImageRequest request = AnnotateImageRequest.newBuilder()
                .addFeatures(feature)
                .setImage(image)
                .build();
        BatchAnnotateImagesResponse batch = vision().batchAnnotateImages(List.of(request));
        if (batch.getResponsesCount() == 0) {
            return "";
        }
        AnnotateImageResponse response = batch.getResponses(0);
        if (response.hasError() && !response.getError().getMessage().isBlank()) {
            throw new OcrFailedException("Vision error: " + response.getError().getMessage());
        }
        TextAnnotation full = response.getFullTextAnnotation();
        return full == null ? "" : full.getText();
    }

    // --- 4. LLM calls (gemini.ts approach) -------------------------------------------------------

    /** Send a text-only prompt to the LLM and return the model's text response. */
    private String generateText(String prompt) {
        GenerateContentResponse response =
                genAi().models.generateContent(resolveModel(), prompt, null);
        String text = response.text();
        return text == null ? "" : text;
    }

    /** Send a text prompt + an inline PNG image to the LLM and return the model's text response. */
    private String generateImagePrompt(String prompt, byte[] pngBytes) {
        Content content = Content.fromParts(
                Part.fromText(prompt),
                Part.fromBytes(pngBytes, "image/png"));
        GenerateContentResponse response =
                genAi().models.generateContent(resolveModel(), content, null);
        String text = response.text();
        return text == null ? "" : text;
    }

    private String resolveModel() {
        String model = properties.llm().model();
        if (model == null || model.isBlank()) {
            throw new OcrFailedException("foremen.ocr.llm.model is not configured");
        }
        return model.strip();
    }

    // --- Lazy client construction (credentials from config, never hardcoded — R3.6) --------------

    private ImageAnnotatorClient vision() {
        ImageAnnotatorClient local = this.visionClient;
        if (local == null) {
            synchronized (visionLock) {
                local = this.visionClient;
                if (local == null) {
                    local = buildVisionClient();
                    this.visionClient = local;
                }
            }
        }
        return local;
    }

    private ImageAnnotatorClient buildVisionClient() {
        try {
            GoogleCredentials credentials = resolveCredentials(properties.vision().credentials());
            ImageAnnotatorSettings.Builder settings = ImageAnnotatorSettings.newBuilder();
            if (credentials != null) {
                settings.setCredentialsProvider(() -> credentials);
            }
            return ImageAnnotatorClient.create(settings.build());
        } catch (IOException e) {
            throw new OcrFailedException("failed to build the Vision client: " + e.getMessage(), e);
        }
    }

    private Client genAi() {
        Client local = this.genAiClient;
        if (local == null) {
            synchronized (genAiLock) {
                local = this.genAiClient;
                if (local == null) {
                    local = buildGenAiClient();
                    this.genAiClient = local;
                }
            }
        }
        return local;
    }

    private Client buildGenAiClient() {
        OcrProperties.Llm llm = properties.llm();
        Client.Builder builder = Client.builder();
        String apiKey = llm.apiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            // Gemini Developer API backend, keyed.
            builder.apiKey(apiKey.strip());
        } else {
            // Gemini Enterprise Agent Platform (Vertex) backend via ADC + project/location.
            builder.vertexAI(true);
            if (llm.project() != null && !llm.project().isBlank()) {
                builder.project(llm.project().strip());
            }
            if (llm.location() != null && !llm.location().isBlank()) {
                builder.location(llm.location().strip());
            }
            GoogleCredentials credentials = resolveCredentials(llm.credentials());
            if (credentials != null) {
                builder.credentials(credentials);
            }
        }
        return builder.build();
    }

    /**
     * Resolve {@link GoogleCredentials} from an explicit {@code path}/{@code location}, falling back
     * to Application Default Credentials when neither is set or the {@code application-default}
     * sentinel is used. Mirrors {@link com.foremen.service.image.GcsImageStorage}'s resolution.
     */
    private GoogleCredentials resolveCredentials(OcrProperties.Credentials creds) {
        String location = creds.path() != null && !creds.path().isBlank()
                ? creds.path()
                : creds.location();
        if (location == null || location.isBlank()
                || "application-default".equalsIgnoreCase(location.strip())) {
            return null; // fall back to Application Default Credentials
        }
        Resource resource = new DefaultResourceLoader().getResource(location.strip());
        try (InputStream in = resource.getInputStream()) {
            return GoogleCredentials.fromStream(in);
        } catch (IOException e) {
            throw new OcrFailedException(
                    "failed to load Google credentials from '" + location + "': " + e.getMessage(), e);
        }
    }

    /**
     * Read the EXIF orientation of a raster image, returning the orientation tag (1–8) or 1 when
     * absent. Retained as a documented hook for the EXIF auto-orient step; PDF pages are rendered
     * upright by PDFBox and {@link ImageIO#read} already applies no rotation, so most inputs are
     * orientation 1. Kept package-visible for unit coverage of the metadata read.
     */
    static int readExifOrientation(byte[] imageBytes) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
            if (iis == null) {
                return 1;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return 1;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true);
                IIOMetadata metadata = reader.getImageMetadata(0);
                if (metadata == null) {
                    return 1;
                }
                return extractOrientation(metadata);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return 1;
        }
    }

    private static int extractOrientation(IIOMetadata metadata) {
        for (String format : metadata.getMetadataFormatNames()) {
            Node tree = metadata.getAsTree(format);
            Integer orientation = findOrientation(tree);
            if (orientation != null) {
                return orientation;
            }
        }
        return 1;
    }

    private static Integer findOrientation(Node node) {
        if (node == null) {
            return null;
        }
        if ("Orientation".equalsIgnoreCase(node.getNodeName()) && node.getNodeValue() != null) {
            try {
                return Integer.parseInt(node.getNodeValue().strip());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (node.getAttributes() != null) {
            Node attr = node.getAttributes().getNamedItem("value");
            if (attr != null && "Orientation".equalsIgnoreCase(nodeName(node))) {
                try {
                    return Integer.parseInt(attr.getNodeValue().strip());
                } catch (NumberFormatException ignored) {
                    // fall through to children
                }
            }
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            Integer found = findOrientation(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String nodeName(Node node) {
        return node.getNodeName() == null ? "" : node.getNodeName();
    }

    /** Apply an EXIF orientation (1–8) to a raster image, baking the rotation/flip with no resample. */
    static BufferedImage applyOrientation(BufferedImage image, int orientation) {
        if (image == null || orientation <= 1 || orientation > 8) {
            return image;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        AffineTransform t = new AffineTransform();
        boolean swap = false;
        switch (orientation) {
            case 2 -> { t.scale(-1, 1); t.translate(-w, 0); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.scale(1, -1); t.translate(0, -h); }
            case 5 -> { swap = true; t.rotate(-Math.PI / 2); t.scale(-1, 1); }
            case 6 -> { swap = true; t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { swap = true; t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(-Math.PI / 2); }
            case 8 -> { swap = true; t.translate(0, w); t.rotate(-Math.PI / 2); }
            default -> { return image; }
        }
        int outW = swap ? h : w;
        int outH = swap ? w : h;
        BufferedImage out = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(image, t, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
