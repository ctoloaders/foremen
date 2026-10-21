package com.foremen.dao.model;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.foremen.config.persistence.LocaleAttributeConverter;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-06-12 (Requirements 1.1, 1.5): the ClientMessage persistence root — the project-scoped
 * aggregate anchoring a single message in the per-project client&lt;-&gt;staff conversation
 * (design §Data Models / ClientMessageEntity). All of a project's messages form one thread ordered
 * by {@code createdDate}. Maps the {@code client_messages} table (changeset
 * {@code 183-create-client-messages.xml}).
 *
 * <p>Project-scoped on {@code project.id} ({@code getProjectIdPath() = "project.id"}, R8.2). The
 * entity carries <strong>no</strong> {@code nameRU}/{@code namePL} i18n columns — a message is
 * transactional, not a dictionary; its per-locale text lives in the {@link #translations} child rows
 * (the original-locale mirror + best-effort machine translations), and any referenced display name
 * (project, user) uses the existing {@code ru → pl → code} fallback (Requirement 11.1).
 *
 * <p>Its per-locale translation rows ({@link ClientMessageTranslationEntity}) and attachments
 * ({@link ClientMessageMediaEntity}) are cascaded children (cascade ALL + orphan removal); the DB
 * also enforces {@code ON DELETE CASCADE} on both child tables (Requirement 1.6).
 */
@Entity
@Table(name = "client_messages")
@Getter
@Setter
@NoArgsConstructor
public class ClientMessageEntity extends BaseEntity {

    /** Owner FK to the project, NOT NULL — drives the project-scope filter ({@code "project.id"}, R8.2). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    /** Who posted the message, NOT NULL (R1.1). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private UserEntity author;

    /** The {@link AuthorSide} ({@code CLIENT}/{@code STAFF}) derived from the author's project role (R1.1). */
    @Enumerated(EnumType.STRING)
    @Column(name = "author_side", nullable = false, length = 16)
    private AuthorSide authorSide;

    /** The message as the author wrote it, NOT NULL / non-blank (R1.1). */
    @Column(name = "original_text", nullable = false, columnDefinition = "text")
    private String originalText;

    /** The author's message locale (PL/RU), persisted as a lowercase language tag via the shared converter (R1.1, R12). */
    @Convert(converter = LocaleAttributeConverter.class)
    @Column(name = "original_locale", nullable = false, length = 35)
    private Locale originalLocale;

    /**
     * First-read timestamp (first-read-wins, set once by the MANAGER, R5); nullable until read.
     * {@code TIMESTAMP WITH TIME ZONE} → {@link OffsetDateTime}.
     */
    @Column(name = "read_at")
    private OffsetDateTime readAt;

    /** Who first read the message; nullable until read (R5). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "read_by_id")
    private UserEntity readBy;

    /**
     * The per-locale translation rows — the original-locale mirror ({@code machine=false}) plus
     * best-effort machine translations ({@code machine=true}). Cascade ALL + orphan removal; the DB
     * enforces {@code ON DELETE CASCADE} (R1.2, R1.6).
     */
    @OneToMany(mappedBy = "message", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ClientMessageTranslationEntity> translations = new ArrayList<>();

    /**
     * The message's attachments (metadata only; bytes in the object store). Cascade ALL + orphan
     * removal; the DB enforces {@code ON DELETE CASCADE} (R1.3, R1.6).
     */
    @OneToMany(mappedBy = "message", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ClientMessageMediaEntity> media = new ArrayList<>();
}
