package com.foremen.service.roomsimport.ai;

import com.foremen.service.roomsimport.ai.DetectRequest.RoomTypeRef;
import com.foremen.service.roomsimport.ai.DetectResponse.DetectedRoom;
import com.foremen.service.roomsimport.ai.DetectResponse.SourceRef;
import com.foremen.service.roomsimport.ai.MeasureResponse.Opening;
import com.foremen.service.roomsimport.ai.MeasureResponse.Vertex;
import com.foremen.service.roomsimport.ai.MeasureResponse.Wall;
import com.foremen.service.roomsimport.ai.RoomsVisionException.Reason;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * FOR-05-02 (design §Testing, Requirement 13) — deterministic {@link RoomsVisionClient} for
 * integration tests and the Docker stack, active when {@code foremen.rooms-import.enabled=true} and
 * {@code foremen.rooms-import.llm.stub=true} ({@code FOREMEN_ROOMS_IMPORT_LLM_STUB=true}).
 *
 * <p>The canned answer is chosen by a marker found in the user prompt, a file name or the extracted
 * text (searched in that order): {@code STUB:THREE_ROOMS} (also the default when no marker is
 * present), {@code STUB:AREA_MISMATCH}, {@code STUB:OPENINGS_TOO_BIG}, {@code STUB:UNKNOWN_TYPE},
 * {@code STUB:TEXT_ONLY_SCALARS}, {@code STUB:IMAGE_CROPS}, {@code STUB:ROOM_COUNT_MISMATCH},
 * {@code STUB:LLM_DOWN}, {@code STUB:MM_UNITS} (plan dimensioned in mm, Requirement 25) and
 * {@code STUB:AMBIGUOUS_UNIT} (a value no single rescale fixes). All answers use the per-value
 * {@code { value, unit, unitSource }} shape. The modifier {@code STUB:SLOW=<seconds>} (default 20) delays every measuring
 * call so an active job can be observed.
 */
@Component
@ConditionalOnExpression("${foremen.rooms-import.enabled:false} and ${foremen.rooms-import.llm.stub:false}")
public class StubRoomsVisionClient implements RoomsVisionClient {

    /** Canned scenarios selectable by marker. */
    public enum Scenario {
        THREE_ROOMS, AREA_MISMATCH, OPENINGS_TOO_BIG, UNKNOWN_TYPE, TEXT_ONLY_SCALARS, IMAGE_CROPS,
        ROOM_COUNT_MISMATCH, LLM_DOWN, MM_UNITS, AMBIGUOUS_UNIT
    }

    /** Sleeps for the SLOW modifier; replaceable in tests. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    static final int DEFAULT_SLOW_SECONDS = 20;
    private static final Pattern MARKER = Pattern.compile("STUB:([A-Z_]+)(?:=(\\d+))?");

    private final Sleeper sleeper;

    public StubRoomsVisionClient() {
        this(Thread::sleep);
    }

    StubRoomsVisionClient(Sleeper sleeper) {
        this.sleeper = sleeper;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    // --- Marker parsing -------------------------------------------------------------------------

    /** Scenario named by the first scenario marker in {@code sources}, else {@link Scenario#THREE_ROOMS}. */
    static Scenario scenarioOf(List<String> sources) {
        for (String s : sources) {
            if (s == null) {
                continue;
            }
            Matcher m = MARKER.matcher(s);
            while (m.find()) {
                try {
                    return Scenario.valueOf(m.group(1));
                } catch (IllegalArgumentException ignored) {
                    // SLOW or an unknown marker: keep looking.
                }
            }
        }
        return Scenario.THREE_ROOMS;
    }

    /** Seconds of the {@code STUB:SLOW[=n]} modifier, or 0 when absent. */
    static int slowSecondsOf(List<String> sources) {
        for (String s : sources) {
            if (s == null) {
                continue;
            }
            Matcher m = MARKER.matcher(s);
            while (m.find()) {
                if ("SLOW".equals(m.group(1))) {
                    return m.group(2) == null ? DEFAULT_SLOW_SECONDS : Integer.parseInt(m.group(2));
                }
            }
        }
        return 0;
    }

    // --- Detection ------------------------------------------------------------------------------

    @Override
    public DetectResponse detectRooms(DetectRequest request) {
        List<String> sources = new ArrayList<>();
        sources.add(request.userPrompt());
        request.images().forEach(i -> sources.add(i.fileName()));
        request.texts().forEach(t -> sources.add(t.fileName()));
        request.texts().forEach(t -> sources.add(t.text()));
        Scenario scenario = scenarioOf(sources);
        if (scenario == Scenario.LLM_DOWN) {
            throw new RoomsVisionException(Reason.LLM_UNAVAILABLE, "stub: LLM is down");
        }
        String file = request.images().isEmpty()
                ? (request.texts().isEmpty() ? "prompt" : request.texts().get(0).fileName())
                : request.images().get(0).fileName();
        Integer page = request.images().isEmpty() ? null : request.images().get(0).page();
        SourceRef ref = new SourceRef(file, page);
        List<DetectedRoom> rooms = new ArrayList<>();
        for (Canned c : canned(scenario)) {
            rooms.add(new DetectedRoom(c.tempId, c.label, c.typeHint,
                    c.typeHint == null ? null : catalogueId(request.roomTypes(), c.typeHint, c.label),
                    ref, c.bbox, 0.9));
        }
        Integer stated = scenario == Scenario.ROOM_COUNT_MISMATCH ? Integer.valueOf(4) : null;
        return new DetectResponse(rooms, stated, "");
    }

