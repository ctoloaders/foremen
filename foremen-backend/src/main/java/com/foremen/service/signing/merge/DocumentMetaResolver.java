package com.foremen.service.signing.merge;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.SignableDocumentEntity;

/**
 * FOR-05-08 (Requirement 3.3): resolves the document-meta placeholder group — the document's own id
 * and creation date (design §Components resolver table row {@code DocumentMetaResolver}).
 *
 * <table>
 *   <caption>Supported tokens</caption>
 *   <tr><th>Token</th><th>Source</th></tr>
 *   <tr><td>{@code {Id}}</td><td>{@link SignableDocumentEntity#getId()} — the document's human id/number</td></tr>
 *   <tr><td>{@code {DocumentCreateTime}}</td><td>{@link SignableDocumentEntity#getCreatedDate()}</td></tr>
 * </table>
 *
 * <p>Each token resolves to {@code null} (unresolved) when its datum is absent — a brand-new,
 * not-yet-persisted document has a {@code null} id, and the audited creation date is {@code null}
 * until the row is first flushed (Requirement 3.4, null→unresolved).
 */
@Component
public class DocumentMetaResolver implements MergeFieldResolver {

    static final String TOKEN_ID = "Id";
    static final String TOKEN_CREATE_TIME = "DocumentCreateTime";

    /** ISO-like, locale-independent date rendering for the creation date. */
    private static final DateTimeFormatter CREATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public boolean supports(String token) {
        return TOKEN_ID.equals(token) || TOKEN_CREATE_TIME.equals(token);
    }

    @Override
    public String resolve(String token, MergeContext ctx) {
        SignableDocumentEntity document = ctx.document();
        return switch (token) {
            case TOKEN_ID -> document.getId() == null ? null : String.valueOf(document.getId());
            case TOKEN_CREATE_TIME -> formatCreateTime(document.getCreatedDate());
            default -> null;
        };
    }

    private static String formatCreateTime(LocalDateTime createdDate) {
        return createdDate == null ? null : createdDate.format(CREATE_TIME_FORMAT);
    }
}
