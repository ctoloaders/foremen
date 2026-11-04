package com.foremen.dao;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.BankConnectionEntity;
import com.foremen.dao.model.BankConnectionStatus;
import com.foremen.dao.model.FeedProvider;

/**
 * FOR-07-04 (Requirements 1.1, 2.1) — DAO for {@link BankConnectionEntity}: the generic
 * {@link AdminDao} CRUD + specification surface plus the provider-id lookups used by callbacks and the
 * scheduler.
 */
@Repository
public interface BankConnectionDao extends AdminDao<BankConnectionEntity, Long> {

    /** The connection for a provider connection id (callbacks, reconnect). */
    Optional<BankConnectionEntity> findByProviderAndExternalConnectionId(FeedProvider provider,
                                                                         String externalConnectionId);

    /** Connections of a provider customer (Client customer / Partner lead). */
    List<BankConnectionEntity> findByProviderAndExternalCustomerId(FeedProvider provider, String externalCustomerId);

    /** Connections in the given statuses (scheduler, consent sweep), oldest first. */
    List<BankConnectionEntity> findByStatusInOrderByIdAsc(Collection<BankConnectionStatus> statuses);
}