    private static Long catalogueId(List<RoomTypeRef> catalogue, String typeHint, String label) {
        for (String text : new String[] {typeHint, label}) {
            String n = norm(text);
            for (RoomTypeRef t : catalogue) {
                if (n.equals(norm(t.code())) || n.equals(norm(t.nameRu())) || n.equals(norm(t.namePl()))) {
                    return t.id();
                }
            }
        }
        return null;
    }

    private static String norm(String s) {
        if (s == null) {
            return "";
        }
        String d = Normalizer.normalize(s.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return d.replace('ł', 'l');
    }

    // --- Measuring ------------------------------------------------------------------------------

    @Override
    public MeasureResponse measureRoom(MeasureRequest request) {
        List<String> sources = new ArrayList<>();
        sources.add(request.userPrompt());
        sources.add(request.sourceName());
        sources.add(request.text());
        int slow = slowSecondsOf(sources);
        if (slow > 0) {
            try {
                sleeper.sleep(slow * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RoomsVisionException(Reason.LLM_UNAVAILABLE, "stub: interrupted");
            }
        }
        Scenario scenario = scenarioOf(sources);
        Canned found = null;
        for (Canned c : canned(scenario)) {
            if (c.tempId.equals(request.tempId()) || c.label.equals(request.label())) {
                found = c;
                break;
            }
        }
        if (found == null) {
            // Marker only in the detection inputs: locate the room by label across all scenarios.
            for (Scenario s : Scenario.values()) {
                for (Canned c : canned(s)) {
                    if (c.label.equals(request.label())) {
                        found = c;
                        break;
                    }
                }
                if (found != null) {
                    break;
                }
            }
        }
        if (found == null) {
            throw new RoomsVisionException(Reason.INVALID_RESPONSE, "stub: unknown room");
        }
        return found.measure;
    }

    // --- Canned data ----------------------------------------------------------------------------

    private record Canned(String tempId, String label, String typeHint, List<Double> bbox,
                          MeasureResponse measure) {}

    private static List<Canned> canned(Scenario scenario) {
        return switch (scenario) {
            case THREE_ROOMS, ROOM_COUNT_MISMATCH -> List.of(
                    new Canned("r1", "Sypialnia", "bedroom", null,
                            rect(3.2, 4.1, 2.6, List.of(), List.of(), 13.12, 14.6)),
                    new Canned("r2", "Łazienka", "bathroom", null, bathroom()),
                    new Canned("r3", "Kuchnia", "kitchen", null, lShapedKitchen()));
            case AREA_MISMATCH -> List.of(new Canned("r1", "Salon", "living room", null,
                    // 4 x 5 = 20 m2 calculated, 22 m2 stated (10 % off).
                    rect(4, 5, 2.7, List.of(), List.of(), 22.0, 18.0)));
            case OPENINGS_TOO_BIG -> List.of(new Canned("r1", "Pokój z oknami", "bedroom", null,
                    // 3 x 3 room, ceiling 2.5: wall area 30 m2, one 3.5 m wide window x4 = 33.6 m2.
                    rect(3, 3, 2.5, List.of(op("WINDOW", 4, 2.4, 3.5, null)), List.of(), 9.0, 12.0)));
            case UNKNOWN_TYPE -> List.of(new Canned("r1", "Pomieszczenie X", "zzz-unknown", null,
                    rect(3, 3, 2.6, List.of(), List.of(), 9.0, 12.0)));
            case TEXT_ONLY_SCALARS -> List.of(new Canned("r1", "Pokój tekstowy", "bedroom", null, textOnly()));
            case IMAGE_CROPS -> List.of(
                    new Canned("r1", "Pokój A", "bedroom", List.of(100.0, 100.0, 500.0, 450.0),
                            rect(3, 4, 2.6, List.of(), List.of(), 12.0, 14.0)),
                    new Canned("r2", "Pokój B", "kitchen", List.of(100.0, 500.0, 500.0, 900.0),
                            rect(3, 3.5, 2.6, List.of(), List.of(), 10.5, 13.0)));
            case MM_UNITS -> List.of(new Canned("r1", "Pokój MM", "bedroom", null, mmPlan()));
            case AMBIGUOUS_UNIT -> List.of(new Canned("r1", "Pokój niejednoznaczny", "bedroom", null,
                    ambiguousUnit()));
            case LLM_DOWN -> List.of();
        };
    }

    /** Opening with all three sizes explicitly in metres. */
    private static Opening op(String type, int count, double height, double width, Double offset) {
        return new Opening(type, count, Measured.explicit(height, "m"), Measured.explicit(width, "m"),
                offset == null ? null : Measured.explicit(offset, "m"));
    }

    private static Wall wall(List<Opening> openings) {
        return new Wall(null, null, openings);
    }

    private static MeasureResponse rect(double w, double h, double ceiling, List<Opening> wall0,
                                        List<Opening> wall1, double statedArea, double statedPerimeter) {
        List<Vertex> v = List.of(new Vertex(0.0, 0.0), new Vertex(w, 0.0), new Vertex(w, h), new Vertex(0.0, h));
        List<Wall> walls = List.of(wall(wall0), wall(wall1), wall(List.of()), wall(List.of()));
        return new MeasureResponse("m", Measured.EXPLICIT, v, walls, Measured.explicit(ceiling, "m"), 0, null,
                stated(statedArea, statedPerimeter), true, 0.9);
    }

    /**
     * STUB:MM_UNITS — a plan dimensioned in millimetres: 3200 x 4100 rectangle, ceiling 2600, door
     * 900 x 2050 (wall 0), window 1200 x 1500 (wall 1), stated floorArea 13.12 m2 and perimeter 14600 mm.
     */
    private static MeasureResponse mmPlan() {
        List<Vertex> v = List.of(new Vertex(0.0, 0.0), new Vertex(3200.0, 0.0), new Vertex(3200.0, 4100.0),
                new Vertex(0.0, 4100.0));
        Opening door = new Opening("DOOR", 1, Measured.explicit(2050, "mm"), Measured.explicit(900, "mm"),
                Measured.explicit(500, "mm"));
        Opening window = new Opening("WINDOW", 1, Measured.explicit(1500, "mm"), Measured.explicit(1200, "mm"),
                Measured.explicit(900, "mm"));
        List<Wall> walls = List.of(wall(List.of(door)), wall(List.of(window)), wall(List.of()), wall(List.of()));
        Map<String, Measured> stated = new LinkedHashMap<>();
        stated.put("floorArea", Measured.explicit(13.12, "m2"));
        stated.put("perimeter", Measured.explicit(14600, "mm"));
        return new MeasureResponse("mm", Measured.EXPLICIT, v, walls, Measured.explicit(2600, "mm"), 0, null,
                stated, true, 0.9);
    }

    /**
     * STUB:AMBIGUOUS_UNIT — a 3 x 4 m room whose door width is "30 m" (INFERRED): neither /10 (3.0) nor
     * /100 (0.3) lands in the plausible 0.6-2.0 m range, so no single rescale fixes it.
     */
    private static MeasureResponse ambiguousUnit() {
        Opening door = new Opening("DOOR", 1, Measured.explicit(2.05, "m"), Measured.inferred(30, "m"),
                Measured.explicit(0.5, "m"));
        return rect(3, 4, 2.6, List.of(door), List.of(), 12.0, 14.0);
    }

    private static MeasureResponse bathroom() {
        // 1.8 x 2.4: door 0.9 x 2.0 on wall 0 (1.8 m), window 0.6 x 1.0 on wall 1 (2.4 m).
        return rect(1.8, 2.4, 2.6,
                List.of(op("DOOR", 1, 2.0, 0.9, 0.4)),
                List.of(op("WINDOW", 1, 1.0, 0.6, 0.9)), 4.32, 8.4);
    }

    private static MeasureResponse lShapedKitchen() {
        List<Vertex> v = List.of(new Vertex(0.0, 0.0), new Vertex(4.0, 0.0), new Vertex(4.0, 2.0),
                new Vertex(2.0, 2.0), new Vertex(2.0, 3.5), new Vertex(0.0, 3.5));
        List<Wall> walls = new ArrayList<>();
        for (int i = 0; i < v.size(); i++) {
            walls.add(wall(i == 0 ? List.of(op("DOOR", 1, 2.0, 0.9, 0.5)) : List.of()));
        }
        // Area 4*2 + 2*1.5 = 11, perimeter 4+2+2+1.5+2+3.5 = 15, one reflex corner at (2, 2).
        return new MeasureResponse("m", Measured.EXPLICIT, v, walls, Measured.explicit(2.6, "m"), 1, null,
                stated(11.0, 15.0), true, 0.9);
    }

    private static MeasureResponse textOnly() {
        Map<String, Measured> stated = new LinkedHashMap<>();
        stated.put("floorArea", Measured.explicit(12.0, "m2"));
        stated.put("ceilingHeight", Measured.explicit(2.6, "m"));
        return new MeasureResponse(null, null, List.of(), List.of(), Measured.explicit(2.6, "m"), null, null,
                stated, false, 0.7);
    }

    private static Map<String, Measured> stated(double area, double perimeter) {
        Map<String, Measured> m = new LinkedHashMap<>();
        m.put("floorArea", Measured.explicit(area, "m2"));
        m.put("perimeter", Measured.explicit(perimeter, "m"));
        return m;
    }
}
