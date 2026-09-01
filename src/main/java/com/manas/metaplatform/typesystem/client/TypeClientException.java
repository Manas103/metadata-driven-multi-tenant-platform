package com.manas.metaplatform.typesystem.client;

/** Thrown by {@link TypeClientSupport} when the REST surface returns a non-2xx response. */
public class TypeClientException extends RuntimeException {
    public TypeClientException(String message) {
        super(message);
    }
}
