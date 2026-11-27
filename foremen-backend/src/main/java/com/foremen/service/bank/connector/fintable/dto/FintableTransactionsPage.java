package com.foremen.service.bank.connector.fintable.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * FOR-07-04a: one page of {@code GET /accounts/{id}/transactions} ({@code {data, next_cursor}}).
 *
 * @param data       raw transaction objects (with {@code raw} when {@code include=raw})
 * @param nextCursor Fintable's {@code next_cursor}; {@code null} on the last page
 */
public record FintableTransactionsPage(List<JsonNode> data, String nextCursor) {

    public FintableTransactionsPage {
        data = data == null ? List.of() : List.copyOf(data);
        nextCursor = nextCursor == null || nextCursor.isBlank() ? null : nextCursor;
    }

    public boolean hasNext() {
        return nextCursor != null;
    }
}
