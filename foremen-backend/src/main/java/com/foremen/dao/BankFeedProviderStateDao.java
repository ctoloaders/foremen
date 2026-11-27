package com.foremen.dao;
import org.springframework.stereotype.Repository;

import com.foremen.dao.model.BankFeedProviderStateEntity;
import com.foremen.dao.model.FeedProvider;
/**
 * FOR-07-04a (Requirement 15.1) — DAO for {@link BankFeedProviderStateEntity}, keyed by the
 * {@link FeedProvider} ({@code findById(provider)}).
 */
@Repository
public interface BankFeedProviderStateDao extends AdminDao<BankFeedProviderStateEntity, FeedProvider> {
}
