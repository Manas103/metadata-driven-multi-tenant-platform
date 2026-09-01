package com.manas.metaplatform.typesystem.dsl;

/** One lexed token: its kind, the raw source text, and the 1-based line it started on
 * (carried purely for {@link TypeDslSyntaxException} messages). */
public record TypeToken(TypeTokenType type, String text, int line) {
}
