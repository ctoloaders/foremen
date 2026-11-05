package com.foremen.service.estimate;

/**
 * The scope of a LABOUR-price override adjustment (FOR-05-05 bug-report Round 8 B): which estimate
 * lines a signed percentage adjustment writes its effective {@code unit_price_override} for.
 *
 * <ul>
 *   <li>{@link #PROJECT} — every line of the project's estimate (the target id is ignored).</li>
 *   <li>{@link #WORK_CATEGORY} — only lines whose work item belongs to the work category identified
 *       by the target id.</li>
 *   <li>{@link #WORK_ITEM} — only the single line for the work item identified by the target id.</li>
 * </ul>
 */
public enum LabourPriceAdjustmentScope {
    PROJECT,
    WORK_CATEGORY,
    WORK_ITEM
}
