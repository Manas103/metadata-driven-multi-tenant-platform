package com.manas.metaplatform.compiler;

/** A compile-time (schema resolution or type) error, as distinct from a parse error. */
public class DslCompileException extends RuntimeException {

    public DslCompileException(String message) {
        super(message);
    }
}
