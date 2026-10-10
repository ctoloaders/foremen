package com.foremen.service.readiness;

/**
 * The superinterface each design-stage area implements once to expose its own project readiness
 * (FOR-05-11 Requirement 1, Decision D1).
 *
 * <p>The aggregator ({@code ProjectReadinessService}) discovers every bean implementing this
 * interface, calls {@link #contribute(Long)} on each one the caller is permitted to READ (per
 * {@link #areaResource()}), orders the results by {@link #order()}, and composes them into the
 * aggregated readiness view. The aggregator never re-derives an area's completion from raw
 * entities — each area owns its own completion logic and percentage formula.
 *
 * <p>Implementations MUST be side-effect free: computing a contribution reads only and changes no
 * data (Requirement 1.6).
 */
public interface ProjectReadinessContributor {

    /**
     * The stable, lowercase area key — {@code rooms}, {@code estimate}, {@code offer},
     * {@code documents}, {@code team}, or {@code schedule} (Requirement 7.2). Equal to the
     * {@link AreaContribution#key()} this contributor returns.
     *
     * @return the stable area key
     */
    String areaKey();

    /**
     * The ABAC resource that governs READ of this area's data ({@code ROOMS}, {@code ESTIMATE},
     * {@code OFFERS}, {@code SIGNABLE_DOCUMENTS}, {@code PROJECT_MEMBERS}, {@code WORK_SCHEDULE}).
     * The aggregator includes this contribution only when the caller holds READ on this resource
     * (Requirement 6.1).
     *
     * @return the area's ABAC resource code
     */
    String areaResource();

    /**
     * The declared order position (lower first) that defines the stable area order (Requirement 7),
     * independent of Spring bean discovery order.
     *
     * @return the declared order position
     */
    int order();

    /**
     * Compute this area's readiness for the given project. Read-only (Requirement 1.6). The returned
     * {@link AreaContribution} MUST be well-formed: {@code percentage ∈ [0, 100]}, a valid
     * {@link ReadinessState}, and empty {@code items} iff {@code state == DONE} and
     * {@code percentage == 100} (Requirement 1.2, 1.3). Called only when the caller holds
     * {@link #areaResource()} READ.
     *
     * @param projectId the project whose readiness to compute
     * @return the area's contribution
     */
    AreaContribution contribute(Long projectId);
}
