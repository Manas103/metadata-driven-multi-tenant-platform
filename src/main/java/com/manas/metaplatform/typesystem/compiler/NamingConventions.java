package com.manas.metaplatform.typesystem.compiler;

/** Name mapping shared by every emitter: the DSL's camelCase type and field names into a
 * SQL table name, a SQL column name, a Java identifier, and a TypeScript identifier. Kept in
 * one place so the DDL, the Java client and the TypeScript client can never disagree about
 * what a given declared name becomes in each target language. */
public final class NamingConventions {

    private NamingConventions() {
    }

    /** {@code WorkOrder} -> {@code work_order_records}. */
    public static String tableName(String typeName) {
        return toSnakeCase(typeName) + "_records";
    }

    /** {@code estimatedHours} -> {@code estimated_hours}. */
    public static String columnName(String fieldName) {
        return toSnakeCase(fieldName);
    }

    private static String toSnakeCase(String camel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
