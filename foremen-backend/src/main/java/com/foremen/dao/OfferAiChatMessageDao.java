package com.foremen.dao;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.foremen.dao.model.OfferAiChatMessageEntity;

/**
 * FOR-05-04 (Requirement 21.3; design B8): DAO of the AI chat history of an offer. The entity is a
 * child of the offer and resolves to a project through {@link #PROJECT_ID_PATH}
 * ({@code getProjectIdPath = "offer.project.id"}).
 */
@Repository
public interface OfferAiChatMessageDao extends AdminDao<OfferAiChatMessageEntity, Long> {

    /** JPA path from the message to its owning project. */
    String PROJECT_ID_PATH = "offer.project.id";

    List<OfferAiChatMessageEntity> findByOfferIdOrderByIdAsc(Long offerId);

    /** The newest message of the offer (pending-action expiry rule). */
    Optional<OfferAiChatMessageEntity> findFirstByOfferIdOrderByIdDesc(Long offerId);

    /** User messages of the offer since {@code since} (rate limit). */
    long countByOfferIdAndRoleAndCreatedDateAfter(Long offerId, String role, java.time.LocalDateTime since);

    /** The latest applied, not yet undone turn of the offer (undo target). */
    Optional<OfferAiChatMessageEntity> findFirstByOfferIdAndVersionAfterIsNotNullAndUndoneAtIsNullOrderByIdDesc(Long offerId);
}
