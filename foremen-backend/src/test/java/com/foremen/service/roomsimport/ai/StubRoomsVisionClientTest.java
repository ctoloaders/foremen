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
        assertThat(m.stated().get("floorArea")).isEqualTo(new Measured(22.0, "m2", "EXPLICIT"));
        assertThat(m.vertices().get(2).x() * m.vertices().get(2).y()).isEqualTo(20.0);
    }

    @Test
    void openingsTooBigExceedWallWidthAndArea() {
        var room = stub.detectRooms(detect("STUB:OPENINGS_TOO_BIG")).rooms().get(0);
        MeasureResponse m = stub.measureRoom(measure("STUB:OPENINGS_TOO_BIG", room));
        var opening = m.walls().get(0).openings().get(0);
        assertThat(opening.width().value()).isGreaterThan(3.0);
        assertThat(opening.width().value() * opening.height().value() * opening.count())
                .isGreaterThan(12.0 * 2.5);
    }

    @Test
    void defaultAnswersUseExplicitMetreUnitsPerValue() {
        for (var room : stub.detectRooms(detect("STUB:THREE_ROOMS")).rooms()) {
            MeasureResponse m = stub.measureRoom(measure("STUB:THREE_ROOMS", room));
            assertThat(m.geometryUnit()).isEqualTo("m");
            assertThat(m.geometryUnitSource()).isEqualTo("EXPLICIT");
            assertThat(m.ceilingHeight()).isEqualTo(new Measured(2.6, "m", "EXPLICIT"));
            assertThat(m.stated().get("floorArea").unit()).isEqualTo("m2");
            assertThat(m.stated().get("perimeter").unit()).isEqualTo("m");
        }
    }

    @Test
    void mmUnitsIsAPlanDimensionedInMillimetres() {
        DetectResponse d = stub.detectRooms(detect("STUB:MM_UNITS"));
        assertThat(d.rooms()).hasSize(1);
        MeasureResponse m = stub.measureRoom(measure("STUB:MM_UNITS", d.rooms().get(0)));
        assertThat(RoomsVisionResponseValidator.validate(m)).isNull();
        assertThat(m.geometryUnit()).isEqualTo("mm");
        assertThat(m.vertices().get(2)).isEqualTo(new MeasureResponse.Vertex(3200.0, 4100.0));
        assertThat(m.ceilingHeight()).isEqualTo(new Measured(2600.0, "mm", "EXPLICIT"));
        var door = m.walls().get(0).openings().get(0);
        assertThat(door.width().value()).isEqualTo(900.0);
        assertThat(door.height().value()).isEqualTo(2050.0);
        assertThat(door.width().unit()).isEqualTo("mm");
        var window = m.walls().get(1).openings().get(0);
        assertThat(window.width().value()).isEqualTo(1200.0);
        assertThat(window.height().value()).isEqualTo(1500.0);
        assertThat(m.stated().get("floorArea")).isEqualTo(new Measured(13.12, "m2", "EXPLICIT"));
    }

    @Test
    void templateLIsAnLShapeTemplateAnswerInMillimetres() {
        DetectResponse d = stub.detectRooms(detect("STUB:TEMPLATE_L"));
        assertThat(d.rooms()).extracting(DetectResponse.DetectedRoom::label).containsExactly("Pokój L");
        MeasureResponse m = stub.measureRoom(measure("STUB:TEMPLATE_L", d.rooms().get(0)));
        assertThat(RoomsVisionResponseValidator.validate(m)).isNull();
        assertThat(m.vertices()).isEmpty();
        assertThat(m.template().id()).isEqualTo("L_SHAPE");
        assertThat(m.template().rotation()).isEqualTo(90);
        assertThat(m.template().dims()).containsEntry("width", new Measured(5000.0, "mm", "EXPLICIT"))
                .containsEntry("length", new Measured(4000.0, "mm", "EXPLICIT"))
                .containsEntry("cutWidth", new Measured(2000.0, "mm", "EXPLICIT"))
                .containsEntry("cutLength", new Measured(2000.0, "mm", "EXPLICIT"));
        assertThat(m.walls()).hasSize(6);
        var door = m.walls().get(0).openings().get(0);
        assertThat(door.width()).isEqualTo(new Measured(900.0, "mm", "EXPLICIT"));
        assertThat(door.height()).isEqualTo(new Measured(2050.0, "mm", "EXPLICIT"));
        assertThat(m.ceilingHeight()).isEqualTo(new Measured(2600.0, "mm", "EXPLICIT"));
    }

    @Test
    void ambiguousUnitHasADoorWidthNoSingleRescaleFixes() {
        DetectResponse d = stub.detectRooms(detect("STUB:AMBIGUOUS_UNIT"));
        MeasureResponse m = stub.measureRoom(measure("STUB:AMBIGUOUS_UNIT", d.rooms().get(0)));
        assertThat(RoomsVisionResponseValidator.validate(m)).isNull();
        Measured width = m.walls().get(0).openings().get(0).width();
        assertThat(width).isEqualTo(new Measured(30.0, "m", "INFERRED"));
        // /10 = 3.0 and /100 = 0.3 are both outside the plausible 0.6-2.0 m door width.
        assertThat(width.value() / 10).isGreaterThan(2.0);
        assertThat(width.value() / 100).isLessThan(0.6);
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
