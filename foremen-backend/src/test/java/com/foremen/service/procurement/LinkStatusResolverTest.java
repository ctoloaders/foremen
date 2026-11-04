package com.foremen.service.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.dao.model.MatchStatus;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Arbitrary;
import org.junit.jupiter.api.Test;

/**
 * Unit + property tests for {@link LinkStatusResolver} (FOR-07-03, Requirement 4.6).
 *
 * <p><b>Validates: Requirements 4.6</b>
 */
class LinkStatusResolverTest {

    private static LinkStatus expected(boolean project, MatchStatus ms, boolean cat) {
        if (!project) {
            return LinkStatus.NO_PROJECT;
        }
        if (ms == MatchStatus.AUTO || ms == MatchStatus.MANUAL_MATCHED) {
            return LinkStatus.LINKED;
        }
        return cat ? LinkStatus.CLASSIFIED : LinkStatus.UNLINKED;
    }

    @Test
    void allCombinationsResolveAsSpecified() {
        for (boolean project : new boolean[] {true, false}) {
            for (MatchStatus ms : MatchStatus.values()) {
                for (boolean cat : new boolean[] {true, false}) {
                    assertThat(LinkStatusResolver.resolve(project, ms, cat))
                            .as("project=%s ms=%s cat=%s", project, ms, cat)
                            .isEqualTo(expected(project, ms, cat));
                }
            }
        }
    }

    @Test
    void explicitExamples() {
        assertThat(LinkStatusResolver.resolve(false, MatchStatus.AUTO, true)).isEqualTo(LinkStatus.NO_PROJECT);
        assertThat(LinkStatusResolver.resolve(true, MatchStatus.AUTO, false)).isEqualTo(LinkStatus.LINKED);
        assertThat(LinkStatusResolver.resolve(true, MatchStatus.MANUAL_MATCHED, true)).isEqualTo(LinkStatus.LINKED);
        assertThat(LinkStatusResolver.resolve(true, MatchStatus.UNMATCHED, true)).isEqualTo(LinkStatus.CLASSIFIED);
        assertThat(LinkStatusResolver.resolve(true, MatchStatus.MANUAL_UNMATCHED, false)).isEqualTo(LinkStatus.UNLINKED);
        assertThat(LinkStatusResolver.resolve(true, null, false)).isEqualTo(LinkStatus.UNLINKED);
    }

    @Provide
    Arbitrary<MatchStatus> matchStatuses() {
        return Arbitraries.of(MatchStatus.class).injectNull(0.1);
    }

    /** Totality: every input yields exactly one non-null status, consistent with the rule. */
    @Property
    void totality(@ForAll boolean hasProject, @ForAll("matchStatuses") MatchStatus ms, @ForAll boolean cat) {
        LinkStatus result = LinkStatusResolver.resolve(hasProject, ms, cat);
        assertThat(result).isNotNull().isEqualTo(expected(hasProject, ms, cat));
    }
}
