package com.manas.metaplatform.typesystem.dsl;

import com.manas.metaplatform.domain.FieldType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Recursive-descent parser for the type-declaration DSL, in the same hand-rolled style as
 * {@code dsl.Parser} for the query language. Grammar:
 *
 * <pre>
 * program    := typeDecl*
 * typeDecl   := 'type' IDENTIFIER '{' fieldDecl* '}'
 * fieldDecl  := IDENTIFIER ':' fieldType
 * fieldType  := 'STRING' | 'NUMBER' | 'BOOLEAN'
 * </pre>
 *
 * <p>Every type name and, within a type, every field name must be unique; both are checked
 * here rather than left for a later stage to discover, so a malformed declaration fails at
 * parse time with a precise message instead of surfacing as a confusing DDL or codegen error.
 */
public final class TypeParser {

    private final List<TypeToken> tokens;
    private int pos;

    public TypeParser(List<TypeToken> tokens) {
        this.tokens = tokens;
    }

    public static List<TypeDecl> parse(String source) {
        return new TypeParser(new TypeLexer(source).tokenize()).parseProgram();
    }

    public List<TypeDecl> parseProgram() {
        List<TypeDecl> decls = new ArrayList<>();
        Set<String> seenTypeNames = new LinkedHashSet<>();
        while (!check(TypeTokenType.EOF)) {
            TypeDecl decl = parseTypeDecl();
            if (!seenTypeNames.add(decl.name())) {
                throw new TypeDslSyntaxException("Duplicate type declaration: " + decl.name());
            }
            decls.add(decl);
        }
        return decls;
    }

    private TypeDecl parseTypeDecl() {
        expect(TypeTokenType.TYPE_KEYWORD, "expected 'type'");
        TypeToken nameToken = expect(TypeTokenType.IDENTIFIER, "expected a type name after 'type'");
        expect(TypeTokenType.LBRACE, "expected '{' after type name");
        List<FieldDecl> fields = new ArrayList<>();
        Set<String> seenFieldNames = new LinkedHashSet<>();
        while (!check(TypeTokenType.RBRACE)) {
            FieldDecl field = parseFieldDecl();
            if (!seenFieldNames.add(field.name())) {
                throw new TypeDslSyntaxException(
                        "Duplicate field '" + field.name() + "' on type " + nameToken.text());
            }
            fields.add(field);
        }
        expect(TypeTokenType.RBRACE, "expected '}' to close type " + nameToken.text());
        if (fields.isEmpty()) {
            throw new TypeDslSyntaxException("Type " + nameToken.text() + " declares no fields");
        }
        return new TypeDecl(nameToken.text(), fields);
    }

    private FieldDecl parseFieldDecl() {
        TypeToken fieldName = expect(TypeTokenType.IDENTIFIER, "expected a field name");
        expect(TypeTokenType.COLON, "expected ':' after field name " + fieldName.text());
        TypeToken typeName = expect(TypeTokenType.IDENTIFIER, "expected a field type after ':'");
        FieldType fieldType = parseFieldType(typeName);
        return new FieldDecl(fieldName.text(), fieldType);
    }

    private FieldType parseFieldType(TypeToken token) {
        try {
            return FieldType.valueOf(token.text());
        } catch (IllegalArgumentException e) {
            throw new TypeDslSyntaxException(
                    "Unknown field type '" + token.text() + "' at line " + token.line()
                            + " (expected STRING, NUMBER or BOOLEAN)");
        }
    }

    private boolean check(TypeTokenType type) {
        return tokens.get(pos).type() == type;
    }

    private TypeToken expect(TypeTokenType type, String message) {
        TypeToken token = tokens.get(pos);
        if (token.type() != type) {
            throw new TypeDslSyntaxException(message + " (got '" + token.text() + "' at line " + token.line() + ")");
        }
        pos++;
        return token;
    }
}
