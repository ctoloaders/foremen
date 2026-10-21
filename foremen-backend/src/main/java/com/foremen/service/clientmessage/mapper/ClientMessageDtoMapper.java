package com.foremen.service.clientmessage.mapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.foremen.config.persistence.LocaleAttributeConverter;
import com.foremen.dao.model.AuthorSide;
import com.foremen.dao.model.ClientMessageEntity;
import com.foremen.dao.model.ClientMessageMediaEntity;
import com.foremen.dao.model.ClientMessageTranslationEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.clientmessage.ClientMessageDto;
import com.foremen.service.clientmessage.ClientMessageMediaSummaryDto;
import com.foremen.service.clientmessage.ThreadView;

/**
 * FOR-06-12 (Requirements 5.4, 6.4, 4.4; design §"Request flow — read the thread") — the read mapper
 * projecting the {@link ClientMessageEntity} graph into the client↔staff conversation read models
 * ({@link ClientMessageDto} / {@link ThreadView}).
 *
 * <p><b>Hand-written (not MapStruct) by design.</b> The projection needs two service-supplied inputs
 * that are not a plain field copy: the <em>reader locale</em> (used to select the matching
 * translation row with an original-text fallback, R6.4) and the <em>per-media signed download
 * URLs</em> (resolved by the service via {@code ImageStorage.signedDownloadUrl(...)} — the mapper
 * must <strong>not</strong> call storage directly, per the task/design). It therefore takes those as
 * explicit arguments and stays pure.
 *
 * <p><b>Reader-locale text selection (R6.4).</b> {@link #toDto} selects the translation row whose
 * locale language matches the reader locale (PL/RU); when none exists (the seam left it
 * untranslated, R6.3) it falls back to the message's {@code originalText}. {@code originalText} +
 * {@code originalLocale} are always carried for the "show original" quick-view (R6.5).
 *
 * <p><b>Thread + unread count (R5.4).</b> {@link #toThreadView} orders the messages by
 * {@code createdDate} ascending and carries the service-computed per-reader unread count verbatim
 * (the unread derivation — MANAGER-only, {@code CLIENT}-side, {@code readAt == null} — is a service
 * concern, R5).
 */
@Component
public class ClientMessageDtoMapper {

    /**
     * Build the thread read model for a reader locale from the ordered message entities, each
     * message projected with its reader-locale text and the service-supplied signed URLs.
     *
     * @param messages      the project's messages (any order; re-ordered here by {@code createdDate})
     * @param readerLocale  the reader's locale used to select each message's text (PL fallback when
     *                      {@code null}/unsupported)
     * @param signedUrls    media id → resolved signed download URL, supplied by the service; may be
     *                      empty (then every {@code downloadUrl} is {@code null})
     * @param unreadCount   the per-reader unread count computed by the service
     * @return the ordered, reader-localized thread view
     */
    public ThreadView toThreadView(List<ClientMessageEntity> messages,
                                   Locale readerLocale,
                                   Map<Long, String> signedUrls,
                                   long unreadCount) {
        Map<Long, String> urls = signedUrls == null ? Collections.emptyMap() : signedUrls;
        List<ClientMessageDto> dtos = new ArrayList<>();
        if (messages != null) {
            messages.stream()
                    .sorted(Comparator.comparing(ClientMessageEntity::getCreatedDate,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .forEach(message -> dtos.add(toDto(message, readerLocale, urls)));
        }
        return new ThreadView(dtos, unreadCount);
    }

    /**
     * Project one message for the reader locale, resolving its reader-locale text (translation row
     * for the reader locale, else {@code originalText}), its read state, and its media summaries
     * (each with the service-supplied signed URL, or {@code null} when none was resolved).
     *
     * @param message      the message entity (with its translations + media loaded)
     * @param readerLocale the reader's locale used to select the text (PL fallback when
     *                     {@code null}/unsupported)
     * @param signedUrls   media id → resolved signed download URL, supplied by the service
     * @return the reader-localized message DTO
     */
    public ClientMessageDto toDto(ClientMessageEntity message,
                                  Locale readerLocale,
                                  Map<Long, String> signedUrls) {
        Map<Long, String> urls = signedUrls == null ? Collections.emptyMap() : signedUrls;

        UserEntity author = message.getAuthor();
        UserEntity readBy = message.getReadBy();
        AuthorSide authorSide = message.getAuthorSide();

        return new ClientMessageDto(
                message.getId(),
                authorSide,
                author == null ? null : author.getId(),
                author == null ? null : author.getName(),
                message.getCreatedDate(),
                resolveReaderText(message, readerLocale),
                message.getOriginalText(),
                message.getOriginalLocale(),
                message.getReadAt(),
                readBy == null ? null : readBy.getId(),
                readBy == null ? null : readBy.getName(),
                toMediaSummaries(message, urls));
    }

    /**
     * Select the message's text for the reader locale: the {@link ClientMessageTranslationEntity}
     * whose locale language matches the reader locale (PL fallback when {@code null}/unsupported),
     * else the message's {@code originalText} (R6.3, R6.4).
     */
    private static String resolveReaderText(ClientMessageEntity message, Locale readerLocale) {
        Locale target = LocaleAttributeConverter.isSupported(readerLocale)
                ? readerLocale
                : LocaleAttributeConverter.FALLBACK;
        String targetLanguage = target.getLanguage();

        List<ClientMessageTranslationEntity> translations = message.getTranslations();
        if (translations != null) {
            for (ClientMessageTranslationEntity translation : translations) {
                Locale locale = translation.getLocale();
                if (locale != null && locale.getLanguage().equals(targetLanguage)) {
                    return translation.getText();
                }
            }
        }
        return message.getOriginalText();
    }

    /**
     * Map the message's attachments to summaries, attaching the service-supplied signed download URL
     * per media id (R4.4). A media id absent from {@code signedUrls} yields a {@code null} URL.
     */
    private static List<ClientMessageMediaSummaryDto> toMediaSummaries(ClientMessageEntity message,
                                                                       Map<Long, String> signedUrls) {
        List<ClientMessageMediaSummaryDto> summaries = new ArrayList<>();
        List<ClientMessageMediaEntity> media = message.getMedia();
        if (media != null) {
            for (ClientMessageMediaEntity entity : media) {
                summaries.add(new ClientMessageMediaSummaryDto(
                        entity.getId(),
                        entity.getFileName(),
                        entity.getContentType(),
                        entity.getSizeBytes(),
                        entity.getKind(),
                        signedUrls.get(entity.getId())));
            }
        }
        return summaries;
    }
}
