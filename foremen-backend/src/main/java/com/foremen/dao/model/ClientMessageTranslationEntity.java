package com.foremen.dao.model;

import java.util.Locale;

import com.foremen.config.persistence.LocaleAttributeConverter;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * FOR-06-12 (Requirement 1.2): a per-locale translation row of a {@link ClientMessageEntity} — the
 * child table {@code client_message_translations} (changeset
 * {@code 184-create-client-message-translations.xml}). Each row is one localization of its parent
 * message: the original-locale mirror ({@code machine=false}) or a best-effort machine translation
 * into another supported locale ({@code machine=true}). Unique on {@code (message_id, locale)}.
 *
 * <p>The reader-locale projection selects the matching row for a given {@link Locale}; a locale
 * without a row falls back to the message's {@code originalText} (R6.3).
 */
@Entity
@Table(
        name = "client_message_translations",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_client_message_translations_message_locale",
                columnNames = {"message_id", "locale"}))
@Getter
@Setter
@NoArgsConstructor
public class ClientMessageTranslationEntity extends BaseEntity {

    /** Owner message FK, NOT NULL; translations cannot outlive their message ({@code ON DELETE CASCADE}, R1.6). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private ClientMessageEntity message;

    /** The translation's locale (PL/RU), persisted as a lowercase language tag via the shared converter (R1.2, R12). */
    @Convert(converter = LocaleAttributeConverter.class)
    @Column(name = "locale", nullable = false, length = 35)
    private Locale locale;

    /** The localized message text, NOT NULL (R1.2). */
    @Column(name = "text", nullable = false, columnDefinition = "text")
    private String text;

    /** {@code false} = original-locale mirror, {@code true} = auto-translation (R1.2). */
    @Column(name = "machine", nullable = false)
    private boolean machine;
}
