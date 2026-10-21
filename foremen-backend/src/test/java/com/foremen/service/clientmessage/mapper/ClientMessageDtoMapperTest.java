package com.foremen.service.clientmessage.mapper;

import static com.foremen.config.persistence.LocaleAttributeConverter.PL;
import static com.foremen.config.persistence.LocaleAttributeConverter.RU;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.foremen.dao.model.AuthorSide;
import com.foremen.dao.model.ClientMessageEntity;
import com.foremen.dao.model.ClientMessageMediaEntity;
import com.foremen.dao.model.ClientMessageTranslationEntity;
import com.foremen.dao.model.MediaKind;
import com.foremen.dao.model.UserEntity;
import com.foremen.service.clientmessage.ClientMessageDto;
import com.foremen.service.clientmessage.ClientMessageMediaSummaryDto;
import com.foremen.service.clientmessage.ThreadView;

/**
 * FOR-06-12 task 10.1 (Requirements 6.4, 5.4, 4.4) — unit tests for the pure
 * {@link ClientMessageDtoMapper} read projection.
 *
 * <p>Covers:
 * <ul>
 *   <li><b>per-reader-locale text (R6.4)</b> — selects the translation row whose locale matches the
 *       reader; falls back to {@code originalText} when none exists; carries {@code originalText} +
 *       {@code originalLocale} always; PL fallback for a {@code null}/unsupported reader locale;</li>
 *   <li><b>ThreadView (R5.4)</b> — orders messages by {@code createdDate} ascending and carries the
 *       service-supplied unread count verbatim;</li>
 *   <li><b>media summaries (R4.4)</b> — carry the supplied signed URL per media id; a media id with
 *       no supplied URL gets a {@code null} download URL.</li>
 * </ul>
 */
class ClientMessageDtoMapperTest {

    private final ClientMessageDtoMapper mapper = new ClientMessageDtoMapper();

    // =======================================================================
    // per-reader-locale text (R6.4)
    // =======================================================================

    @Nested
    @DisplayName("reader-locale text selection (R6.4)")
    class ReaderLocaleText {

        @Test
        @DisplayName("selects the translation row matching the reader locale")
        void selectsMatchingTranslation() {
            ClientMessageEntity msg = message(1L, "dzień dobry", PL,
                    translation(PL, "dzień dobry", false),
                    translation(RU, "добрый день", true));

            ClientMessageDto plDto = mapper.toDto(msg, PL, Map.of());
            ClientMessageDto ruDto = mapper.toDto(msg, RU, Map.of());

            assertThat(plDto.text()).isEqualTo("dzień dobry");
            assertThat(ruDto.text()).isEqualTo("добрый день");
        }

        @Test
        @DisplayName("falls back to originalText when no row exists for the reader locale (R6.3)")
        void fallsBackToOriginalText() {
            // original PL only; no RU machine row (seam unconfigured / failed).
            ClientMessageEntity msg = message(1L, "dzień dobry", PL,
                    translation(PL, "dzień dobry", false));

            ClientMessageDto ruDto = mapper.toDto(msg, RU, Map.of());

            assertThat(ruDto.text()).isEqualTo("dzień dobry"); // original-only
        }

        @Test
        @DisplayName("always carries originalText + originalLocale for the show-original quick-view")
        void carriesOriginal() {
            ClientMessageEntity msg = message(1L, "привет", RU,
                    translation(RU, "привет", false),
                    translation(PL, "cześć", true));

            ClientMessageDto plDto = mapper.toDto(msg, PL, Map.of());

            assertThat(plDto.text()).isEqualTo("cześć");          // reader locale PL
            assertThat(plDto.originalText()).isEqualTo("привет");  // original always carried
            assertThat(plDto.originalLocale()).isEqualTo(RU);
        }

        @Test
        @DisplayName("a null reader locale resolves to PL")
        void nullReaderLocaleIsPl() {
            ClientMessageEntity msg = message(1L, "привет", RU,
                    translation(RU, "привет", false),
                    translation(PL, "cześć", true));

            ClientMessageDto dto = mapper.toDto(msg, null, Map.of());

            assertThat(dto.text()).isEqualTo("cześć"); // PL fallback
        }

        @Test
        @DisplayName("an unsupported reader locale resolves to PL")
        void unsupportedReaderLocaleIsPl() {
            ClientMessageEntity msg = message(1L, "привет", RU,
                    translation(RU, "привет", false),
                    translation(PL, "cześć", true));

            ClientMessageDto dto = mapper.toDto(msg, Locale.ENGLISH, Map.of());

            assertThat(dto.text()).isEqualTo("cześć"); // PL fallback
        }
    }

