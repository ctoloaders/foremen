package com.foremen.dao;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.ClientMessageTranslationEntity;

/**
 * FOR-06-12 (Requirement 1.2, 6.2) — DAO for {@link ClientMessageTranslationEntity}, the per-locale
 * translation child of a {@code ClientMessage}. The generic {@link AdminDao} CRUD surface backs the
 * {@code ClientMessageService}, which persists the original-locale mirror ({@code machine=false})
 * and the best-effort machine translations ({@code machine=true}) at post time. Rows are a cascade
 * child of their message ({@code ON DELETE CASCADE}); this DAO exists to {@code save} them within
 * the post transaction.
 */
@Repository
public interface ClientMessageTranslationDao extends AdminDao<ClientMessageTranslationEntity, Long> {
}
