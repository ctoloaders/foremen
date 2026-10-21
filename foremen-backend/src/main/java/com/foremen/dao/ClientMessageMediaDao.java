package com.foremen.dao;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.ClientMessageMediaEntity;

/**
 * FOR-06-12 (Requirement 4) DAO for {@link ClientMessageMediaEntity}, following the
 * {@code DocumentMediaDao} convention: the generic {@link AdminDao} CRUD surface plus a
 * message-scoped finder. Media is a collection child of its {@code ClientMessage} (owner FK
 * {@code message_id}, {@code ON DELETE CASCADE}) and resolves to a project via
 * {@code message.project.id}; the attachment bytes live in the object store while only metadata +
 * {@code kind} are persisted here (R4.1).
 */
@Repository
public interface ClientMessageMediaDao extends AdminDao<ClientMessageMediaEntity, Long> {

    /**
     * Every media row of a message, backing the thread read model's per-message attachment list and
     * signed-URL resolution (R4.4).
     *
     * @param messageId the owning client-message id
     * @return the message's media rows
     */
    List<ClientMessageMediaEntity> findByMessageId(Long messageId);
}
