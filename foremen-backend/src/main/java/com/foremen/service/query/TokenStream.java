package com.foremen.service.query;

import java.util.List;

public class TokenStream {
    private final List<QueryToken> tokens;
    private int position = 0;

    public TokenStream(List<QueryToken> tokens) {
        this.tokens = tokens;
    }

    public boolean hasMore() {
        return position < tokens.size();
    }

    public QueryToken peek() {
        return tokens.get(position);
    }

    public QueryToken consume() {
        return tokens.get(position++);
    }

    public int position() {
        return position;
    }
}
