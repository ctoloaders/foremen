package com.foremen.service.roomsimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.foremen.config.roomsimport.RoomsImportProperties;
import com.foremen.dao.RoomsImportJobDao;
import com.foremen.dao.model.RoomsImportStatus;
import com.foremen.service.RoomCalculationService;
import com.foremen.service.roomsimport.RoomsImportContextLoader.JobContext;
import com.foremen.service.roomsimport.ai.DetectRequest;
import com.foremen.service.roomsimport.ai.DetectResponse;
import com.foremen.service.roomsimport.ai.MeasureRequest;
import com.foremen.service.roomsimport.ai.MeasureResponse;
import com.foremen.service.roomsimport.ai.RoomsVisionClient;
import com.foremen.service.roomsimport.ai.RoomsVisionException;
import com.foremen.service.roomsimport.ai.StubRoomsVisionClient;
import com.foremen.service.roomsimport.classify.RoomTypeResolver.RoomTypeOption;
import com.foremen.service.roomsimport.crop.RoomCropper;
import com.foremen.service.roomsimport.extract.RoomsInputExtractors;
import com.foremen.service.roomsimport.geometry.GeometryNormalizer;
import com.foremen.service.roomsimport.model.DraftRoom;
import com.foremen.service.roomsimport.model.ImportResult;
import com.foremen.service.roomsimport.model.Warning;
import com.foremen.service.roomsimport.model.WarningCodes;
import com.foremen.service.roomsimport.validate.DraftValidator;
import com.foremen.service.roomsimport.validate.ExistingRoomRef;

/**
 * Unit tests of {@link RoomsImportPipeline} with the deterministic stub client.
 * Validates: Requirements 3.1-3.5, 4.4, 5.1-5.6, 8.1-8.4, 9.1, 9.2, 13.3, 16.2, 21.3, 24.3.
 */
class RoomsImportPipelineTest {

    private static final Long JOB = 7L;
    private static final Long PROJECT = 11L;

    private final RoomsImportContextLoader loader = mock(RoomsImportContextLoader.class);
    private final RoomsImportStageMarker stages = mock(RoomsImportStageMarker.class);
    private final RoomsImportStorage storage = mock(RoomsImportStorage.class);
    private final RoomsImportJobDao jobDao = mock(RoomsImportJobDao.class);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private RoomsImportCancellation cancellation;
    private CountingClient client;

    @BeforeEach
    void setUp() {
        when(jobDao.existsById(anyLong())).thenAnswer(inv -> !cancelled.get());
        cancellation = new RoomsImportCancellation(jobDao, Duration.ZERO);
        client = new CountingClient(new StubRoomsVisionClient());
        when(loader.roomTypes()).thenReturn(List.of(
                new RoomTypeOption(1L, "bedroom", "Спальня", "Sypialnia"),
                new RoomTypeOption(2L, "bathroom", "Ванная", "Łazienka"),
                new RoomTypeOption(3L, "kitchen", "Кухня", "Kuchnia"),
                new RoomTypeOption(4L, "living room", "Гостиная", "Salon")));
        when(loader.existingRooms(PROJECT)).thenReturn(List.of());
        when(storage.storeMedia(anyLong(), any(), any())).thenReturn("rooms-import/7/media/abc.png");
    }

    private RoomsImportPipeline pipeline(RoomsVisionClient vision, int maxConcurrency) {
        RoomsImportProperties props = new RoomsImportProperties(true, null, null, null, maxConcurrency, null);
        return new RoomsImportPipeline(loader, stages, cancellation, storage, new RoomsInputExtractors(props),
                vision, new GeometryNormalizer(),
                new DraftValidator(new RoomCalculationService(), props.validation()), new RoomCropper(), props);
    }

    private void job(String prompt, StoredInput... inputs) {
        when(loader.load(JOB)).thenReturn(java.util.Optional.of(
                new JobContext(JOB, PROJECT, 3L, prompt, List.of(inputs))));
    }

    private static DraftRoom room(ImportResult r, String label) {
        return r.rooms().stream().filter(d -> label.equals(d.room().label())).findFirst().orElseThrow();
    }

    private static boolean has(List<Warning> ws, String code) {
        return ws.stream().anyMatch(w -> code.equals(w.code()));
    }

    // ---------------------------------------------------------------------------------------

