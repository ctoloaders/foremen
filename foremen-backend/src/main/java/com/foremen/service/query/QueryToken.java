package com.foremen.service.query;

public sealed interface QueryToken {

    record Filter(String field, QueryOperator operator, String value) implements QueryToken {}

    record And() implements QueryToken {}

    record Or() implements QueryToken {}

    record OpenParen() implements QueryToken {}

    record CloseParen() implements QueryToken {}
}
