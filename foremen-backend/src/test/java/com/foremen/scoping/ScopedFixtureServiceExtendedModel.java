package com.foremen.scoping;

import lombok.Getter;
import lombok.Setter;

/** Test-only extended service model for {@link ScopedFixtureEntity}. */
@Getter
@Setter
public class ScopedFixtureServiceExtendedModel {

    private Long id;
    private String label;
    private Long projectId;
}
