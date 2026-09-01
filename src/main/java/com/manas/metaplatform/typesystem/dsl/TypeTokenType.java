package com.manas.metaplatform.typesystem.dsl;

/** Token kinds for the type-declaration DSL. Deliberately smaller than the query DSL's
 * token set (dsl.lexer.TokenType): this grammar has no operators, no literals, no WHERE
 * clause, only the shape needed to declare an entity's name and its typed fields. */
public enum TypeTokenType {
    TYPE_KEYWORD,
    IDENTIFIER,
    LBRACE,
    RBRACE,
    COLON,
    EOF
}
