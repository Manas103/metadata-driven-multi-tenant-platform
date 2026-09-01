package com.manas.metaplatform.typesystem.web;

/** Thrown when a declared type is known but the requested row id does not exist in its
 * table. {@link TypeSystemExceptionHandler} maps this to HTTP 404. */
public class RecordNotFoundException extends RuntimeException {
    public RecordNotFoundException(String typeName, long id) {
        super("No " + typeName + " record with id " + id);
    }
}
