package com.manas.metaplatform.typesystem.web;

import com.manas.metaplatform.typesystem.dsl.TypeDecl;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parsed type declarations, held in memory for the lifetime of the application context.
 * Populated once, at startup, by {@link TypeSystemBootstrap}; {@link GenericTypeController}
 * reads it on every request to resolve a URL path segment ("Asset") to the {@link TypeDecl}
 * that names its table and columns. This is the runtime analog of {@code catalog.SchemaCatalog}
 * in the existing SOQL-style subsystem, but keyed by physical table rather than by tenant.
 */
@Component
public final class TypeRegistry {

    private final Map<String, TypeDecl> byName = new LinkedHashMap<>();

    public void register(List<TypeDecl> types) {
        byName.clear();
        for (TypeDecl type : types) {
            byName.put(type.name(), type);
        }
    }

    public TypeDecl require(String typeName) {
        TypeDecl decl = byName.get(typeName);
        if (decl == null) {
            throw new UnknownDeclaredTypeException(typeName);
        }
        return decl;
    }

    public List<TypeDecl> all() {
        return List.copyOf(byName.values());
    }

    public int size() {
        return byName.size();
    }
}
