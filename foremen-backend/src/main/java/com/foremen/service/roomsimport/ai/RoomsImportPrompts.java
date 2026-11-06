package com.foremen.service.roomsimport.ai;

import java.util.stream.Collectors;

/** Static prompt text for the rooms auto-import calls. Built from data only; never logged at INFO. */
public final class RoomsImportPrompts {

    private RoomsImportPrompts() {}

    public static final String DETECT_SYSTEM = """
            You analyse renovation inputs (floor plans, drawings, spreadsheets, notes) and list the rooms they describe.
            Return one entry per distinct room. Never invent rooms that are not in the inputs.
            For each room give a short tempId (r1, r2, ...), the label as written in the source, a typeHint
            (English room kind such as bedroom, bathroom, kitchen), the roomTypeId from the offered catalogue
            when you are sure (otherwise null), the source file and page, a bbox [ymin, xmin, ymax, xmax]
            normalised to 0..1000 when the room is visible on an image (otherwise null) and a confidence 0..1.
            Set statedRoomCount only if the inputs explicitly state how many rooms there are.
            Respond with JSON only.""";

    public static final String MEASURE_SYSTEM = """
            You extract the dimensions of ONE room from the given image and/or text.
            Return the floor outline as polygon vertices in ONE length unit (geometryUnit: m, cm, mm or ft),
            walls aligned with the vertices (wall i runs from vertex i to vertex i+1) with their doors and windows,
            the ceiling height, and the values written in the source in "stated" (floorArea, perimeter, ...).
            UNITS: copy every number exactly as written in the source and NEVER convert it. Report the unit of
            EVERY value next to it as { value, unit, unitSource }. Length units: m, cm, mm, ft. Area units:
            m2, cm2, mm2. unitSource is EXPLICIT when the unit is written next to the number or in the
            drawing legend, otherwise INFERRED. Do the same for geometryUnit / geometryUnitSource.
            Plain numbers on Polish and Russian architectural plans are usually millimetres (sometimes
            centimetres); areas are usually m2. Counts (internalCorners, opening count, doorCount, windowCount)
            are plain integers without a unit.
            For context only (the backend converts, you must not): the target unit of floorArea, wallArea,
            doorArea and windowArea is m2; of perimeter, ceilingHeight, door/window height and width, wall length
            and opening offset is m; of narrowPlace, wallGap and finishGap is metres.
            Use null for anything that is not present. Never guess or invent numbers; if the scale cannot be
            established set scaleEstablished=false. Respond with JSON only.""";

    public static final String RETRY_SUFFIX =
            "\n\nYour previous answer was not valid for the required JSON schema. "
                    + "Answer again with valid JSON only, strictly following the schema.";

    public static String detectUser(DetectRequest r) {
        StringBuilder sb = new StringBuilder();
        if (r.userPrompt() != null && !r.userPrompt().isBlank()) {
            sb.append("User prompt:\n").append(r.userPrompt().strip()).append("\n\n");
        }
        if (!r.roomTypes().isEmpty()) {
            sb.append("Room type catalogue (id | code | RU | PL):\n");
            sb.append(r.roomTypes().stream()
                    .map(t -> t.id() + " | " + t.code() + " | " + t.nameRu() + " | " + t.namePl())
                    .collect(Collectors.joining("\n"))).append("\n\n");
        }
        for (DetectRequest.TextSource t : r.texts()) {
            sb.append("File ").append(t.fileName()).append(":\n").append(t.text()).append("\n\n");
        }
        if (!r.images().isEmpty()) {
            sb.append("Images attached: ").append(r.images().size()).append(" (in order).\n");
        }
        return sb.toString();
    }

    public static String measureUser(MeasureRequest r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Room: ").append(r.label()).append(" (type: ").append(r.typeHint()).append(")\n");
        if (r.userPrompt() != null && !r.userPrompt().isBlank()) {
            sb.append("User prompt:\n").append(r.userPrompt().strip()).append("\n");
        }
        if (r.text() != null && !r.text().isBlank()) {
            sb.append("Relevant text:\n").append(r.text()).append("\n");
        }
        return sb.toString();
    }
}
