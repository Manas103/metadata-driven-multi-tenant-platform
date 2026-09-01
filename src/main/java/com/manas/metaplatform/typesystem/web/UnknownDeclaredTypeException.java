package com.manas.metaplatform.typesystem.web;

/** Thrown when a REST path segment names a type that was never declared in
 * {@code declared-types.dsl}. {@link TypeSystemExceptionHandler} maps this to HTTP 404. */
public class UnknownDeclaredTypeException extends RuntimeException {
    public UnknownDeclaredTypeException(String typeName) {
        super("No declared type named '" + typeName + "'");
    }
}
