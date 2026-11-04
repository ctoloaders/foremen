package com.foremen.service.bank.connection;

import java.util.Optional;

import com.foremen.dao.model.SyncTrigger;

/**
 * FOR-07-04 (Requirements 1.4, 5.1) — seam between the connection lifecycle and the sync orchestrator.
 *
 * <p>{@link BankConnectionService} requests a {@link SyncTrigger#CONNECT} run after a successful
 * authorization and a {@link SyncTrigger#MANUAL} run on "Sync now" through this interface; the
 * implementation is the {@code BankSyncOrchestrator} (task 6.4). It is looked up lazily
 * ({@code ObjectProvider}), so the connection service works (without triggering syncs) while no
 * implementation is present.
 *
 * <p>Implementations must be safe to call inside the caller's transaction: they should insert the
 * {@code QUEUED} run (or coalesce into the active one) and submit the asynchronous work only after
 * commit.
 */
public interface BankSyncTrigger {

    /**
     * Requests a sync run for a connection.
     *
     * @param connectionId the connection id
     * @param trigger      why the run is requested
     * @param requestedBy  the requesting user name ({@code SYSTEM} for background work)
     * @return the id of the queued run, or of the active run the request was coalesced into; empty
     *         when no run was created
     */
    Optional<Long> enqueue(Long connectionId, SyncTrigger trigger, String requestedBy);
}
