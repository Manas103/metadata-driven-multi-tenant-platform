package com.manas.metaplatform.typesystem.compiler;

import com.manas.metaplatform.typesystem.dsl.TypeDecl;
import com.manas.metaplatform.typesystem.dsl.TypeParser;

import java.util.List;

/**
 * The compiler's front door: parses type-declaration DSL text into {@link TypeDecl}s and
 * hands out the two emitters ({@link DdlEmitter}, and separately {@link JavaClientEmitter} /
 * {@link TypeScriptClientEmitter} for the generated clients). Kept deliberately thin;
 * {@code web.TypeSystemBootstrap} is what actually executes the emitted DDL against a
 * database and registers the parsed types for the REST surface, and
 * {@code codegen.GenerateClients} is what writes the emitted client source to disk. This
 * class only does the one thing every one of those needs in common: turning DSL text into
 * {@link TypeDecl}s.
 */
public final class TypePlatformCompiler {

    private final DdlEmitter ddlEmitter = new DdlEmitter();
    private final JavaClientEmitter javaClientEmitter = new JavaClientEmitter();
    private final TypeScriptClientEmitter typeScriptClientEmitter = new TypeScriptClientEmitter();

    public List<TypeDecl> parse(String dslSource) {
        return TypeParser.parse(dslSource);
    }

    public DdlEmitter ddlEmitter() {
        return ddlEmitter;
    }

    public JavaClientEmitter javaClientEmitter() {
        return javaClientEmitter;
    }

    public TypeScriptClientEmitter typeScriptClientEmitter() {
        return typeScriptClientEmitter;
    }
}
