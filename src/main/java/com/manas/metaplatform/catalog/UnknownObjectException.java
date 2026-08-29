package com.manas.metaplatform.catalog;

/**
 * Thrown when a DSL query names an object that does not exist for the querying tenant.
 * This is the guard against the "object name that only exists for another tenant" class
 * of adversarial query: resolution is always scoped by tenant id, so this exception fires
 * cleanly instead of the lookup ever falling through to another tenant's object.
 */
public class UnknownObjectException extends RuntimeException {

    public UnknownObjectException(long tenantId, String objectName) {
        super("no object named '" + objectName + "' for tenant " + tenantId);
    }
}
