package com.manas.metaplatform.dsl.lexer;

public enum TokenType {
    SELECT, FROM, WHERE, AND, OR,
    IDENT, STRING, NUMBER, TRUE, FALSE,
    COMMA, LPAREN, RPAREN,
    EQ, NEQ, GT, GTE, LT, LTE, LIKE,
    EOF
}
