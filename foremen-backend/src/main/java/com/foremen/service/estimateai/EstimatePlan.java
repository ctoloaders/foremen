package com.foremen.service.estimateai;

import java.util.List;

/**
 * FOR-05-04 (design B5) — the validated plan: priced works per room, plus the report lists.
 */
public record EstimatePlan(List<PlannedWork> works, List<Note> assumptions, List<Note> questions,
                           List<Unpriced> unpriced, List<ReportWarning> warnings) {

    public EstimatePlan {
        works = works == null ? List.of() : List.copyOf(works);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        questions = questions == null ? List.of() : List.copyOf(questions);
        unpriced = unpriced == null ? List.of() : List.copyOf(unpriced);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** An assumption or question: plain text of at most 500 characters, optional anchors. */
    public record Note(String text, String workCode, Long roomId, String source) {
    }
}
