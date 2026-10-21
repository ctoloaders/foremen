package com.foremen.service.clientmessage;

import java.util.List;

/**
 * FOR-06-12 (Requirements 5.4, 6.4) — the thread read model: the whole per-project client↔staff
 * conversation projected for one reader {@link java.util.Locale}, returned by
 * {@code GET /api/client-messages}.
 *
 * <p>{@link #messages} is the ordered thread (by {@code createdDate} ascending); each
 * {@link ClientMessageDto} already carries its reader-locale text (with the original-text fallback),
 * read state, and the per-media signed download URLs supplied by the service (R6.4).
 *
 * <p>{@link #unreadCount} is the <em>per-reader</em> unread count (R5.4): for a MANAGER reader the
 * number of {@code CLIENT}-side messages not yet read; the service computes it against the reader
 * before building the view. It lets the panel badge the unread total without re-deriving it on the
 * client.
 *
 * @param messages    the ordered thread, each message projected for the reader locale
 * @param unreadCount the per-reader unread message count
 */
public record ThreadView(
        List<ClientMessageDto> messages,
        long unreadCount) {
}