    @Test
    void textPrompt_threeRooms_runsAllStagesInOrderAndReturnsCalculatedDrafts() {
        job("Mieszkanie STUB:THREE_ROOMS");

        ImportResult result = pipeline(client, 2).run(JOB);

        assertThat(result.rooms()).extracting(d -> d.room().label())
                .containsExactlyInAnyOrder("Sypialnia", "Łazienka", "Kuchnia");
        assertThat(result.modelInfo()).isNotNull();
        DraftRoom bedroom = room(result, "Sypialnia");
        assertThat(bedroom.room().projectId()).isEqualTo(PROJECT);
        assertThat(bedroom.room().roomTypeId()).isEqualTo(1L);
        assertThat(bedroom.room().geometry()).isNotNull();
        assertThat(bedroom.calculated().floorArea()).isNotNull();
        assertThat(bedroom.calculated().floorArea().doubleValue()).isEqualTo(13.12);
        // the validator is advisory: nothing was rejected or mutated
        assertThat(has(bedroom.warnings(), WarningCodes.AREA_MISMATCH)).isFalse();

        InOrder order = inOrder(stages);
        order.verify(stages).markStage(JOB, RoomsImportStatus.EXTRACTING);
        order.verify(stages).markStage(JOB, RoomsImportStatus.DETECTING_ROOMS);
        order.verify(stages).markStage(JOB, RoomsImportStatus.CLASSIFYING);
        order.verify(stages).markStage(JOB, RoomsImportStatus.MEASURING);
        order.verify(stages).markStage(JOB, RoomsImportStatus.VALIDATING);
        assertThat(client.measureCalls.get()).isEqualTo(3);
    }

    @Test
    void statedRoomCountDiffering_addsRoomCountMismatch() {
        job("STUB:ROOM_COUNT_MISMATCH");

        ImportResult result = pipeline(client, 2).run(JOB);

        Warning w = result.warnings().stream().filter(x -> x.code().equals(WarningCodes.ROOM_COUNT_MISMATCH))
                .findFirst().orElseThrow();
        assertThat(w.params()).containsEntry("stated", 4).containsEntry("detected", 3);
    }

    @Test
    void unknownType_yieldsNullTypeAndUnresolvedWarning() {
        job("STUB:UNKNOWN_TYPE");

        ImportResult result = pipeline(client, 1).run(JOB);

        DraftRoom d = result.rooms().get(0);
        assertThat(d.room().roomTypeId()).isNull();
        assertThat(has(d.warnings(), WarningCodes.ROOM_TYPE_UNRESOLVED)).isTrue();
    }

    @Test
    void areaMismatch_isReportedNotApplied() {
        job("STUB:AREA_MISMATCH");

        DraftRoom d = pipeline(client, 1).run(JOB).rooms().get(0);

        assertThat(has(d.warnings(), WarningCodes.AREA_MISMATCH)).isTrue();
        assertThat(d.calculated().floorArea().doubleValue()).isEqualTo(20.0);
    }

    @Test
    void textOnlyScalars_keepScalarsWithoutGeometryAndReportMissingDimensions() {
        job("STUB:TEXT_ONLY_SCALARS");

        DraftRoom d = pipeline(client, 1).run(JOB).rooms().get(0);

        assertThat(d.room().geometry()).isNull();
        assertThat(d.room().floorArea().doubleValue()).isEqualTo(12.0);
        assertThat(d.room().ceilingHeight().doubleValue()).isEqualTo(2.6);
        // text-only source: no image, so the missing scale is not a finding
        assertThat(has(d.warnings(), WarningCodes.SCALE_UNKNOWN)).isFalse();
        assertThat(d.warnings()).filteredOn(w -> w.code().equals(WarningCodes.DIMENSION_MISSING))
                .extracting(Warning::field).contains("wallArea", "perimeter", "doorCount");
    }

    @Test
    void existingRoomWithSameLabelAndType_addsDuplicateWarning_andNothingIsRemoved() {
        job("STUB:THREE_ROOMS");
        when(loader.existingRooms(PROJECT)).thenReturn(List.of(new ExistingRoomRef(99L, "sypialnia", 1L)));

        ImportResult result = pipeline(client, 2).run(JOB);

        assertThat(result.rooms()).hasSize(3);
        Warning w = room(result, "Sypialnia").warnings().stream()
                .filter(x -> x.code().equals(WarningCodes.POSSIBLE_DUPLICATE_OF_EXISTING)).findFirst().orElseThrow();
        assertThat(w.params()).containsEntry("existingRoomId", 99L);
    }

