package com.foremen.service.model;

import java.time.OffsetDateTime;
import java.util.Locale;

import com.foremen.dao.model.AuthorSide;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FOR-06-12 (design §ClientMessageService) — read-path service model for a {@code ClientMessage},
 * satisfying the {@code ProjectScopedService}/{@code AdminService} CRUD contract
 * {@code ClientMessageService} implements (entity-creation-rules step 4).
 *
 * <p>The Komunikacja tab does <strong>not</strong> consume the generic {@code AdminService}
 * list/read projection — it reads the richer {@code ThreadView} (per-reader-locale text + read
 * state + unread count + signed media URLs) built by {@code ClientMessageService.listThread}. This
 * model exists only to complete the generic CRUD contract so the inherited by-id read surface
 * compiles and stays project-scoped. It is a flat projection of the entity carrying the FK ids, the
 * server-derived {@code authorSide}, and the stored scalars.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClientMessageServiceModel {

    private Long id;

    private Long projectId;

    private Long authorId;

    /** {@code CLIENT} / {@code STAFF}, derived from the author's project role; never user-supplied (R2.1). */
    private AuthorSide authorSide;

    private String originalText;

    private Locale originalLocale;

    /** First-read timestamp (first-read-wins), or {@code null} until a MANAGER reads it (R5). */
    private OffsetDateTime readAt;

    private Long readById;
}
