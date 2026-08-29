package com.manas.metaplatform.dsl.lexer;

import com.manas.metaplatform.dsl.DslSyntaxException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A hand-rolled, character-by-character tokenizer for the SOQL-style DSL. This is the main
 * compiler's front door; the independent reference interpreter in
 * {@code reference.ReferenceInterpreter} deliberately does not reuse this class or its
 * scanning strategy (it tokenizes with a single regex pass instead), so the two
 * implementations share no lexing code at all.
 */
public final class Lexer {

    private final String source;
    private int pos;

    public Lexer(String source) {
        this.source = source;
        this.pos = 0;
    }

    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        Token t;
        do {
            t = nextToken();
            tokens.add(t);
        } while (t.type() != TokenType.EOF);
        return tokens;
    }

    private Token nextToken() {
        skipWhitespace();
        if (pos >= source.length()) {
            return new Token(TokenType.EOF, "", pos);
        }
        int start = pos;
        char c = source.charAt(pos);

        if (Character.isLetter(c) || c == '_') {
            return scanIdentOrKeyword(start);
        }
        if (Character.isDigit(c)) {
            return scanNumber(start);
        }
        if (c == '\'') {
            return scanString(start);
        }
        switch (c) {
            case ',':
                pos++;
                return new Token(TokenType.COMMA, ",", start);
            case '(':
                pos++;
                return new Token(TokenType.LPAREN, "(", start);
            case ')':
                pos++;
                return new Token(TokenType.RPAREN, ")", start);
            case '=':
                pos++;
                return new Token(TokenType.EQ, "=", start);
            case '!':
                if (peek(1) == '=') {
                    pos += 2;
                    return new Token(TokenType.NEQ, "!=", start);
                }
                throw new DslSyntaxException("unexpected '!' at position " + pos + ", did you mean '!='?");
            case '<':
                if (peek(1) == '=') {
                    pos += 2;
                    return new Token(TokenType.LTE, "<=", start);
                }
                if (peek(1) == '>') {
                    pos += 2;
                    return new Token(TokenType.NEQ, "<>", start);
                }
                pos++;
                return new Token(TokenType.LT, "<", start);
            case '>':
                if (peek(1) == '=') {
                    pos += 2;
                    return new Token(TokenType.GTE, ">=", start);
                }
                pos++;
                return new Token(TokenType.GT, ">", start);
            default:
                throw new DslSyntaxException("unexpected character '" + c + "' at position " + pos);
        }
    }

    private char peek(int offset) {
        int i = pos + offset;
        return i < source.length() ? source.charAt(i) : '\0';
    }

    private void skipWhitespace() {
        while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) {
            pos++;
        }
    }

    private Token scanIdentOrKeyword(int start) {
        while (pos < source.length() && (Character.isLetterOrDigit(source.charAt(pos)) || source.charAt(pos) == '_')) {
            pos++;
        }
        String text = source.substring(start, pos);
        String upper = text.toUpperCase(Locale.ROOT);
        TokenType type = switch (upper) {
            case "SELECT" -> TokenType.SELECT;
            case "FROM" -> TokenType.FROM;
            case "WHERE" -> TokenType.WHERE;
            case "AND" -> TokenType.AND;
            case "OR" -> TokenType.OR;
            case "LIKE" -> TokenType.LIKE;
            case "TRUE" -> TokenType.TRUE;
            case "FALSE" -> TokenType.FALSE;
            default -> TokenType.IDENT;
        };
        // Keyword tokens carry their canonical text; identifiers keep the user's own casing
        // so that field/object names remain case-sensitive as declared in the catalog.
        return new Token(type, type == TokenType.IDENT ? text : upper, start);
    }

    private Token scanNumber(int start) {
        while (pos < source.length() && Character.isDigit(source.charAt(pos))) {
            pos++;
        }
        if (pos < source.length() && source.charAt(pos) == '.' && pos + 1 < source.length()
                && Character.isDigit(source.charAt(pos + 1))) {
            pos++;
            while (pos < source.length() && Character.isDigit(source.charAt(pos))) {
                pos++;
            }
        }
        return new Token(TokenType.NUMBER, source.substring(start, pos), start);
    }

    private Token scanString(int start) {
        pos++; // consume opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= source.length()) {
                throw new DslSyntaxException("unterminated string literal starting at position " + start);
            }
            char c = source.charAt(pos);
            if (c == '\'') {
                if (peek(1) == '\'') {
                    // doubled single quote is an escaped literal quote, SQL-style
                    sb.append('\'');
                    pos += 2;
                    continue;
                }
                pos++; // consume closing quote
                break;
            }
            sb.append(c);
            pos++;
        }
        return new Token(TokenType.STRING, sb.toString(), start);
    }
}
