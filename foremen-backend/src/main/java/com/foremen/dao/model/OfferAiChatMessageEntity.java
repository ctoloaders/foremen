package com.foremen.dao.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-05-04 (Requirement 21.3; design B8): one message of the AI chat attached to an offer. Maps
 * {@code offer_ai_chat_messages} (changeset 273). Child of {@link OfferEntity} (ON DELETE CASCADE),
 * resolves to a project through {@code offer.project.id}. Not a managed CRUD entity (no resource).
 *
 * <p>{@code snapshotBefore} (the offer snapshot as a generic JSON map) and the version pair support
 * the undo of an applied turn.
 */
@Entity
@Table(name = "offer_ai_chat_messages")
@Getter
@Setter
@NoArgsConstructor
public class OfferAiChatMessageEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offer_id", nullable = false)
    private OfferEntity offer;

    @Column(name = "user_id")
    private Long userId;

    /** {@code USER} | {@code ASSISTANT} | {@code SYSTEM}. */
    @Column(name = "role", nullable = false, length = 16)
    private String role;

    @Column(name = "content", columnDefinition = "text")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_calls", columnDefinition = "jsonb")
    private List<Map<String, Object>> toolCalls;

    /** Edits awaiting the user's confirmation (wide edits, Requirement 11.5). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pending_action", columnDefinition = "jsonb")
    private Map<String, Object> pendingAction;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot_before", columnDefinition = "jsonb")
    private Map<String, Object> snapshotBefore;

    @Column(name = "version_before")
    private Integer versionBefore;

    @Column(name = "version_after")
    private Integer versionAfter;

    @Column(name = "undone_at")
    private LocalDateTime undoneAt;
}
