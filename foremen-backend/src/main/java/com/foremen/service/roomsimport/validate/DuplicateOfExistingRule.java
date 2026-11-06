package com.foremen.service.roomsimport.validate;

import com.foremen.service.roomsimport.model.DraftRoom;
import com.foremen.service.roomsimport.model.Severity;
import com.foremen.service.roomsimport.model.Warning;
import com.foremen.service.roomsimport.model.WarningCodes;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code POSSIBLE_DUPLICATE_OF_EXISTING} (Requirement 3.5): a draft room whose normalised label and
 * room type equal those of an existing project room. Pure; existing rooms are only read. Draft rooms
 * are never removed or skipped.
 */
public final class DuplicateOfExistingRule {

    private DuplicateOfExistingRule() {}

    /** One warning per matching draft room (the first matching existing room, in list order). */
    public static List<Warning> apply(List<DraftRoom> drafts, List<ExistingRoomRef> existing) {
        if (drafts == null || existing == null || existing.isEmpty()) {
            return List.of();
        }
        List<Warning> out = new ArrayList<>();
        for (DraftRoom d : drafts) {
            Warning w = forRoom(d, existing);
            if (w != null) {
                out.add(w);
            }
        }
        return out;
    }

    /** Warning for a single draft room, or {@code null}. */
    public static Warning forRoom(DraftRoom draft, List<ExistingRoomRef> existing) {
        if (draft == null || draft.room() == null || existing == null) {
            return null;
        }
        String label = normalize(draft.room().label());
        Long typeId = draft.room().roomTypeId();
        if (label.isEmpty() || typeId == null) {
            return null;
        }
        for (ExistingRoomRef e : existing) {
            if (e != null && typeId.equals(e.roomTypeId()) && label.equals(normalize(e.label()))) {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("existingRoomId", e.id());
                params.put("label", e.label());
                return new Warning(WarningCodes.POSSIBLE_DUPLICATE_OF_EXISTING, Severity.WARNING,
                        draft.tempId(), "label", params, null);
            }
        }
        return null;
    }

    /** Trim, lower-case, strip diacritics ({@code ł -> l}) and collapse whitespace. */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('ł', 'l');
        return n.replaceAll("\\s+", " ").trim();
    }
}
