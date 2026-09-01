package com.manas.metaplatform.typesystem.dsl;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-rolled character-by-character tokenizer for the type-declaration DSL, in the same
 * style as {@code dsl.lexer.Lexer} for the query language: no regex-based scanning, one
 * character consumed at a time, tracking line numbers for error messages.
 *
 * <p>Grammar tokenized: {@code type Identifier { fieldName : TYPE ... }}, repeated for as
 * many types as the source declares. {@code //} starts a line comment.
 */
public final class TypeLexer {

    private final String source;
    private int pos;
    private int line = 1;

    public TypeLexer(String source) {
        this.source = source;
    }

    public List<TypeToken> tokenize() {
        List<TypeToken> tokens = new ArrayList<>();
        while (true) {
            skipWhitespaceAndComments();
            if (isAtEnd()) {
                tokens.add(new TypeToken(TypeTokenType.EOF, "", line));
                return tokens;
            }
            char c = peek();
            if (c == '{') {
                tokens.add(new TypeToken(TypeTokenType.LBRACE, "{", line));
                advance();
            } else if (c == '}') {
                tokens.add(new TypeToken(TypeTokenType.RBRACE, "}", line));
                advance();
            } else if (c == ':') {
                tokens.add(new TypeToken(TypeTokenType.COLON, ":", line));
                advance();
            } else if (Character.isLetter(c) || c == '_') {
                tokens.add(readIdentifierOrKeyword());
            } else {
                throw new TypeDslSyntaxException("Unexpected character '" + c + "' at line " + line);
            }
        }
    }

    private TypeToken readIdentifierOrKeyword() {
        int startLine = line;
        StringBuilder sb = new StringBuilder();
        while (!isAtEnd() && (Character.isLetterOrDigit(peek()) || peek() == '_')) {
            sb.append(advance());
        }
        String text = sb.toString();
        TypeTokenType type = "type".equals(text) ? TypeTokenType.TYPE_KEYWORD : TypeTokenType.IDENTIFIER;
        return new TypeToken(type, text, startLine);
    }

    private void skipWhitespaceAndComments() {
        while (!isAtEnd()) {
            char c = peek();
            if (c == '\n') {
                line++;
                advance();
            } else if (Character.isWhitespace(c)) {
                advance();
            } else if (c == '/' && peekNext() == '/') {
                while (!isAtEnd() && peek() != '\n') {
                    advance();
                }
            } else {
                return;
            }
        }
    }

    private boolean isAtEnd() {
        return pos >= source.length();
    }

    private char peek() {
        return source.charAt(pos);
    }

    private char peekNext() {
        return pos + 1 < source.length() ? source.charAt(pos + 1) : '\0';
    }

    private char advance() {
        return source.charAt(pos++);
    }
}
