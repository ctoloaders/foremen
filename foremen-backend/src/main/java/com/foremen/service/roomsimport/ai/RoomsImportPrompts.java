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
            FIRST decide which of the six room templates below is the closest to the floor outline and return it
            in "template" { id, rotation, dims, confidence }. Describe the room only with a template whenever
            one fits; return free polygon "vertices" ONLY when no template fits (non-right angles that are not
            a single cut/chamfer corner, curved or irregular rooms). With a template, leave "vertices" empty.
            Templates (all angles are 90 degrees except the diagonal cuts), in their unrotated position,
            y points up, x to the right; every dim is a length written in the source:
            - RECTANGLE: a plain rectangle. dims: width (bottom edge), length (side edge).
            - L_SHAPE: a rectangle with a rectangular notch cut out of the top-right corner.
              dims: width, length (the whole bounding box), cutWidth, cutLength (the notch, both smaller).
            - CUT_CORNER: a rectangle with the top-right corner cut off diagonally.
              dims: width, length, cutSize (the length of each leg of the cut).
            - T_SHAPE: a wide top part with a narrower stem centred below it.
              dims: width (the wide top part), topDepth (its depth), stemWidth (narrower than width),
              stemDepth (depth of the stem).
            - U_SHAPE: a rectangle with a rectangular notch centred on the top edge.
              dims: width, length (bounding box), notchWidth, notchDepth (the notch).
            - CHAMFERED: a rectangle with BOTH top corners cut off diagonally.
              dims: width, length, chamferSize (the leg of each cut).
            rotation = how many degrees (0, 90, 180 or 270) the template must be turned CLOCKWISE (quarter turns)
            to match the plan; use 0 when the plan already shows the room as drawn above. Copy the dims exactly
            as written in the source, with their own unit (the unit rules below are unchanged).
            WALL ORDER: after rotating the template, wall 0 starts at the bottom-most corner of the rotated
            outline (the left-most one if several), and the walls then follow COUNTER-CLOCKWISE (wall i runs from
            corner i to corner i+1). Put every door and window on the matching walls[i]; the walls array length
            must equal the wall count of the template (RECTANGLE 4, L_SHAPE 6, CUT_CORNER 5, T_SHAPE 8,
            U_SHAPE 8, CHAMFERED 6).
            With a template, geometryUnit / geometryUnitSource still describe the main unit of the plan.
            Also return walls (with their doors and windows) and the ceiling height, and the values written in
            the source in "stated" (floorArea, perimeter, ...). For a free polygon give the vertices in ONE
            length unit (geometryUnit: m, cm, mm or ft) with walls aligned to them (wall i runs from vertex i
            to vertex i+1).
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
