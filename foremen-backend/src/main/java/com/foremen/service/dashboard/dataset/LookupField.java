package com.foremen.service.dashboard.dataset;

import com.foremen.service.dashboard.config.DatasetSchema.FieldType;

/**
 * LOOKUP field: a scalar correlated subquery declared by the dataset, used where the entity keeps a
 * plain id instead of an association (for example {@code ProjectMemberEntity.projectId}).
 * Behaves as a regular {@link FieldDescriptor} once converted with {@link #toDescriptor()}.
 */
public record LookupField(String name, FieldType type, String labelKey, boolean filterable,
                          boolean groupable, FieldExpression expression) {

    public LookupField {
        if (expression == null) {
            throw new IllegalArgumentException("Lookup expression is required: " + name);
        }
    }

    public static LookupField of(String name, FieldType type, FieldExpression expression) {
        return new LookupField(name, type, null, true, true, expression);
    }

    public FieldDescriptor toDescriptor() {
        return FieldDescriptor.computed(name, type, expression)
                .withLabelKey(labelKey)
                .withFlags(true, filterable, groupable, false);
    }
}