    @Test
    void failureOfOneRoom_isIsolated() {
        job("STUB:THREE_ROOMS");
        RoomsVisionClient failing = new DelegatingClient(client) {
            @Override
            public MeasureResponse measureRoom(MeasureRequest request) {
                if ("Łazienka".equals(request.label())) {
                    throw new RoomsVisionException(RoomsVisionException.Reason.INVALID_RESPONSE, "bad");
                }
                return super.measureRoom(request);
            }
        };

        ImportResult result = pipeline(failing, 2).run(JOB);

        assertThat(result.rooms()).hasSize(3);
        DraftRoom bathroom = room(result, "Łazienka");
        assertThat(has(bathroom.warnings(), WarningCodes.ROOM_ANALYSIS_FAILED)).isTrue();
        assertThat(bathroom.room().geometry()).isNull();
        assertThat(room(result, "Sypialnia").room().geometry()).isNotNull();
    }

    @Test
    void detectionOutage_failsTheJobWithLlmUnavailable() {
        job("STUB:LLM_DOWN");

        assertThatThrownBy(() -> pipeline(client, 1).run(JOB))
                .isInstanceOfSatisfying(RoomsImportFailureException.class,
                        e -> assertThat(e.getReason()).isEqualTo(RoomsImportErrors.FAILED_LLM_UNAVAILABLE));
        assertThat(client.measureCalls.get()).isZero();
    }

    @Test
    void noRooms_completesWithNoRoomsDetected() {
        job("anything");
        RoomsVisionClient empty = new DelegatingClient(client) {
            @Override
            public DetectResponse detectRooms(DetectRequest request) {
                return new DetectResponse(List.of(), null, "");
            }
        };

        ImportResult result = pipeline(empty, 1).run(JOB);

        assertThat(result.rooms()).isEmpty();
        assertThat(has(result.warnings(), WarningCodes.NO_ROOMS_DETECTED)).isTrue();
    }

    @Test
    void unreadableOnlyInputAndNoPrompt_failsWithNoReadableInput_butPromptKeepsItAlive() {
        StoredInput broken = new StoredInput("broken.bin", "application/octet-stream", 3, "k1", null);
        when(storage.read("k1")).thenReturn(new byte[] {1, 2, 3});

        job("  ", broken);
        assertThatThrownBy(() -> pipeline(client, 1).run(JOB))
                .isInstanceOfSatisfying(RoomsImportFailureException.class,
                        e -> assertThat(e.getReason()).isEqualTo(RoomsImportErrors.FAILED_NO_READABLE_INPUT));

        job("STUB:THREE_ROOMS", broken);
        ImportResult result = pipeline(client, 1).run(JOB);
        assertThat(has(result.warnings(), WarningCodes.FILE_UNREADABLE)).isTrue();
        assertThat(result.rooms()).hasSize(3);
    }

    @Test
    void imageWithBoundingBoxes_storesCropsAsDraftMedia() throws Exception {
        StoredInput image = new StoredInput("STUB:IMAGE_CROPS.png", "image/png", 1, "img", null);
        when(storage.read("img")).thenReturn(png(400, 300));
        job("plan", image);

        ImportResult result = pipeline(client, 1).run(JOB);

        assertThat(result.rooms()).hasSize(2);
        for (DraftRoom d : result.rooms()) {
            assertThat(d.media()).hasSize(1);
            assertThat(d.media().get(0).mediaId()).isEqualTo("abc.png");
            assertThat(d.media().get(0).previewUrl()).isEqualTo("/api/rooms/autoimport/7/media/abc.png");
            assertThat(has(d.warnings(), WarningCodes.CROP_UNAVAILABLE)).isFalse();
        }
        verify(storage, org.mockito.Mockito.times(2)).storeMedia(eq(JOB), any(), eq("image/png"));
    }

    @Test
    void undecodableImage_fallsBackToWholeSourceWithCropUnavailable() {
        // bytes sniff as PNG-by-name but decode fails: forwarded as is, not croppable
        StoredInput image = new StoredInput("STUB:IMAGE_CROPS.heic", "image/heic", 1, "img", null);
        when(storage.read("img")).thenReturn(new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0,
                'm', 'i', 'f', '1', 'h', 'e', 'i', 'c'});
        job("plan", image);

        ImportResult result = pipeline(client, 1).run(JOB);

