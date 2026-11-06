package com.foremen.service.roomsimport.classify;

import com.foremen.service.roomsimport.classify.RoomTypeResolver.Resolution;
import com.foremen.service.roomsimport.classify.RoomTypeResolver.RoomTypeOption;
import com.foremen.service.roomsimport.model.WarningCodes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RoomTypeResolverTest {

    private final RoomTypeResolver resolver = new RoomTypeResolver(List.of(
            new RoomTypeOption(1L, "BATHROOM", "Ванная", "Łazienka"),
            new RoomTypeOption(2L, "KITCHEN", "Кухня", "Kuchnia"),
            new RoomTypeOption(3L, "LIVING_ROOM", "Гостиная", "Salon"),
            new RoomTypeOption(4L, "BEDROOM", "Спальня", "Sypialnia"),
            new RoomTypeOption(5L, "BATH_TOILET", "Ванная с туалетом", "Łazienka z WC")));

    @Test
    void validModelIdIsAccepted() {
        Resolution r = resolver.resolve(2L, "whatever", "x", "r1");
        assertThat(r.roomTypeId()).isEqualTo(2L);
        assertThat(r.warning()).isEmpty();
    }

    @Test
    void unknownModelIdFallsBackToNameMatch() {
        Resolution r = resolver.resolve(999L, "Кухня", null, "r1");
        assertThat(r.roomTypeId()).isEqualTo(2L);
        assertThat(r.warning()).isEmpty();
    }

    @Test
    void matchesRussianNameCaseInsensitively() {
        assertThat(resolver.resolve(null, "  СПАЛЬНЯ ", null, "r").roomTypeId()).isEqualTo(4L);
    }

    @Test
    void matchesPolishNameIgnoringDiacritics() {
        assertThat(resolver.resolve(null, "lazienka", null, "r").roomTypeId()).isEqualTo(1L);
        assertThat(resolver.resolve(null, "ŁAZIENKA", null, "r").roomTypeId()).isEqualTo(1L);
    }

    @Test
    void matchesCodeWithSeparators() {
        assertThat(resolver.resolve(null, "living room", null, "r").roomTypeId()).isEqualTo(3L);
        assertThat(resolver.resolve(null, "living_room", null, "r").roomTypeId()).isEqualTo(3L);
    }

    @Test
    void fallsBackToLabelWhenHintMissing() {
        assertThat(resolver.resolve(null, null, "Salon", "r").roomTypeId()).isEqualTo(3L);
    }

    @Test
    void unresolvedYieldsNullIdAndWarningWithAtMostThreeCandidates() {
        Resolution r = resolver.resolve(null, "Ванная комната", null, "room-7");
        assertThat(r.roomTypeId()).isNull();
        var w = r.warning().orElseThrow();
        assertThat(w.code()).isEqualTo(WarningCodes.ROOM_TYPE_UNRESOLVED);
        assertThat(w.roomTempId()).isEqualTo("room-7");
        assertThat(w.field()).isEqualTo("roomTypeId");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) w.suggestedFix().get("candidates");
        assertThat(candidates).hasSizeBetween(1, 3);
        assertThat(candidates.get(0).get("roomTypeId")).isIn(1L, 5L);
    }

    @Test
    void unrelatedTextGivesEmptyCandidatesButStillWarns() {
        Resolution r = resolver.resolve(null, "zzzzqqqq", null, "r");
        assertThat(r.roomTypeId()).isNull();
        @SuppressWarnings("unchecked")
        List<Object> candidates = (List<Object>) r.warning().orElseThrow().suggestedFix().get("candidates");
        assertThat(candidates).isEmpty();
    }

    @Test
    void emptyCatalogueAndNullInputsNeverThrow() {
        Resolution r = new RoomTypeResolver(null).resolve(null, null, null, "r");
        assertThat(r.roomTypeId()).isNull();
        assertThat(r.warning()).isPresent();
    }
}
