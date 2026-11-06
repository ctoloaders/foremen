package com.foremen.service.roomsimport;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Component;

import com.foremen.config.roomsimport.RoomsImportProperties;
import com.foremen.controller.model.RoomCreateRequest;
import com.foremen.dao.model.OpeningType;
import com.foremen.dao.model.RoomGeometry;
import com.foremen.dao.model.RoomsImportStatus;
import com.foremen.service.roomsimport.RoomsImportContextLoader.JobContext;
import com.foremen.service.roomsimport.ai.DetectRequest;
import com.foremen.service.roomsimport.ai.DetectResponse;
import com.foremen.service.roomsimport.ai.DetectResponse.DetectedRoom;
import com.foremen.service.roomsimport.ai.Measured;
import com.foremen.service.roomsimport.ai.MeasureRequest;
import com.foremen.service.roomsimport.ai.MeasureResponse;
import com.foremen.service.roomsimport.ai.RoomsVisionClient;
import com.foremen.service.roomsimport.ai.RoomsVisionException;
import com.foremen.service.roomsimport.classify.RoomTypeResolver;
import com.foremen.service.roomsimport.classify.RoomTypeResolver.Resolution;
import com.foremen.service.roomsimport.crop.RoomCropper;
import com.foremen.service.roomsimport.extract.ExtractedInput;
import com.foremen.service.roomsimport.extract.InputKind;
import com.foremen.service.roomsimport.extract.PageImage;
import com.foremen.service.roomsimport.extract.PdfPageLimitExceededException;
import com.foremen.service.roomsimport.extract.RoomsInputExtractors;
import com.foremen.service.roomsimport.geometry.GeometryNormalizer;
import com.foremen.service.roomsimport.model.CalculatedMetrics;
import com.foremen.service.roomsimport.model.DraftMedia;
import com.foremen.service.roomsimport.model.DraftRoom;
import com.foremen.service.roomsimport.model.ImportResult;
import com.foremen.service.roomsimport.model.ModelInfo;
import com.foremen.service.roomsimport.model.Severity;
import com.foremen.service.roomsimport.model.SourceRef;
import com.foremen.service.roomsimport.model.Warning;
import com.foremen.service.roomsimport.model.WarningCodes;
import com.foremen.service.roomsimport.units.PlausibilityCorrector;
import com.foremen.service.roomsimport.units.PlausibilityInput;
import com.foremen.service.roomsimport.units.PlausibilityInput.Quantity;
import com.foremen.service.roomsimport.units.PlausibilityResult;
import com.foremen.service.roomsimport.units.UnitConverter;
import com.foremen.service.roomsimport.validate.CountRule;
import com.foremen.service.roomsimport.validate.DimensionMissingRule;
import com.foremen.service.roomsimport.validate.DraftValidator;
import com.foremen.service.roomsimport.validate.ExistingRoomRef;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-05-02 (design B5) — orchestrates one import job:
 * {@code EXTRACTING → DETECTING_ROOMS → CLASSIFYING → MEASURING → VALIDATING}, returning the
 * {@link ImportResult}. Terminal transitions belong to the caller ({@link RoomsImportWorker}).
 *
 * <ul>
 *   <li>Every stage is committed through {@link RoomsImportStageMarker} (0 updated rows ⇒
 *       {@link JobCancelledException}); {@link RoomsImportCancellation#assertActive} runs before each
 *       stage and before <b>every</b> per-room Gemini call, so no new AI call starts after a cancel.</li>
 *   <li>Measuring runs with bounded concurrency; a failure of one room becomes
 *       {@code ROOM_ANALYSIS_FAILED} for that room only.</li>
 *   <li>Existing project rooms are only read, for {@code POSSIBLE_DUPLICATE_OF_EXISTING}.</li>
 *   <li>Logs carry job id, stage, counts and timings only — never user content.</li>
 * </ul>
 */
@Slf4j
@Component
public class RoomsImportPipeline {

    /** Page images per detection call (design B5 stage 2); results are merged. */
    static final int DETECT_IMAGE_BATCH = 8;
    /** Upper bound of the extracted text passed to one measuring call. */
    static final int MAX_MEASURE_TEXT_CHARS = 30_000;

    private final RoomsImportContextLoader loader;
    private final RoomsImportStageMarker stages;
    private final RoomsImportCancellation cancellation;
    private final RoomsImportStorage storage;
    private final RoomsInputExtractors extractors;
    private final RoomsVisionClient vision;
    private final GeometryNormalizer normalizer;
    private final DraftValidator validator;
    private final RoomCropper cropper;
    private final RoomsImportProperties properties;
    private final PlausibilityCorrector corrector;
    private final UnitConverter converter = new UnitConverter();

    /** Stated / scalar fields that are areas (m²); the other known scalar fields are lengths (m) or counts. */
    private static final Set<String> AREA_FIELDS = Set.of(PlausibilityInput.FLOOR_AREA, PlausibilityInput.WALL_AREA,
            PlausibilityInput.DOOR_AREA, PlausibilityInput.WINDOW_AREA);
    private static final Set<String> LENGTH_FIELDS = Set.of(PlausibilityInput.CEILING_HEIGHT,
            PlausibilityInput.NARROW_PLACE, PlausibilityInput.PERIMETER, PlausibilityInput.DOOR_HEIGHT,
            PlausibilityInput.DOOR_WIDTH, PlausibilityInput.WINDOW_HEIGHT, PlausibilityInput.WINDOW_WIDTH);
    private static final Set<String> COUNT_FIELDS = Set.of(PlausibilityInput.DOOR_COUNT,
            PlausibilityInput.WINDOW_COUNT);

    public RoomsImportPipeline(RoomsImportContextLoader loader, RoomsImportStageMarker stages,
                               RoomsImportCancellation cancellation, RoomsImportStorage storage,
                               RoomsInputExtractors extractors, RoomsVisionClient vision,
                               GeometryNormalizer normalizer, DraftValidator validator, RoomCropper cropper,
                               RoomsImportProperties properties, PlausibilityCorrector corrector) {
        this.corrector = corrector;
        this.loader = loader;
        this.stages = stages;
        this.cancellation = cancellation;
        this.storage = storage;
        this.extractors = extractors;
        this.vision = vision;
        this.normalizer = normalizer;
        this.validator = validator;
        this.cropper = cropper;
        this.properties = properties;
    }

    /**
     * Runs the job to a result.
     *
     * @throws JobCancelledException      the job was cancelled / replaced / already failed
     * @throws RoomsImportFailureException a job-level failure with a stable reason code
     */
    public ImportResult run(Long jobId) {
        long t0 = System.currentTimeMillis();
        JobContext ctx = loader.load(jobId).orElseThrow(() -> new JobCancelledException(jobId));

        // --- 1. EXTRACTING ----------------------------------------------------------------
        enter(jobId, RoomsImportStatus.EXTRACTING);
        List<Warning> projectWarnings = new ArrayList<>();
        List<ExtractedInput> inputs = extract(ctx, projectWarnings);
        log.info("Rooms import job {}: extracted {} input(s)", jobId, inputs.size());

        // --- 2. DETECTING_ROOMS -----------------------------------------------------------
        enter(jobId, RoomsImportStatus.DETECTING_ROOMS);
        List<RoomTypeResolver.RoomTypeOption> catalogue = loader.roomTypes();
        DetectResponse detected = detect(ctx, inputs, catalogue);
        Map<String, List<Warning>> roomWarnings = new LinkedHashMap<>();
        List<DetectedRoom> rooms = dedupe(detected.rooms(), roomWarnings);
        CountRule.evaluate(detected.statedRoomCount(), rooms.size()).ifPresent(projectWarnings::add);
        if (rooms.isEmpty()) {
            projectWarnings.add(new Warning(WarningCodes.NO_ROOMS_DETECTED, Severity.WARNING, null, null,
                    Map.of(), null));
        }
        log.info("Rooms import job {}: detected {} room(s)", jobId, rooms.size());

        // --- 3. CLASSIFYING ---------------------------------------------------------------
        enter(jobId, RoomsImportStatus.CLASSIFYING);
        RoomTypeResolver resolver = new RoomTypeResolver(catalogue);
        Map<String, Resolution> resolutions = new LinkedHashMap<>();
        for (DetectedRoom r : rooms) {
            resolutions.put(r.tempId(), resolver.resolve(r.roomTypeId(), r.typeHint(), r.label(), r.tempId()));
        }

        // --- 4. MEASURING -----------------------------------------------------------------
        enter(jobId, RoomsImportStatus.MEASURING);
        List<DraftRoom> drafts = measureAll(ctx, inputs, rooms, resolutions, roomWarnings);
        log.info("Rooms import job {}: measured {} room(s)", jobId, drafts.size());

        // --- 5. VALIDATING ----------------------------------------------------------------
        enter(jobId, RoomsImportStatus.VALIDATING);
        List<ExistingRoomRef> existing = loader.existingRooms(ctx.projectId());
        List<DraftRoom> validated = new ArrayList<>();
        for (DraftRoom d : drafts) {
            DraftRoom v = validator.validated(d, existing);
            List<Warning> all = new ArrayList<>(v.warnings());
            all.addAll(DimensionMissingRule.evaluate(v.tempId(), v.room(), v.calculated(), v.warnings()));
            validated.add(new DraftRoom(v.tempId(), v.confidence(), v.source(), v.room(), v.calculated(),
                    v.stated(), v.media(), List.copyOf(all)));
        }
        ImportResult result = new ImportResult(List.copyOf(validated), List.copyOf(projectWarnings),
                inputs.stream().map(ExtractedInput::toInfo).toList(),
                new ModelInfo(properties.llm().model()));
        log.info("Rooms import job {}: validated, {} room(s), {} ms", jobId, validated.size(),
                System.currentTimeMillis() - t0);
        return result;
    }

    /** Cancellation check, then the committed stage transition. */
    private void enter(Long jobId, RoomsImportStatus stage) {
        cancellation.assertActive(jobId);
        stages.markStage(jobId, stage);
    }

    // ------------------------------------------------------------------------------------------
    // Stage 1
    // ------------------------------------------------------------------------------------------

    private List<ExtractedInput> extract(JobContext ctx, List<Warning> projectWarnings) {
        List<ExtractedInput> out = new ArrayList<>();
        for (StoredInput in : ctx.inputs()) {
            ExtractedInput extracted;
            try {
                byte[] bytes = storage.read(in.objectKey());
                extracted = bytes == null || bytes.length == 0
                        ? ExtractedInput.unreadable(in.fileName(), InputKind.UNSUPPORTED)
                        : extractors.extract(in.fileName(), in.contentType(), bytes);
            } catch (PdfPageLimitExceededException e) {
                throw new RoomsImportFailureException(RoomsImportErrors.FAILED_PDF_PAGES, e);
            } catch (RuntimeException e) {
                extracted = ExtractedInput.unreadable(in.fileName(), InputKind.UNSUPPORTED);
            }
            out.add(extracted);
            if (extracted.warning() != null) {
                projectWarnings.add(extracted.warning());
            }
        }
        boolean anyReadable = out.stream().anyMatch(ExtractedInput::readable);
        boolean hasPrompt = ctx.prompt() != null && !ctx.prompt().isBlank();
        if (!out.isEmpty() && !anyReadable && !hasPrompt) {
            throw new RoomsImportFailureException(RoomsImportErrors.FAILED_NO_READABLE_INPUT);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Stage 2
    // ------------------------------------------------------------------------------------------

    private DetectResponse detect(JobContext ctx, List<ExtractedInput> inputs,
                                  List<RoomTypeResolver.RoomTypeOption> catalogue) {
        List<DetectRequest.TextSource> texts = new ArrayList<>();
        List<PageImage> images = new ArrayList<>();
        for (ExtractedInput in : inputs) {
            if (in.text() != null && !in.text().isBlank()) {
                texts.add(new DetectRequest.TextSource(in.fileName(), in.text()));
            }
            images.addAll(in.pages());
        }
        List<DetectRequest.RoomTypeRef> types = catalogue.stream()
                .map(o -> new DetectRequest.RoomTypeRef(o.id(), o.code(), o.nameRu(), o.namePl())).toList();

        List<DetectedRoom> merged = new ArrayList<>();
        Integer stated = null;
        int batches = Math.max(1, (images.size() + DETECT_IMAGE_BATCH - 1) / DETECT_IMAGE_BATCH);
        for (int b = 0; b < batches; b++) {
            if (b > 0) {
                cancellation.assertActive(ctx.jobId());
            }
            List<PageImage> slice = images.subList(Math.min(images.size(), b * DETECT_IMAGE_BATCH),
                    Math.min(images.size(), (b + 1) * DETECT_IMAGE_BATCH));
            DetectRequest req = new DetectRequest(ctx.prompt(), b == 0 ? texts : List.of(), slice, types);
            DetectResponse resp;
            try {
                resp = vision.detectRooms(req);
            } catch (RoomsVisionException e) {
                throw new RoomsImportFailureException(RoomsImportErrors.FAILED_LLM_UNAVAILABLE, e);
            }
            if (stated == null) {
                stated = resp.statedRoomCount();
            }
            for (DetectedRoom r : resp.rooms()) {
                merged.add(withUniqueTempId(r, merged, b));
            }
        }
        return new DetectResponse(merged, stated, null);
    }

    private static DetectedRoom withUniqueTempId(DetectedRoom r, List<DetectedRoom> existing, int batch) {
        String id = r.tempId() == null || r.tempId().isBlank() ? "r" + (existing.size() + 1) : r.tempId();
        String candidate = id;
        int n = batch + 1;
        while (hasId(existing, candidate)) {
            candidate = id + "-b" + n++;
        }
        return new DetectedRoom(candidate, r.label(), r.typeHint(), r.roomTypeId(), r.sourceRef(), r.bbox(),
                r.confidence());
    }

    private static boolean hasId(List<DetectedRoom> rooms, String id) {
        return rooms.stream().anyMatch(r -> id.equals(r.tempId()));
    }

    /**
     * Merges rooms with the same normalised label described by different sources (Requirement 3.3):
     * the more confident one is kept and {@code DUPLICATE_ROOM_SUSPECTED} lists the merged sources.
     * Two rooms with the same label in the <i>same</i> source are distinct rooms and both stay.
     */
    static List<DetectedRoom> dedupe(List<DetectedRoom> rooms, Map<String, List<Warning>> warningsOut) {
        List<DetectedRoom> kept = new ArrayList<>();
        Map<String, List<String>> mergedSources = new LinkedHashMap<>();
        for (DetectedRoom r : rooms) {
            String label = normalize(r.label());
            int idx = -1;
            if (!label.isEmpty()) {
                for (int i = 0; i < kept.size(); i++) {
                    DetectedRoom k = kept.get(i);
                    if (label.equals(normalize(k.label())) && !sameSource(k, r)) {
                        idx = i;
                        break;
                    }
                }
            }
            if (idx < 0) {
                kept.add(r);
                continue;
            }
            DetectedRoom k = kept.get(idx);
            List<String> sources = mergedSources.computeIfAbsent(k.tempId(), t -> new ArrayList<>(List.of(sourceLabel(k))));
            sources.add(sourceLabel(r));
            if (conf(r) > conf(k)) {
                // the better-evidenced duplicate becomes the kept room; keep the earlier tempId stable
                DetectedRoom replaced = new DetectedRoom(k.tempId(), r.label(), r.typeHint(), r.roomTypeId(),
                        r.sourceRef(), r.bbox(), r.confidence());
                kept.set(idx, replaced);
            }
        }
        for (Map.Entry<String, List<String>> e : mergedSources.entrySet()) {
            DetectedRoom k = kept.stream().filter(x -> x.tempId().equals(e.getKey())).findFirst().orElseThrow();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", k.label());
            params.put("sources", List.copyOf(e.getValue()));
            warningsOut.computeIfAbsent(k.tempId(), t -> new ArrayList<>()).add(new Warning(
                    WarningCodes.DUPLICATE_ROOM_SUSPECTED, Severity.WARNING, k.tempId(), "label", params, null));
        }
        return kept;
    }

    private static boolean sameSource(DetectedRoom a, DetectedRoom b) {
        return sourceLabel(a).equals(sourceLabel(b));
    }

    private static String sourceLabel(DetectedRoom r) {
        DetectResponse.SourceRef s = r.sourceRef();
        if (s == null || s.fileName() == null) {
            return "prompt";
        }
        return s.page() == null ? s.fileName() : s.fileName() + "#" + s.page();
    }

    private static double conf(DetectedRoom r) {
        return r.confidence() == null ? 0.0 : r.confidence();
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replace('ł', 'l').replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------------------------------
    // Stage 4
    // ------------------------------------------------------------------------------------------

    private List<DraftRoom> measureAll(JobContext ctx, List<ExtractedInput> inputs, List<DetectedRoom> rooms,
                                       Map<String, Resolution> resolutions,
                                       Map<String, List<Warning>> roomWarnings) {
        if (rooms.isEmpty()) {
            return List.of();
        }
        Map<String, DraftMedia> mediaCache = new ConcurrentHashMap<>();
        int threads = Math.max(1, Math.min(properties.maxConcurrency(), rooms.size()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "rooms-import-measure-" + ctx.jobId());
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<DraftRoom>> futures = new ArrayList<>();
            for (DetectedRoom room : rooms) {
                futures.add(pool.submit(() -> measureOne(ctx, inputs, room, resolutions.get(room.tempId()),
                        roomWarnings.getOrDefault(room.tempId(), List.of()), mediaCache)));
            }
            List<DraftRoom> out = new ArrayList<>();
            RuntimeException abort = null;
            for (Future<DraftRoom> f : futures) {
                try {
                    out.add(f.get());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (abort == null && cause instanceof RuntimeException re) {
                        abort = re; // JobCancelledException or an unexpected failure
                    } else if (abort == null) {
                        abort = new RoomsImportFailureException(RoomsImportErrors.FAILED_INTERNAL, cause);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new JobCancelledException(ctx.jobId());
                }
            }
            if (abort != null) {
                throw abort;
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private DraftRoom measureOne(JobContext ctx, List<ExtractedInput> inputs, DetectedRoom detected,
                                 Resolution resolution, List<Warning> carried,
                                 Map<String, DraftMedia> mediaCache) {
        Long jobId = ctx.jobId();
        String tempId = detected.tempId();
        List<Warning> warnings = new ArrayList<>(carried);
        resolution.warning().ifPresent(warnings::add);

        PageImage page = findPage(inputs, detected.sourceRef());
        SourceRef source = detected.sourceRef() == null ? new SourceRef(null, null)
                : new SourceRef(detected.sourceRef().fileName(), detected.sourceRef().page());
        double[] bbox = bboxOf(detected.bbox());

        // Crop locally first: the crop (when available) is what the model measures.
        byte[] cropPng = null;
        Optional<Warning> cropWarning = Optional.empty();
        if (page != null && bbox != null) {
            if (page.croppable()) {
                RoomCropper.CropResult cr = cropper.crop(page.bytes(), bbox, tempId);
                cropPng = cr.png();
                cropWarning = cr.warning();
            } else {
                cropWarning = Optional.of(new Warning(WarningCodes.CROP_UNAVAILABLE, Severity.INFO, tempId, null,
                        Map.of("reason", "not_croppable"), null));
            }
        }
        PageImage forModel = cropPng != null ? asImage(cropPng, page) : page;

        // Cancellation gate: no new AI call after a cancel (Requirement 24.3).
        cancellation.assertActive(jobId);
        MeasureResponse measured = null;
        try {
            measured = vision.measureRoom(new MeasureRequest(tempId, detected.label(), detected.typeHint(),
                    forModel, textFor(inputs, source), ctx.prompt(), source.fileName()));
        } catch (RoomsVisionException e) {
            warnings.add(new Warning(WarningCodes.ROOM_ANALYSIS_FAILED, Severity.WARNING, tempId, null,
                    Map.of("reason", e.getReason().name()), null));
        }
        stages.heartbeat(jobId);

        // Draft media (after the call, so a cancelled job stores nothing here).
        List<DraftMedia> media = new ArrayList<>();
        if (page != null && bbox != null) {
            cancellation.assertActive(jobId);
            DraftMedia m = null;
            try {
                m = cropPng != null
                        ? storeMedia(jobId, cropPng, "image/png")
                        : mediaCache.computeIfAbsent(sourceKey(source),
                                k -> storeMedia(jobId, page.bytes(), page.mimeType()));
            } catch (RuntimeException e) {
                cropWarning = Optional.of(new Warning(WarningCodes.CROP_UNAVAILABLE, Severity.INFO, tempId, null,
                        Map.of("reason", "store_failed"), null));
            }
            if (m != null) {
                media.add(m);
            }
            if (cropPng == null && cropWarning.isEmpty()) {
                cropWarning = Optional.of(new Warning(WarningCodes.CROP_UNAVAILABLE, Severity.INFO, tempId, null,
                        Map.of("reason", "unavailable"), null));
            }
            cropWarning.ifPresent(warnings::add);
        }

        return buildDraft(ctx, detected, resolution, measured, forModel != null, source, media, warnings);
    }

    private DraftRoom buildDraft(JobContext ctx, DetectedRoom detected, Resolution resolution,
                                 MeasureResponse m, boolean hadImage, SourceRef source, List<DraftMedia> media,
                                 List<Warning> warnings) {
        String tempId = detected.tempId();
        RoomGeometry geometry = null;
        String geometryUnit = m == null ? null : m.geometryUnit();

        // Stage A (design B5a): UnitConverter — every value to its field's target unit.
        Map<String, Quantity> quantities = new LinkedHashMap<>();
        Map<String, Double> statedConverted = new LinkedHashMap<>();
        if (m != null) {
            m.stated().forEach((k, v) -> {
                Double c = convertScalar(v, dimensionOf(k, v), k, tempId, warnings);
                statedConverted.put(k, c);
                if (PlausibilityInput.CEILING_HEIGHT.equals(k) || PlausibilityInput.NARROW_PLACE.equals(k)) {
                    return; // taken from the dedicated top-level values below
                }
                if (AREA_FIELDS.contains(k) || LENGTH_FIELDS.contains(k) || COUNT_FIELDS.contains(k)) {
                    quantities.put(k, new Quantity(c, v == null ? null : v.unit()));
                }
            });
            Measured ceilingM = m.ceilingHeight();
            Measured statedCeiling = m.stated().get(PlausibilityInput.CEILING_HEIGHT);
            Measured ceilingSource = Measured.valueOf(ceilingM) != null ? ceilingM : statedCeiling;
            if (ceilingSource != null) {
                quantities.put(PlausibilityInput.CEILING_HEIGHT, new Quantity(
                        statedConverted.containsKey(PlausibilityInput.CEILING_HEIGHT) && ceilingSource == statedCeiling
                                ? statedConverted.get(PlausibilityInput.CEILING_HEIGHT)
                                : convertScalar(ceilingSource, UnitConverter.Dimension.LENGTH,
                                        PlausibilityInput.CEILING_HEIGHT, tempId, warnings),
                        ceilingSource.unit()));
            }
            if (Measured.valueOf(m.narrowPlace()) != null) {
                quantities.put(PlausibilityInput.NARROW_PLACE, new Quantity(
                        convertScalar(m.narrowPlace(), UnitConverter.Dimension.LENGTH,
                                PlausibilityInput.NARROW_PLACE, tempId, warnings), m.narrowPlace().unit()));
            }
        }
        Map<String, Quantity> beforeCorrection = new LinkedHashMap<>(quantities);

        if (m != null) {
            boolean scaleKnown = !Boolean.FALSE.equals(m.scaleEstablished());
            if (!m.vertices().isEmpty()) {
                if (!scaleKnown && hadImage) {
                    warnings.add(new Warning(WarningCodes.SCALE_UNKNOWN, Severity.WARNING, tempId, "geometry",
                            Map.of(), null));
                } else {
                    RoomGeometry metric = toMetricGeometry(m, tempId, warnings);
                    if (metric != null) {
                        // GeometryNormalizer on already-metric data (unit "m")
                        GeometryNormalizer.Result n = normalizer.normalize(metric, "m", tempId);
                        warnings.addAll(n.warnings());
                        geometry = n.geometry();
                        if (geometry == null) {
                            warnings.add(new Warning(WarningCodes.GEOMETRY_INVALID, Severity.WARNING, tempId,
                                    "geometry", Map.of("reason", "UNUSABLE_POLYGON"), null));
                        }
                    }
                }
            } else if (!scaleKnown && hadImage) {
                warnings.add(new Warning(WarningCodes.SCALE_UNKNOWN, Severity.WARNING, tempId, "geometry",
                        Map.of(), null));
            }
        }
        if (geometry == null) {
            Optional<double[]> dims = normalizer.parseRectangleDimensions(detected.label());
            if (dims.isPresent()) {
                GeometryNormalizer.Result n = normalizer.synthesizeRectangle(dims.get()[0], dims.get()[1], tempId);
                if (n.geometry() != null) {
                    geometry = n.geometry();
                    warnings.addAll(n.warnings());
                }
            }
        }

        // Stage B: PlausibilityCorrector on the converted values and the metric geometry.
        PlausibilityResult pr = corrector.correct(new PlausibilityInput(tempId, quantities, geometry, geometryUnit));
        warnings.addAll(pr.warnings());
        PlausibilityInput fixed = pr.corrected();
        geometry = fixed.geometry();
        if (geometry != null) {
            // The corrector clears an implausible opening size to 0.0: let the normalizer drop it
            // (OPENING_DROPPED) now that all sizes are final. Idempotent for untouched geometry.
            GeometryNormalizer.Result n = normalizer.normalize(geometry, "m", tempId);
            if (n.geometry() != null) {
                geometry = n.geometry();
                warnings.addAll(n.warnings());
            }
        }

        // The draft's `stated` holds converted (target-unit) numbers, with corrections applied where the
        // corrected field is the very value that was stated.
        Map<String, Object> stated = new LinkedHashMap<>();
        statedConverted.forEach((k, v) -> {
            Quantity before = beforeCorrection.get(k);
            stated.put(k, before != null && Objects.equals(before.value(), v) ? fixed.value(k) : v);
        });

        boolean scalar = geometry == null;
        RoomCreateRequest room = new RoomCreateRequest(
                ctx.projectId(), resolution.roomTypeId(), detected.label(),
                decimal(fixed.value(PlausibilityInput.CEILING_HEIGHT)),
                m == null ? null : m.internalCorners(), geometry,
                scalar ? decimal(fixed.value(PlausibilityInput.FLOOR_AREA)) : null,
                scalar ? decimal(fixed.value(PlausibilityInput.WALL_AREA)) : null,
                scalar ? decimal(fixed.value(PlausibilityInput.PERIMETER)) : null,
                scalar ? decimal(fixed.value(PlausibilityInput.DOOR_AREA)) : null,
                scalar ? decimal(fixed.value(PlausibilityInput.WINDOW_AREA)) : null,
                scalar ? integer(fixed.value(PlausibilityInput.DOOR_COUNT)) : null,
                scalar ? integer(fixed.value(PlausibilityInput.WINDOW_COUNT)) : null,
                scalar ? decimal(fixed.value(PlausibilityInput.DOOR_HEIGHT)) : null,
                scalar ? decimal(fixed.value(PlausibilityInput.DOOR_WIDTH)) : null,
                scalar ? decimal(fixed.value(PlausibilityInput.WINDOW_HEIGHT)) : null,
                scalar ? decimal(fixed.value(PlausibilityInput.WINDOW_WIDTH)) : null,
                decimal(fixed.value(PlausibilityInput.NARROW_PLACE)), null, null, null);
        CalculatedMetrics calculated = validator.calculate(room);

        Double dc = detected.confidence();
        Double mc = m == null ? null : m.confidence();
        double confidence = dc != null && mc != null ? Math.min(dc, mc) : dc != null ? dc : mc != null ? mc : 0.5;
        return new DraftRoom(tempId, clamp01(confidence), source, room, calculated, stated, List.copyOf(media),
                List.copyOf(warnings));
    }

    /**
     * Builds the geometry in metres: vertices by {@code geometryUnit} (null ⇒ metres), every wall gap and
     * opening size/offset by its own unit. A unit that fails conversion makes the value unreadable (see
     * {@link #conversionFailed}); an unreadable geometry unit yields {@code null} (no outline).
     */
    private RoomGeometry toMetricGeometry(MeasureResponse m, String tempId, List<Warning> warnings) {
        double factor = 1.0;
        if (m.geometryUnit() != null) {
            Optional<Double> f = converter.lengthFactor(m.geometryUnit());
            if (f.isEmpty()) {
                warnings.add(conversionFailed(tempId, "geometry", null, m.geometryUnit(),
                        UnitConverter.UNKNOWN_UNIT));
                return null;
            }
            factor = f.get();
        }
        RoomGeometry g = new RoomGeometry();
        List<RoomGeometry.Vertex> vertices = new ArrayList<>();
        for (MeasureResponse.Vertex v : m.vertices()) {
            RoomGeometry.Vertex out = new RoomGeometry.Vertex();
            out.setX(v == null || v.x() == null ? Double.NaN : round(v.x() * factor));
            out.setY(v == null || v.y() == null ? Double.NaN : round(v.y() * factor));
            vertices.add(out);
        }
        g.setVertices(vertices);
        List<RoomGeometry.Wall> walls = new ArrayList<>();
        for (int i = 0; i < m.walls().size(); i++) {
            MeasureResponse.Wall w = m.walls().get(i);
            String base = "geometry.walls[" + i + "]";
            RoomGeometry.Wall out = new RoomGeometry.Wall();
            out.setWallGap(w == null ? null : convertScalar(w.wallGap(), UnitConverter.Dimension.LENGTH,
                    base + ".wallGap", tempId, warnings));
            out.setFinishGap(w == null ? null : convertScalar(w.finishGap(), UnitConverter.Dimension.LENGTH,
                    base + ".finishGap", tempId, warnings));
            List<RoomGeometry.Opening> openings = new ArrayList<>();
            if (w != null && w.openings() != null) {
                int j = 0;
                for (MeasureResponse.Opening o : w.openings()) {
                    String ob = base + ".openings[" + j++ + "]";
                    RoomGeometry.Opening oo = new RoomGeometry.Opening();
                    oo.setType(openingType(o == null ? null : o.type()));
                    oo.setCount(o == null || o.count() == null ? 1 : o.count());
                    Double height = o == null ? null : convertScalar(o.height(), UnitConverter.Dimension.LENGTH,
                            ob + ".height", tempId, warnings);
                    Double width = o == null ? null : convertScalar(o.width(), UnitConverter.Dimension.LENGTH,
                            ob + ".width", tempId, warnings);
                    oo.setHeight(height == null ? 0 : height);
                    oo.setWidth(width == null ? 0 : width);
                    oo.setOffset(o == null ? null : convertScalar(o.offset(), UnitConverter.Dimension.LENGTH,
                            ob + ".offset", tempId, warnings));
                    openings.add(oo);
                }
            }
            out.setOpenings(openings);
            walls.add(out);
        }
        g.setWalls(walls);
        return g;
    }

    /** Target dimension of a stated field; {@code null} ⇒ plain number (counts, unknown keys without a unit). */
    private UnitConverter.Dimension dimensionOf(String key, Measured v) {
        if (AREA_FIELDS.contains(key)) {
            return UnitConverter.Dimension.AREA;
        }
        if (LENGTH_FIELDS.contains(key)) {
            return UnitConverter.Dimension.LENGTH;
        }
        if (COUNT_FIELDS.contains(key) || v == null || v.unit() == null) {
            return null;
        }
        if (converter.lengthFactor(v.unit()).isPresent()) {
            return UnitConverter.Dimension.LENGTH;
        }
        return converter.areaFactor(v.unit()).isPresent() ? UnitConverter.Dimension.AREA : null;
    }

    /**
     * Converts a measured number to its field's target unit (rounded to 9 decimals to drop float noise).
     * {@code null}/absent ⇒ {@code null}. A conversion failure (unknown unit, length unit on an area field
     * or the reverse, non-finite value) makes the value unreadable: {@code null} plus a
     * {@code DIMENSION_OUT_OF_RANGE} warning carrying the original value/unit and a {@code reason}; the raw
     * number is never used.
     */
    private Double convertScalar(Measured v, UnitConverter.Dimension target, String field, String tempId,
                                 List<Warning> warnings) {
        if (v == null || v.value() == null) {
            return null;
        }
        if (target == null) {
            return Double.isFinite(v.value()) ? v.value() : null;
        }
        UnitConverter.Result r = converter.convert(v.value(), v.unit(), target);
        if (r.ok()) {
            return round(r.value());
        }
        warnings.add(conversionFailed(tempId, field, v.value(), v.unit(), r.error()));
        return null;
    }

    private static Warning conversionFailed(String tempId, String field, Double original, String unit,
                                            String reason) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("field", field);
        params.put("original", original);
        params.put("originalUnit", unit);
        params.put("reason", reason);
        return new Warning(WarningCodes.DIMENSION_OUT_OF_RANGE, Severity.WARNING, tempId, field, params, null);
    }

    private static double round(double v) {
        return Double.isFinite(v) ? Math.round(v * 1e9) / 1e9 : v;
    }

    private static OpeningType openingType(String s) {
        if (s == null) {
            return null;
        }
        try {
            return OpeningType.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private DraftMedia storeMedia(Long jobId, byte[] bytes, String contentType) {
        String key = storage.storeMedia(jobId, bytes, contentType);
        String mediaId = RoomsImportJobMapper.mediaIdOf(key);
        return new DraftMedia(mediaId, "SOURCE_CROP", contentType,
                "/api/rooms/autoimport/" + jobId + "/media/" + mediaId);
    }

    private static String sourceKey(SourceRef s) {
        return s.fileName() + "#" + s.page();
    }

    /** The page the room was found on; with a single page overall, a missing / unknown ref falls back to it. */
    private static PageImage findPage(List<ExtractedInput> inputs, DetectResponse.SourceRef ref) {
        List<PageImage> all = new ArrayList<>();
        for (ExtractedInput in : inputs) {
            all.addAll(in.pages());
        }
        if (ref != null && ref.fileName() != null) {
            PageImage firstOfFile = null;
            for (PageImage p : all) {
                if (ref.fileName().equals(p.fileName())) {
                    if (ref.page() == null || Objects.equals(ref.page(), p.page())) {
                        return p;
                    }
                    if (firstOfFile == null) {
                        firstOfFile = p;
                    }
                }
            }
            if (firstOfFile != null && ref.page() == null) {
                return firstOfFile;
            }
        }
        return all.size() == 1 ? all.get(0) : null;
    }

    private static String textFor(List<ExtractedInput> inputs, SourceRef source) {
        String own = null;
        StringBuilder joined = new StringBuilder();
        for (ExtractedInput in : inputs) {
            if (in.text() == null || in.text().isBlank()) {
                continue;
            }
            if (source.fileName() != null && source.fileName().equals(in.fileName())) {
                own = in.text();
            }
            joined.append("== ").append(in.fileName()).append(" ==\n").append(in.text()).append('\n');
        }
        String text = own != null ? own : joined.length() == 0 ? null : joined.toString();
        return text != null && text.length() > MAX_MEASURE_TEXT_CHARS ? text.substring(0, MAX_MEASURE_TEXT_CHARS) : text;
    }

    private static PageImage asImage(byte[] png, PageImage origin) {
        int w = 0;
        int h = 0;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            if (img != null) {
                w = img.getWidth();
                h = img.getHeight();
            }
        } catch (Exception ignored) {
            // dimensions are informational only
        }
        return new PageImage(png, "image/png", w, h, true, origin.fileName(), origin.page());
    }

    private static double[] bboxOf(List<Double> bbox) {
        if (bbox == null || bbox.size() != 4 || bbox.stream().anyMatch(Objects::isNull)) {
            return null;
        }
        return new double[] {bbox.get(0), bbox.get(1), bbox.get(2), bbox.get(3)};
    }

    private static BigDecimal decimal(Object o) {
        if (o instanceof Number n && Double.isFinite(n.doubleValue())) {
            return o instanceof BigDecimal bd ? bd : BigDecimal.valueOf(n.doubleValue());
        }
        return null;
    }

    private static Integer integer(Object o) {
        if (o instanceof Number n && Double.isFinite(n.doubleValue())) {
            return (int) Math.round(n.doubleValue());
        }
        return null;
    }

    private static double clamp01(double v) {
        return Double.isFinite(v) ? Math.max(0, Math.min(1, v)) : 0;
    }
}