        assertThat(result.rooms()).hasSize(2);
        for (DraftRoom d : result.rooms()) {
            assertThat(has(d.warnings(), WarningCodes.CROP_UNAVAILABLE)).isTrue();
        }
    }

    // --- Cancellation (Requirement 24.3) ----------------------------------------------------

    @Test
    void cancelDuringMeasuring_startsNoFurtherGeminiCalls() {
        job("STUB:THREE_ROOMS");
        // the first measuring call cancels the job; the single worker thread then reaches the other
        // two rooms, whose pre-call check must stop them
        client.afterMeasure = () -> cancelled.set(true);

        assertThatThrownBy(() -> pipeline(client, 1).run(JOB)).isInstanceOf(JobCancelledException.class);

        assertThat(client.detectCalls.get()).isEqualTo(1);
        assertThat(client.measureCalls.get()).isEqualTo(1);
        verify(stages, never()).markStage(JOB, RoomsImportStatus.VALIDATING);
    }

    @Test
    void cancelBeforeDetection_startsNoGeminiCallAtAll() {
        job("STUB:THREE_ROOMS");
        doThrow(new JobCancelledException(JOB)).when(stages).markStage(JOB, RoomsImportStatus.DETECTING_ROOMS);

        assertThatThrownBy(() -> pipeline(client, 1).run(JOB)).isInstanceOf(JobCancelledException.class);

        assertThat(client.detectCalls.get()).isZero();
        assertThat(client.measureCalls.get()).isZero();
    }

    @Test
    void jobRowGoneAtStart_isCancelled() {
        when(loader.load(JOB)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> pipeline(client, 1).run(JOB)).isInstanceOf(JobCancelledException.class);
        verify(stages, never()).markStage(anyLong(), any());
    }

    // --- Dedup ---------------------------------------------------------------------------------

    @Test
    void sameLabelFromDifferentSources_isMergedWithSuspectedWarning_sameSourceStaysDistinct() {
        var a = new DetectResponse.DetectedRoom("a", "Łazienka", "bathroom", 2L,
                new DetectResponse.SourceRef("plan.pdf", 1), null, 0.6);
        var b = new DetectResponse.DetectedRoom("b", "lazienka", "bathroom", 2L,
                new DetectResponse.SourceRef("desc.docx", null), null, 0.9);
        var c = new DetectResponse.DetectedRoom("c", "Pokój", "bedroom", 1L,
                new DetectResponse.SourceRef("plan.pdf", 1), null, 0.8);
        var d = new DetectResponse.DetectedRoom("d", "Pokój", "bedroom", 1L,
                new DetectResponse.SourceRef("plan.pdf", 1), null, 0.8);
        java.util.Map<String, List<Warning>> warnings = new java.util.LinkedHashMap<>();

        var kept = RoomsImportPipeline.dedupe(List.of(a, b, c, d), warnings);

        assertThat(kept).extracting(DetectResponse.DetectedRoom::tempId).containsExactly("a", "c", "d");
        assertThat(kept.get(0).confidence()).isEqualTo(0.9); // the better-evidenced duplicate wins
        assertThat(warnings.get("a")).singleElement().satisfies(w -> {
            assertThat(w.code()).isEqualTo(WarningCodes.DUPLICATE_ROOM_SUSPECTED);
            assertThat(w.params().get("sources")).isEqualTo(List.of("plan.pdf#1", "desc.docx"));
        });
    }

    // --- helpers -------------------------------------------------------------------------------

    private static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** Call-counting wrapper; {@code afterMeasure} runs after each measuring call. */
    private static class CountingClient implements RoomsVisionClient {
        final RoomsVisionClient delegate;
        final AtomicInteger detectCalls = new AtomicInteger();
        final AtomicInteger measureCalls = new AtomicInteger();
        volatile Runnable afterMeasure = () -> { };

        CountingClient(RoomsVisionClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public DetectResponse detectRooms(DetectRequest request) {
            detectCalls.incrementAndGet();
            return delegate.detectRooms(request);
        }

        @Override
        public MeasureResponse measureRoom(MeasureRequest request) {
            measureCalls.incrementAndGet();
            try {
                return delegate.measureRoom(request);
            } finally {
                afterMeasure.run();
            }
        }
    }

    private static class DelegatingClient implements RoomsVisionClient {
        private final RoomsVisionClient delegate;

        DelegatingClient(RoomsVisionClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public DetectResponse detectRooms(DetectRequest request) {
            return delegate.detectRooms(request);
        }

        @Override
        public MeasureResponse measureRoom(MeasureRequest request) {
            return delegate.measureRoom(request);
        }
    }
}
