package com.foremen.service.document;

/**
 * FOR-05-08 (Requirements 3.1, 3a.5): the create / versioned-save payload for a
 * {@code DocumentTemplate}.
 *
 * <p>On create the pair ({@code documentTypeId}, {@code locale}) binds the first version; on a
 * versioned save of an existing (type, locale) the service bumps {@link #version} and retains the
 * prior versions ({@code DocumentTemplateService.save}). {@link #body} is the template source body
 * carrying {@code {Token}} merge placeholders (the editable text the merge engine renders); it is
 * stored as the template's {@code storageUri} body text.
 *
 * @param documentTypeId the bound document type id (required)
 * @param name           the template name (required)
 * @param locale         {@code PL} / {@code RU} / {@code BILINGUAL} (required)
 * @param body           the template source body with {@code {Token}} placeholders (required)
 */
public record TemplateSaveInput(
        Long documentTypeId,
        String name,
        String locale,
        String body) {
}