    // =======================================================================
    // ThreadView (R5.4)
    // =======================================================================

    @Nested
    @DisplayName("ThreadView — ordering + unread count (R5.4)")
    class ThreadViewProjection {

        @Test
        @DisplayName("orders messages by createdDate ascending")
        void ordersByCreatedDate() {
            ClientMessageEntity older = message(1L, "first", PL, translation(PL, "first", false));
            older.setCreatedDate(LocalDateTime.of(2026, 1, 1, 10, 0));
            ClientMessageEntity newer = message(2L, "second", PL, translation(PL, "second", false));
            newer.setCreatedDate(LocalDateTime.of(2026, 1, 2, 10, 0));

            // supply out of order; mapper re-orders ascending.
            ThreadView view = mapper.toThreadView(List.of(newer, older), PL, Map.of(), 0L);

            assertThat(view.messages()).extracting(ClientMessageDto::id).containsExactly(1L, 2L);
        }

        @Test
        @DisplayName("carries the service-supplied unread count verbatim")
        void carriesUnreadCount() {
            ClientMessageEntity msg = message(1L, "text", PL, translation(PL, "text", false));

            ThreadView view = mapper.toThreadView(List.of(msg), PL, Map.of(), 3L);

            assertThat(view.unreadCount()).isEqualTo(3L);
        }

        @Test
        @DisplayName("a null message list yields an empty thread")
        void nullMessagesEmptyThread() {
            ThreadView view = mapper.toThreadView(null, PL, Map.of(), 0L);
            assertThat(view.messages()).isEmpty();
        }
    }

    // =======================================================================
    // media summaries (R4.4)
    // =======================================================================

    @Nested
    @DisplayName("media summaries — signed URL per media id (R4.4)")
    class MediaSummaries {

        @Test
        @DisplayName("carries the supplied signed URL for a matching media id")
        void carriesSuppliedUrl() {
            ClientMessageEntity msg = message(1L, "text", PL, translation(PL, "text", false));
            msg.getMedia().add(media(50L, "photo.png", "image/png", 1234, MediaKind.IMAGE));

            ClientMessageDto dto = mapper.toDto(msg, PL, Map.of(50L, "https://signed/photo"));

            ClientMessageMediaSummaryDto summary = dto.media().get(0);
            assertThat(summary.id()).isEqualTo(50L);
            assertThat(summary.fileName()).isEqualTo("photo.png");
            assertThat(summary.contentType()).isEqualTo("image/png");
            assertThat(summary.sizeBytes()).isEqualTo(1234L);
            assertThat(summary.kind()).isEqualTo(MediaKind.IMAGE);
            assertThat(summary.downloadUrl()).isEqualTo("https://signed/photo");
        }

        @Test
        @DisplayName("a media id absent from the supplied map gets a null download URL")
        void absentUrlIsNull() {
            ClientMessageEntity msg = message(1L, "text", PL, translation(PL, "text", false));
            msg.getMedia().add(media(60L, "doc.pdf", "application/pdf", 10, MediaKind.DOCUMENT));

            ClientMessageDto dto = mapper.toDto(msg, PL, Map.of()); // no URL supplied

            assertThat(dto.media().get(0).downloadUrl()).isNull();
        }
    }

    // =======================================================================
    // helpers
    // =======================================================================

    private static ClientMessageEntity message(Long id, String originalText, Locale originalLocale,
                                               ClientMessageTranslationEntity... translations) {
        ClientMessageEntity m = new ClientMessageEntity();
        m.setId(id);
        m.setAuthorSide(AuthorSide.CLIENT);
        m.setOriginalText(originalText);
        m.setOriginalLocale(originalLocale);
        UserEntity author = new UserEntity();
        author.setId(10L);
        author.setName("Author");
        m.setAuthor(author);
        m.getTranslations().addAll(List.of(translations));
        return m;
    }

    private static ClientMessageTranslationEntity translation(Locale locale, String text, boolean machine) {
        ClientMessageTranslationEntity t = new ClientMessageTranslationEntity();
        t.setLocale(locale);
        t.setText(text);
        t.setMachine(machine);
        return t;
    }

    private static ClientMessageMediaEntity media(Long id, String fileName, String contentType,
                                                  long sizeBytes, MediaKind kind) {
        ClientMessageMediaEntity media = new ClientMessageMediaEntity();
        media.setId(id);
        media.setFileName(fileName);
        media.setContentType(contentType);
        media.setSizeBytes(sizeBytes);
        media.setKind(kind);
        return media;
    }
}
