package com.manas.metaplatform.typesystem.compiler;

import com.manas.metaplatform.typesystem.dsl.FieldDecl;
import com.manas.metaplatform.typesystem.dsl.TypeDecl;

import java.util.ArrayList;
import java.util.List;

/**
 * Lowers a {@link TypeDecl} to a real {@code CREATE TABLE} statement: one physical table per
 * declared type, one typed column per declared field, plus a generated {@code id} primary
 * key. This is the deliberate opposite of the EAV storage the existing SOQL-style DSL uses
 * (see the top-level README's sibling comparison): here, declaring a type produces DDL text,
 * not a metadata row, because the claim this subsystem measures is "a compiler emits the
 * PostgreSQL schema", not "schema evolution is DDL-free".
 */
public final class DdlEmitter {

    public String emitCreateTable(TypeDecl type) {
        String table = NamingConventions.tableName(type.name());
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE ").append(table).append(" (\n");
        sql.append("    id BIGSERIAL PRIMARY KEY,\n");
        List<String> columnLines = new ArrayList<>();
        for (FieldDecl field : type.fields()) {
            columnLines.add("    " + NamingConventions.columnName(field.name()) + " "
                    + sqlType(field) + " NOT NULL");
        }
        sql.append(String.join(",\n", columnLines));
        sql.append("\n);");
        return sql.toString();
    }

    public String emitDropTableIfExists(TypeDecl type) {
        return "DROP TABLE IF EXISTS " + NamingConventions.tableName(type.name()) + ";";
    }

    private String sqlType(FieldDecl field) {
        return switch (field.type()) {
            case STRING -> "VARCHAR(255)";
            case NUMBER -> "DOUBLE PRECISION";
            case BOOLEAN -> "BOOLEAN";
        };
    }
}
