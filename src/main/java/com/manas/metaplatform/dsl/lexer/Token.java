package com.manas.metaplatform.dsl.lexer;

public record Token(TokenType type, String text, int position) {
}
