package com.manas.metaplatform.typesystem.dsl;

/** Thrown by {@link TypeLexer} or {@link TypeParser} on malformed type-declaration DSL text. */
public class TypeDslSyntaxException extends RuntimeException {
    public TypeDslSyntaxException(String message) {
        super(message);
    }
}
