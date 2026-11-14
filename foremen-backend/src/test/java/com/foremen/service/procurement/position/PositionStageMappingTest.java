package com.foremen.service.procurement.position;

import com.foremen.dao.model.PositionStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** FOR-11-00 R8.8 / R12: every stage maps to a code seeded by changeset 228 (or one of the pre-existing four). */
class PositionStageMappingTest {

    private static final String[] EXPECTED = {"new", "proposed", "accepted", "in_proforma", "client_paid",
            "with_buyer", "ordered", "supplier_paid", "in_delivery", "partially_received", "delivered", "cancelled"};

    @Test
    void mappingFollowsFunnelOrder() {
        PositionStatus[] all = PositionStatus.values();
        assertThat(all).hasSize(EXPECTED.length);
        for (int i = 0; i < all.length; i++) {
            assertThat(PositionStage.statusCode(all[i])).isEqualTo(EXPECTED[i]);
            if (all[i] != PositionStatus.CANCELLED) {
                assertThat(PositionStage.rank(all[i])).isEqualTo(i + 1);
            }
        }
        assertThat(PositionStage.rank(PositionStatus.CANCELLED)).isEqualTo(PositionStage.UNRANKED);
    }

    @Test
    void codesAndOrderMatchSeedChangeset() throws Exception {
        String xml = Files.readString(Path.of("database_files/changesets/228-seed-procurement-delivery-stages.xml"));
        Matcher m = Pattern.compile("\\('([a-z_]+)',\\s+(\\d+),").matcher(xml);
        int seeded = 0;
        while (m.find()) {
            seeded++;
            String code = m.group(1);
            int order = Integer.parseInt(m.group(2));
            PositionStatus status = null;
            for (PositionStatus s : PositionStatus.values()) {
                if (code.equals(PositionStage.statusCode(s))) {
                    status = s;
                }
            }
            assertThat(status).as("seeded code %s", code).isNotNull();
            assertThat(PositionStage.rank(status)).isEqualTo(order);
        }
        assertThat(seeded).isEqualTo(8);
    }
}
