package com.manas.metaplatform.domain;

/**
 * The three field types a tenant can declare for a custom field, each backed by its own
 * typed column on {@link FieldValueEntity} (string_value / number_value / boolean_value).
 * A field's declared type constrains which DSL comparison operators the compiler accepts
 * against it; see {@code compiler.SqlCompiler}.
 */
public enum FieldType {
    STRING,
    NUMBER,
    BOOLEAN
}
