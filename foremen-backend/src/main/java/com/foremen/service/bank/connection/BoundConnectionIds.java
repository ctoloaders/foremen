package com.foremen.service.bank.connection;

import com.foremen.dao.model.FeedProvider;
import java.util.Set;

/**
 * FOR-07-04a (Requirement 5.6; design §BoundConnectionIds port): lets a connector learn which provider
 * connection ids are already bound to a Foremen connection, without seeing JPA entities. Used by the
 * Fintable fallback binding when the return URL carries no {@code connection_id}.
 */
public interface BoundConnectionIds {

    /** All {@code externalConnectionId} values bound to a connection of {@code provider}. */
    Set<String> externalConnectionIds(FeedProvider provider);
}
