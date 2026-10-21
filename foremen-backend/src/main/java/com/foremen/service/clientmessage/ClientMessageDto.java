package com.foremen.service.clientmessage;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

import com.foremen.dao.model.AuthorSide;

/**
 * FOR-06-12 (Requirements 5.4, 6.4) — one {@code ClientMessage} projected for a given reader
 * {@link Locale}, served inside {@link ThreadView#messages()}.
 *
 * <p><b>Per-reader-locale text (R6.4).</b> {@link #text} is the message rendered in the reader's
 * locale: the {@code ClientMessageTranslation} row whose locale matches the reader is selected,
 * falling back to {@link #originalText} when no row exists for that locale (the seam left it
 * untranslated, R6.3). {@link #originalText} + {@link #originalLocale} are always carried so the UI
 * can offer a "show original" quick-view; when the seam is unconfigured the reader sees the original
 * text with its locale labeled (R6.5).
 *
 * <p><b>Read state (R5.4).</b> {@link #readAt} + {@link #readBy*} expose the first-read-wins state:
 * {@code null} until a MANAGER marks a {@code CLIENT}-side message read.
 *
 * <p>{@link #authorSide} is an enum localized on the frontend; the author ref/name let the UI show
 * who posted without cross-referencing the project-members list.
 *
 * @param id             the message id
 * @param authorSide     {@code CLIENT} / {@code STAFF}, derived from the author's project role
 * @param authorId       the author user id
 * @param authorName     the author's display name
 * @param createdDate    the post timestamp (thread order key)
 * @param text           the reader-locale text (translation row for the reader locale, else
 *                       {@link #originalText})
 * @param originalText   the message exactly as the author wrote it
 * @param originalLocale the author's original locale
 * @param readAt         first-read timestamp ({@code TIMESTAMPTZ}), or {@code null} until read
 * @param readById       who first read the message, or {@code null} until read
 * @param readByName     the reader's display name, or {@code null} until read
 * @param media          the attachment summaries (each with its service-supplied signed URL)
 */
public record ClientMessageDto(
        Long id,
        AuthorSide authorSide,
        Long authorId,
        String authorName,
        LocalDateTime createdDate,
        String text,
        String originalText,
        Locale originalLocale,
        OffsetDateTime readAt,
        Long readById,
        String readByName,
        List<ClientMessageMediaSummaryDto> media) {
}
