package com.foremen.service.signing.merge;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import com.foremen.dao.model.ProjectEntity;

/**
 * FOR-05-08 (Requirement 3.3): resolves the schedule placeholder group — the project's planned
 * start and end dates (design §Components resolver table row {@code ScheduleResolver}).
 *
 * <table>
 *   <caption>Supported tokens</caption>
 *   <tr><th>Token</th><th>Source</th></tr>
 *   <tr><td>{@code {Begindate}}</td><td>{@link ProjectEntity#getStartDate()}</td></tr>
 *   <tr><td>{@code {Closedate}}</td><td>{@link ProjectEntity#getEndDate()}</td></tr>
 * </table>
 *
 * <p>A date resolves to {@code null} (unresolved) when the project is absent or the respective date
 * is not set, so an unplanned project leaves the date placeholders for manual completion rather than
 * fabricating a value (Requirement 3.4, null→unresolved).
 */
@Component
public class ScheduleResolver implements MergeFieldResolver {

    static final String TOKEN_BEGIN_DATE = "Begindate";
    static final String TOKEN_CLOSE_DATE = "Closedate";

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public boolean supports(String token) {
        return TOKEN_BEGIN_DATE.equals(token) || TOKEN_CLOSE_DATE.equals(token);
    }

    @Override
    public String resolve(String token, MergeContext ctx) {
        ProjectEntity project = ctx.project();
        if (project == null) {
            return null;
        }
        return switch (token) {
            case TOKEN_BEGIN_DATE -> formatDate(project.getStartDate());
            case TOKEN_CLOSE_DATE -> formatDate(project.getEndDate());
            default -> null;
        };
    }

    private static String formatDate(LocalDate date) {
        return date == null ? null : date.format(DATE_FORMAT);
    }
}
