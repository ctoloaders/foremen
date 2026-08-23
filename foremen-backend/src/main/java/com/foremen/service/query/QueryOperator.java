package com.foremen.service.query;

public enum QueryOperator {
    // Equality
    EQUALS("=="),
    NOT_EQUALS("!="),

    // Text search (case-insensitive)
    CONTAINS("~ct~"),
    STARTS_WITH("~sw~"),
    ENDS_WITH("~ew~"),

    // Text search (case-sensitive)
    CONTAINS_CS("~CT~"),
    STARTS_WITH_CS("~SW~"),
    ENDS_WITH_CS("~EW~"),

    // Legacy LIKE — alias for CONTAINS (case-insensitive). Backward compatible.
    LIKE("~~"),

    // Numeric comparison
    GREATER_THAN(">"),
    LESS_THAN("<"),
    GREATER_THAN_OR_EQUAL(">="),
    LESS_THAN_OR_EQUAL("<="),

    // RSQL-style aliases for comparison operators (backward compatibility)
    GTE("=gte="),
    LTE("=lte="),
    GT("=gt="),
    LT("=lt="),

    // Date comparison
    GT_DATE(">date"),
    LT_DATE("<date"),

    // Set membership
    IN("~in~"),
    NOT_IN("~notin~"),

    // Null checks
    NULL("~null~"),
    NOT_NULL("~notnull~");

    private final String symbol;

    QueryOperator(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }

    /**
     * Ordered list of operator symbols for tokenizer matching.
     * Longest symbols first to avoid partial matches (e.g., ">=" before ">").
     */
    public static final java.util.List<QueryOperator> ORDERED_FOR_MATCHING = java.util.List.of(
            NOT_NULL, NOT_IN, NULL, IN,                             // tilde-wrapped (longest first)
            CONTAINS, STARTS_WITH, ENDS_WITH,                      // case-insensitive text
            CONTAINS_CS, STARTS_WITH_CS, ENDS_WITH_CS,             // case-sensitive text
            GTE, LTE, GT, LT,                                     // RSQL-style (before >= <= > < to match longer first)
            GT_DATE, LT_DATE,                                      // date (before > and <)
            GREATER_THAN_OR_EQUAL, LESS_THAN_OR_EQUAL,             // >= and <= before > and <
            GREATER_THAN, LESS_THAN,                               // numeric
            EQUALS, NOT_EQUALS,                                    // == and != before single =
            LIKE                                                   // ~~ last (legacy alias)
    );
}
