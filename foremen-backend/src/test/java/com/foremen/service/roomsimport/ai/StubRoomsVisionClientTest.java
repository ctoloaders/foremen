package com.foremen.service.roomsimport.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foremen.service.roomsimport.ai.DetectRequest.RoomTypeRef;
import com.foremen.service.roomsimport.ai.DetectRequest.TextSource;
import com.foremen.service.roomsimport.ai.RoomsVisionException.Reason;
import com.foremen.service.roomsimport.extract.PageImage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StubRoomsVisionClientTest {

    private final List<Long> slept = new ArrayList<>();
    private final StubRoomsVisionClient stub = new StubRoomsVisionClient(slept::add);

    private DetectRequest detect(String prompt) {
        return new DetectRequest(prompt, List.of(), List.of(), List.of());
    }

    private MeasureRequest measure(String prompt, DetectResponse.DetectedRoom r) {
        return new MeasureRequest(r.tempId(), r.label(), r.typeHint(), null, null, prompt, null);
    }

    @Test
    void threeRoomsIsTheDefaultAndPassesValidation() {
        DetectResponse r = stub.detectRooms(detect("3 pokoje"));
        assertThat(r.rooms()).extracting(DetectResponse.DetectedRoom::label)
                .containsExactly("Sypialnia", "Łazienka", "Kuchnia");
        assertThat(RoomsVisionResponseValidator.validate(r)).isNull();
        assertThat(r.statedRoomCount()).isNull();
        for (var room : r.rooms()) {
            MeasureResponse m = stub.measureRoom(measure("3 pokoje", room));
            assertThat(RoomsVisionResponseValidator.validate(m)).isNull();
            assertThat(m.walls()).hasSameSizeAs(m.vertices());
        }
    }

    @Test
    void kitchenIsLShapedWithSixVertices() {
        var kitchen = stub.detectRooms(detect("STUB:THREE_ROOMS")).rooms().get(2);
        assertThat(stub.measureRoom(measure("STUB:THREE_ROOMS", kitchen)).vertices()).hasSize(6);
    }

    @Test
    void roomCountMismatchStatesFourButReturnsThree() {
        DetectResponse r = stub.detectRooms(detect("STUB:ROOM_COUNT_MISMATCH"));
        assertThat(r.rooms()).hasSize(3);
        assertThat(r.statedRoomCount()).isEqualTo(4);
    }

    @Test
    void areaMismatchStatesAreaTenPercentOffTheGeometry() {
        var room = stub.detectRooms(detect("STUB:AREA_MISMATCH")).rooms().get(0);
        MeasureResponse m = stub.measureRoom(measure("STUB:AREA_MISMATCH", room));
        // 4 x 5 polygon = 20 m2; stated 22.
        assertThat(((Number) m.stated().get("floorArea")).doubleValue()).isEqualTo(22.0);
        assertThat(m.vertices().get(2).x() * m.vertices().get(2).y()).isEqualTo(20.0);
    }

    @Test
    void openingsTooBigExceedWallWidthAndArea() {
        var room = stub.detectRooms(detect("STUB:OPENINGS_TOO_BIG")).rooms().get(0);
        MeasureResponse m = stub.measureRoom(measure("STUB:OPENINGS_TOO_BIG", room));
        var opening = m.walls().get(0).openings().get(0);
        assertThat(opening.width()).isGreaterThan(3.0);
        assertThat(opening.width() * opening.height() * opening.count()).isGreaterThan(12.0 * 2.5);
    }

    @Test
    void unknownTypeHasNoCatalogueMatchEvenWithCatalogue() {
        DetectRequest req = new DetectRequest("STUB:UNKNOWN_TYPE", List.of(), List.of(),
                List.of(new RoomTypeRef(1L, "bedroom", "Спальня", "Sypialnia")));
        assertThat(stub.detectRooms(req).rooms().get(0).roomTypeId()).isNull();
    }

    @Test
    void catalogueIdIsFilledWhenTypeMatches() {
        DetectRequest req = new DetectRequest("STUB:THREE_ROOMS", List.of(), List.of(),
                List.of(new RoomTypeRef(7L, "BEDROOM", "Спальня", "Sypialnia")));
        var rooms = stub.detectRooms(req).rooms();
        assertThat(rooms.get(0).roomTypeId()).isEqualTo(7L);
        assertThat(rooms.get(1).roomTypeId()).isNull();
    }

    @Test
    void textOnlyScalarsHaveNoGeometry() {
        var room = stub.detectRooms(detect("STUB:TEXT_ONLY_SCALARS")).rooms().get(0);
        MeasureResponse m = stub.measureRoom(measure("STUB:TEXT_ONLY_SCALARS", room));
        assertThat(m.vertices()).isEmpty();
        assertThat(m.stated()).containsKey("floorArea");
        assertThat(RoomsVisionResponseValidator.validate(m)).isNull();
    }

    @Test
    void imageCropsReturnsTwoRoomsWithBoxes() {
        DetectResponse r = stub.detectRooms(detect("STUB:IMAGE_CROPS"));
        assertThat(r.rooms()).hasSize(2).allSatisfy(room -> assertThat(room.bbox()).hasSize(4));
        assertThat(RoomsVisionResponseValidator.validate(r)).isNull();
    }

    @Test
    void llmDownFailsDetection() {
        assertThatThrownBy(() -> stub.detectRooms(detect("STUB:LLM_DOWN")))
                .isInstanceOfSatisfying(RoomsVisionException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.LLM_UNAVAILABLE));
    }

    @Test
    void markerInFileNameIsHonoured() {
        PageImage img = new PageImage(new byte[] {1}, "image/png", 10, 10, true, "plan STUB:IMAGE_CROPS.png", null);
        DetectResponse r = stub.detectRooms(new DetectRequest("", List.of(), List.of(img), List.of()));
        assertThat(r.rooms()).hasSize(2);
        assertThat(r.rooms().get(0).sourceRef().fileName()).contains("IMAGE_CROPS");
    }

    @Test
    void markerInTextContentIsHonoured() {
        DetectRequest req = new DetectRequest("", List.of(new TextSource("rooms.txt", "STUB:AREA_MISMATCH")),
                List.of(), List.of());
        assertThat(stub.detectRooms(req).rooms()).extracting(DetectResponse.DetectedRoom::label)
                .containsExactly("Salon");
    }

    @Test
    void slowModifierDelaysMeasuringOnly() {
        stub.detectRooms(detect("STUB:THREE_ROOMS STUB:SLOW=7"));
        assertThat(slept).isEmpty();
        var room = stub.detectRooms(detect("STUB:THREE_ROOMS STUB:SLOW=7")).rooms().get(0);
        stub.measureRoom(measure("STUB:THREE_ROOMS STUB:SLOW=7", room));
        assertThat(slept).containsExactly(7000L);
    }

    @Test
    void slowWithoutValueUsesDefaultAndDoesNotChangeScenario() {
        var room = stub.detectRooms(detect("STUB:SLOW STUB:AREA_MISMATCH")).rooms().get(0);
        assertThat(room.label()).isEqualTo("Salon");
        stub.measureRoom(measure("STUB:SLOW STUB:AREA_MISMATCH", room));
        assertThat(slept).containsExactly(20_000L);
    }

    @Test
    void measureFindsRoomByLabelWhenMarkerOnlyInDetectionInputs() {
        var room = stub.detectRooms(detect("STUB:AREA_MISMATCH")).rooms().get(0);
        MeasureResponse m = stub.measureRoom(measure("", room));
        assertThat(m.stated()).containsKey("floorArea");
    }
}
