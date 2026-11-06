package com.foremen.service.roomsimport.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foremen.service.roomsimport.ai.RoomsVisionException.Reason;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeminiRoomsVisionClientTest {

    private static final String VALID_DETECT =
            "{\"rooms\":[{\"tempId\":\"r1\",\"label\":\"Kuchnia\",\"typeHint\":\"kitchen\","
                    + "\"bbox\":[10,10,500,500],\"confidence\":0.8}],\"statedRoomCount\":1}";
    private static final String VALID_MEASURE =
            "{\"unit\":\"cm\",\"vertices\":[{\"x\":0,\"y\":0},{\"x\":300,\"y\":0},{\"x\":300,\"y\":400}],"
                    + "\"walls\":[{\"openings\":[{\"type\":\"DOOR\",\"count\":1,\"height\":200,\"width\":90}]},{},{}],"
                    + "\"ceilingHeight\":2.6,\"stated\":{\"floorArea\":null,\"perimeter\":12.0},"
                    + "\"scaleEstablished\":true,\"confidence\":0.7}";

    /** Fake transport that replays scripted answers and records the calls. */
    private static final class Fake implements GeminiJsonTransport {
        final List<String> answers = new ArrayList<>();
        final List<String> userTexts = new ArrayList<>();
        final List<Duration> timeouts = new ArrayList<>();
        final List<Map<String, Object>> schemas = new ArrayList<>();
        RuntimeException failure;

        Fake(String... answers) {
            this.answers.addAll(List.of(answers));
        }

        @Override
        public String generate(String system, String user, List<com.foremen.service.roomsimport.extract.PageImage> images,
                               Map<String, Object> schema, Duration timeout) {
            userTexts.add(user);
            timeouts.add(timeout);
            schemas.add(schema);
            if (failure != null) {
                throw failure;
            }
            return answers.get(Math.min(userTexts.size(), answers.size()) - 1);
        }
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static DetectRequest detectReq() {
        return new DetectRequest("prompt", List.of(), List.of(), List.of());
    }

    private static MeasureRequest measureReq() {
        return new MeasureRequest("r1", "Kuchnia", "kitchen", null, "text", "", "f.txt");
    }

    @Test
    void validDetectAnswerParsesWithoutRetry() {
        Fake fake = new Fake(VALID_DETECT);
        DetectResponse r = new GeminiRoomsVisionClient(fake, TIMEOUT).detectRooms(detectReq());
        assertThat(r.rooms()).hasSize(1);
        assertThat(r.rooms().get(0).bbox()).containsExactly(10.0, 10.0, 500.0, 500.0);
        assertThat(r.statedRoomCount()).isEqualTo(1);
        assertThat(fake.userTexts).hasSize(1);
        assertThat(fake.timeouts).containsExactly(TIMEOUT);
        assertThat(fake.schemas.get(0)).containsKey("properties");
    }

    @Test
    void validMeasureAnswerKeepsNullStatedValues() {
        Fake fake = new Fake("```json\n" + VALID_MEASURE + "\n```");
        MeasureResponse m = new GeminiRoomsVisionClient(fake, TIMEOUT).measureRoom(measureReq());
        assertThat(m.unit()).isEqualTo("cm");
        assertThat(m.vertices()).hasSize(3);
        assertThat(m.stated()).containsKey("floorArea").containsEntry("perimeter", 12.0);
        assertThat(m.walls().get(0).openings().get(0).type()).isEqualTo("DOOR");
    }

    @Test
    void malformedFirstAnswerIsRetriedOnceWithCorrection() {
        Fake fake = new Fake("not json", VALID_DETECT);
        DetectResponse r = new GeminiRoomsVisionClient(fake, TIMEOUT).detectRooms(detectReq());
        assertThat(r.rooms()).hasSize(1);
        assertThat(fake.userTexts).hasSize(2);
        assertThat(fake.userTexts.get(1)).contains("not valid for the required JSON schema");
        assertThat(fake.userTexts.get(0)).doesNotContain("not valid for the required JSON schema");
    }

    @Test
    void structurallyInvalidAnswerIsRetriedThenFailsAfterBoundedAttempts() {
        // bbox out of range => validation failure both times.
        String bad = "{\"rooms\":[{\"tempId\":\"r1\",\"label\":\"x\",\"bbox\":[0,0,5000,10],\"confidence\":0.5}]}";
        Fake fake = new Fake(bad, bad, VALID_DETECT);
        assertThatThrownBy(() -> new GeminiRoomsVisionClient(fake, TIMEOUT).detectRooms(detectReq()))
                .isInstanceOfSatisfying(RoomsVisionException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.INVALID_RESPONSE));
        assertThat(fake.userTexts).hasSize(2); // never a third call
    }

    @Test
    void blankAnswerIsInvalid() {
        Fake fake = new Fake("", "  ");
        assertThatThrownBy(() -> new GeminiRoomsVisionClient(fake, TIMEOUT).measureRoom(measureReq()))
                .isInstanceOfSatisfying(RoomsVisionException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.INVALID_RESPONSE));
        assertThat(fake.userTexts).hasSize(2);
    }

    @Test
    void unknownOpeningTypeIsRejected() {
        String bad = VALID_MEASURE.replace("DOOR", "GATE");
        Fake fake = new Fake(bad, VALID_MEASURE);
        MeasureResponse m = new GeminiRoomsVisionClient(fake, TIMEOUT).measureRoom(measureReq());
        assertThat(m.walls().get(0).openings().get(0).type()).isEqualTo("DOOR");
        assertThat(fake.userTexts).hasSize(2);
    }

    @Test
    void providerFailureIsNotRetriedAndPropagates() {
        Fake fake = new Fake(VALID_DETECT);
        fake.failure = new RoomsVisionException(Reason.LLM_UNAVAILABLE, "down");
        assertThatThrownBy(() -> new GeminiRoomsVisionClient(fake, TIMEOUT).detectRooms(detectReq()))
                .isInstanceOfSatisfying(RoomsVisionException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.LLM_UNAVAILABLE));
        assertThat(fake.userTexts).hasSize(1);
    }

    @Test
    void schemasDescribeTheResponses() {
        assertThat(RoomsVisionSchemas.detect()).containsEntry("type", "object");
        assertThat(RoomsVisionSchemas.measure().get("required")).isEqualTo(
                List.of("unit", "vertices", "walls", "confidence"));
    }

    @Test
    void disabledClientFailsWithDisabledReason() {
        DisabledRoomsVisionClient disabled = new DisabledRoomsVisionClient();
        assertThat(disabled.isEnabled()).isFalse();
        assertThatThrownBy(() -> disabled.detectRooms(detectReq()))
                .isInstanceOfSatisfying(RoomsVisionException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.DISABLED));
        assertThatThrownBy(() -> disabled.measureRoom(measureReq()))
                .isInstanceOfSatisfying(RoomsVisionException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.DISABLED));
    }
}
