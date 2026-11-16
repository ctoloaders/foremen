package com.foremen.service;

/**
 * FOR-11-06 (R5.6): published when a project becomes {@code ACTIVE} (dedicated activation, or the
 * contract-signed signal). The estimate sync listens to it after commit; it never blocks activation.
 */
public record ProjectActivatedEvent(Long projectId) {
}
