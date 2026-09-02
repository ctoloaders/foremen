package com.foremen.scoping;

import lombok.Getter;
import lombok.Setter;

/** Test-only service model for {@link ScopedFixtureEntity}. */
@Getter
@Setter
public class ScopedFixtureServiceModel {

    private Long id;
    private String label;
    private Long projectId;
}
