package com.manas.metaplatform.catalog;

/**
 * Thrown when a DSL query names a field that does not exist on the resolved object
 * definition (which is itself already tenant-scoped by the time this is thrown).
 */
public class UnknownFieldException extends RuntimeException {

    public UnknownFieldException(long objectDefinitionId, String fieldName) {
        super("no field named '" + fieldName + "' on object definition " + objectDefinitionId);
    }
}
